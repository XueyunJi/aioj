package com.aioj.next.ai.agent.profile.tool;

import com.aioj.next.ai.agent.context.DataClassification;
import com.aioj.next.ai.agent.context.TrustLevel;
import com.aioj.next.ai.agent.tool.AgentTool;
import com.aioj.next.ai.agent.tool.SourceRef;
import com.aioj.next.ai.agent.tool.ToolAuditLevel;
import com.aioj.next.ai.agent.tool.ToolDescriptor;
import com.aioj.next.ai.agent.tool.ToolExecutionContext;
import com.aioj.next.ai.agent.tool.ToolResult;
import com.aioj.next.ai.agent.tool.ToolRiskLevel;
import com.aioj.next.ai.agent.tool.ToolStatus;
import com.aioj.next.ai.persistence.entity.AiLearningProfileEntity;
import com.aioj.next.ai.persistence.entity.AiProfileSignalEntity;
import com.aioj.next.ai.persistence.mapper.AiLearningProfileMapper;
import com.aioj.next.ai.persistence.mapper.AiProfileSignalMapper;
import com.baomidou.mybatisplus.core.conditions.query.QueryWrapper;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.springframework.stereotype.Component;

import java.time.Duration;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Built-in profile tool {@code profile.search} (P2-5): read-only recall over
 * the caller's learning profile (masteries, weaknesses, misconceptions) with
 * related profile signal snippets. Frozen product decision Q4: only
 * {@code ACTIVE} profiles (not disabled, not deleted) are ever returned, and
 * {@code PENDING}/{@code REJECTED} signals are hard-filtered in SQL — neither
 * ever reaches the model. Returning zero signals is normal while the P2-6
 * aggregator is still pending (all existing signals are PENDING).
 */
@Component
public class ProfileSearchTool implements AgentTool {

    private static final int DEFAULT_TOP_K = 5;
    private static final int MAX_TOP_K = 10;
    private static final int SCAN_LIMIT = 50;
    private static final int MAX_QUERY_CHARS = 100;
    private static final int MAX_CATEGORY_CHARS = 50;
    private static final int DEFAULT_MAX_SIGNALS_PER_PROFILE = 2;
    private static final int MAX_SIGNALS_PER_PROFILE = 5;
    private static final int MAX_SNIPPET_CHARS = 300;
    private static final String STATE_ACTIVE = "ACTIVE";
    private static final List<String> EXCLUDED_SIGNAL_STATUSES = List.of("PENDING", "REJECTED");

    private final AiLearningProfileMapper profileMapper;
    private final AiProfileSignalMapper signalMapper;
    private final ObjectMapper objectMapper;
    private final ToolDescriptor descriptor;

    public ProfileSearchTool(AiLearningProfileMapper profileMapper, AiProfileSignalMapper signalMapper,
                             ObjectMapper objectMapper) {
        this.profileMapper = profileMapper;
        this.signalMapper = signalMapper;
        this.objectMapper = objectMapper;
        this.descriptor = new ToolDescriptor(
                "profile.search",
                "1.0.0",
                "Search the user's learning profile (masteries, weaknesses, misconceptions — ACTIVE entries "
                        + "only) together with related profile signal snippets. Call only when the answer "
                        + "depends on the user's level, mastery, or weak areas — do not call every turn.",
                buildSchema(),
                ToolRiskLevel.LOW,
                true,
                true,
                Set.of("AI_CHAT"),
                Set.of(DataClassification.USER_PRIVATE),
                2000,
                Duration.ofSeconds(5),
                ToolAuditLevel.FULL
        );
    }

    @Override
    public ToolDescriptor descriptor() {
        return descriptor;
    }

    @Override
    public ToolResult<Object> execute(ToolExecutionContext context, JsonNode input) {
        String query = input.path("query").isTextual() ? input.path("query").asText().trim() : "";
        if (query.length() > MAX_QUERY_CHARS) {
            query = query.substring(0, MAX_QUERY_CHARS);
        }
        String category = input.path("category").isTextual() ? input.path("category").asText().trim() : "";
        if (category.length() > MAX_CATEGORY_CHARS) {
            category = category.substring(0, MAX_CATEGORY_CHARS);
        }
        int topK = input.path("topK").isIntegralNumber()
                ? Math.min(MAX_TOP_K, Math.max(1, input.path("topK").asInt()))
                : DEFAULT_TOP_K;
        boolean includeSignals = !input.path("includeSignals").isBoolean()
                || input.path("includeSignals").asBoolean();
        int maxSignalsPerProfile = input.path("maxSignalsPerProfile").isIntegralNumber()
                ? Math.min(MAX_SIGNALS_PER_PROFILE, Math.max(0, input.path("maxSignalsPerProfile").asInt()))
                : DEFAULT_MAX_SIGNALS_PER_PROFILE;

        QueryWrapper<AiLearningProfileEntity> wrapper = new QueryWrapper<AiLearningProfileEntity>()
                .eq("user_id", context.userId())
                .eq("state", STATE_ACTIVE)
                .isNull("deleted_at")
                .isNull("disabled_at");
        if (!category.isEmpty()) {
            wrapper.eq("category", category);
        }
        if (!query.isEmpty()) {
            // Escape LIKE wildcards; MySQL LIKE treats backslash as the default escape char,
            // and the default utf8mb4 collation makes the match case-insensitive.
            wrapper.like("label", escapeLike(query));
        }
        wrapper.orderByDesc("updated_at").last("LIMIT " + SCAN_LIMIT);

        List<AiLearningProfileEntity> candidates;
        try {
            candidates = profileMapper.selectList(wrapper);
        } catch (RuntimeException ex) {
            return ToolResult.failure(null, ToolStatus.EXECUTION_ERROR, "PROFILE_SEARCH_FAILED",
                    "learning profile search failed");
        }

        List<Map<String, Object>> profiles = new ArrayList<>();
        List<SourceRef> sources = new ArrayList<>();
        for (AiLearningProfileEntity profile : candidates) {
            if (profiles.size() >= topK) {
                break;
            }
            Map<String, Object> item = new LinkedHashMap<>();
            item.put("profileId", String.valueOf(profile.id));
            item.put("category", profile.category);
            item.put("profileKey", profile.profileKey);
            item.put("label", profile.label);
            item.put("confidence", profile.confidence);
            item.put("evidenceCount", profile.evidenceCount);
            item.put("lastEvidenceAt", profile.lastEvidenceAt == null ? null : profile.lastEvidenceAt.toString());
            sources.add(new SourceRef("LEARNING_PROFILE", String.valueOf(profile.id)));

            List<Map<String, Object>> signals = new ArrayList<>();
            boolean fetchSignals = includeSignals && maxSignalsPerProfile > 0
                    && profile.profileKey != null && !profile.profileKey.isBlank();
            if (fetchSignals) {
                List<AiProfileSignalEntity> signalRows;
                try {
                    signalRows = signalMapper.selectList(new QueryWrapper<AiProfileSignalEntity>()
                            .eq("user_id", context.userId())
                            .notIn("status", EXCLUDED_SIGNAL_STATUSES)
                            .eq("knowledge_node", profile.profileKey)
                            .orderByDesc("created_at")
                            .last("LIMIT " + maxSignalsPerProfile));
                } catch (RuntimeException ex) {
                    return ToolResult.failure(null, ToolStatus.EXECUTION_ERROR, "SIGNAL_SEARCH_FAILED",
                            "profile signal search failed");
                }
                for (AiProfileSignalEntity signal : signalRows) {
                    Map<String, Object> signalItem = new LinkedHashMap<>();
                    signalItem.put("signalId", String.valueOf(signal.getId()));
                    signalItem.put("signalType", signal.getSignalType());
                    signalItem.put("knowledgeNode", signal.getKnowledgeNode());
                    signalItem.put("polarity", signal.getPolarity());
                    signalItem.put("score", signal.getScore());
                    signalItem.put("snippet", extractSnippet(signal.getPayloadJson()));
                    signalItem.put("createdAt", signal.getCreatedAt() == null ? null : signal.getCreatedAt().toString());
                    signals.add(signalItem);
                    sources.add(new SourceRef("PROFILE_SIGNAL", String.valueOf(signal.getId())));
                }
            }
            item.put("signals", signals);
            profiles.add(item);
        }

        Map<String, Object> data = new LinkedHashMap<>();
        data.put("profiles", profiles);
        data.put("profileCount", profiles.size());
        data.put("query", query);
        data.put("category", category.isEmpty() ? null : category);
        return ToolResult.success(null, data, sources, DataClassification.USER_PRIVATE, TrustLevel.DERIVED_SUMMARY);
    }

    /** Signals carry their text in {@code payloadJson} as {@code {"signal":"..."}}; malformed payloads degrade to the raw JSON. */
    private String extractSnippet(String payloadJson) {
        String snippet = payloadJson;
        if (payloadJson != null) {
            try {
                JsonNode signal = objectMapper.readTree(payloadJson).path("signal");
                if (signal.isTextual()) {
                    snippet = signal.asText();
                }
            } catch (Exception ignored) {
                // Keep the raw payload as the snippet fallback.
            }
        }
        if (snippet != null && snippet.length() > MAX_SNIPPET_CHARS) {
            snippet = snippet.substring(0, MAX_SNIPPET_CHARS);
        }
        return snippet;
    }

    private String escapeLike(String term) {
        return term.replace("\\", "\\\\").replace("%", "\\%").replace("_", "\\_");
    }

    private JsonNode buildSchema() {
        ObjectNode schema = objectMapper.createObjectNode();
        schema.put("type", "object");
        schema.put("additionalProperties", false);
        ObjectNode properties = schema.putObject("properties");
        ObjectNode query = properties.putObject("query");
        query.put("type", "string");
        query.put("description", "Optional keyword matched case-insensitively against the profile label. "
                + "Omit to recall by category or all active profiles.");
        query.put("maxLength", MAX_QUERY_CHARS);
        ObjectNode category = properties.putObject("category");
        category.put("type", "string");
        category.put("description", "Optional profile category filter (e.g. weakness).");
        category.put("maxLength", MAX_CATEGORY_CHARS);
        ObjectNode topK = properties.putObject("topK");
        topK.put("type", "integer");
        topK.put("description", "Maximum number of profiles to return (default 5, max 10).");
        topK.put("minimum", 1);
        topK.put("maximum", MAX_TOP_K);
        ObjectNode includeSignals = properties.putObject("includeSignals");
        includeSignals.put("type", "boolean");
        includeSignals.put("description", "Whether to attach related profile signal snippets (default true).");
        ObjectNode maxSignalsPerProfile = properties.putObject("maxSignalsPerProfile");
        maxSignalsPerProfile.put("type", "integer");
        maxSignalsPerProfile.put("description", "Maximum signals attached per profile (default 2, max 5; 0 disables).");
        maxSignalsPerProfile.put("minimum", 0);
        maxSignalsPerProfile.put("maximum", MAX_SIGNALS_PER_PROFILE);
        return schema;
    }
}
