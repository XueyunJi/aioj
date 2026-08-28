package com.aioj.next.ai.agent.retrieval.builtin;

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
import com.aioj.next.ai.persistence.entity.AiMessageEntity;
import com.aioj.next.ai.persistence.mapper.AiMessageMapper;
import com.baomidou.mybatisplus.core.conditions.query.QueryWrapper;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.springframework.stereotype.Component;

import java.time.Duration;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

/**
 * Built-in retrieval tool {@code context.search_exact} (design doc §4.3 P0):
 * case-insensitive exact-substring search over the current conversation's
 * messages. Deterministic SQL LIKE matching only — semantic/vector retrieval
 * arrives with the P1 recall plane.
 */
@Component
public class ContextSearchExactTool implements AgentTool {

    private static final int MAX_TERMS = 5;
    private static final int MAX_TERM_CHARS = 100;
    private static final int DEFAULT_TOP_K = 5;
    private static final int MAX_TOP_K = 10;
    private static final int SCAN_LIMIT = 200;
    private static final int EXCERPT_RADIUS = 120;

    private final AiMessageMapper messageMapper;
    private final ObjectMapper objectMapper;
    private final ToolDescriptor descriptor;

    public ContextSearchExactTool(AiMessageMapper messageMapper, ObjectMapper objectMapper) {
        this.messageMapper = messageMapper;
        this.objectMapper = objectMapper;
        this.descriptor = new ToolDescriptor(
                "context.search_exact",
                "1.0.0",
                "Search the current conversation for messages containing the given exact substrings "
                        + "(case-insensitive). Use when you need to recall what was said earlier; "
                        + "prefer specific, distinctive terms. Returns excerpts only — cite message ids.",
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
        List<String> terms = normalizedTerms(input);
        if (terms.isEmpty()) {
            return ToolResult.failure(null, ToolStatus.SCHEMA_ERROR, "EMPTY_TERMS",
                    "exactTerms must contain at least one non-blank term");
        }
        int topK = input.path("topK").isIntegralNumber()
                ? Math.min(MAX_TOP_K, Math.max(1, input.path("topK").asInt()))
                : DEFAULT_TOP_K;
        if (context.conversationId() == null || context.conversationId().isBlank()) {
            return success(List.of(), topK, terms);
        }
        QueryWrapper<AiMessageEntity> query = new QueryWrapper<AiMessageEntity>()
                .eq("conversation_id", context.conversationId())
                .in("role", List.of("user", "assistant"))
                .and(wrapper -> {
                    for (String term : terms) {
                        wrapper.or().like("content", term);
                    }
                })
                .orderByDesc("created_at")
                .last("LIMIT " + SCAN_LIMIT);
        List<AiMessageEntity> candidates;
        try {
            candidates = messageMapper.selectList(query);
        } catch (RuntimeException ex) {
            return ToolResult.failure(null, ToolStatus.EXECUTION_ERROR, "SEARCH_QUERY_FAILED",
                    "message search failed");
        }
        List<Map<String, Object>> ranked = new ArrayList<>();
        for (AiMessageEntity message : candidates) {
            String content = message.getContent() == null ? "" : message.getContent();
            List<String> matched = matchedTerms(content, terms);
            if (matched.isEmpty()) {
                continue;
            }
            Map<String, Object> hit = new LinkedHashMap<>();
            hit.put("messageId", String.valueOf(message.getId()));
            hit.put("role", message.getRole());
            hit.put("createdAt", message.getCreatedAt() == null ? null : message.getCreatedAt().toString());
            hit.put("matchedTerms", matched);
            hit.put("excerpt", excerpt(content, matched.get(0)));
            hit.put("trustLevel", "user".equals(message.getRole())
                    ? TrustLevel.USER_PROVIDED.name()
                    : TrustLevel.MODEL_INFERRED.name());
            ranked.add(hit);
        }
        ranked.sort(Comparator.comparingInt((Map<String, Object> hit) -> ((List<?>) hit.get("matchedTerms")).size())
                .reversed());
        List<Map<String, Object>> hits = ranked.size() > topK ? ranked.subList(0, topK) : ranked;
        return success(hits, topK, terms);
    }

    private ToolResult<Object> success(List<Map<String, Object>> hits, int topK, List<String> terms) {
        Map<String, Object> data = new LinkedHashMap<>();
        data.put("hits", hits);
        data.put("hitCount", hits.size());
        data.put("topK", topK);
        data.put("searchedTerms", terms);
        data.put("scope", "CURRENT_CONVERSATION");
        List<SourceRef> sources = hits.stream()
                .map(hit -> new SourceRef("MESSAGE", String.valueOf(hit.get("messageId"))))
                .toList();
        return ToolResult.success(null, data, sources, DataClassification.USER_PRIVATE, TrustLevel.USER_PROVIDED);
    }

    private List<String> normalizedTerms(JsonNode input) {
        List<String> terms = new ArrayList<>();
        JsonNode exactTerms = input.path("exactTerms");
        if (!exactTerms.isArray()) {
            return terms;
        }
        for (JsonNode term : exactTerms) {
            if (!term.isTextual()) {
                continue;
            }
            String normalized = term.asText().trim();
            if (normalized.isEmpty()) {
                continue;
            }
            if (normalized.length() > MAX_TERM_CHARS) {
                normalized = normalized.substring(0, MAX_TERM_CHARS);
            }
            if (!terms.contains(normalized)) {
                terms.add(normalized);
            }
            if (terms.size() >= MAX_TERMS) {
                break;
            }
        }
        return terms;
    }

    private List<String> matchedTerms(String content, List<String> terms) {
        String lowered = content.toLowerCase(Locale.ROOT);
        List<String> matched = new ArrayList<>();
        for (String term : terms) {
            if (lowered.contains(term.toLowerCase(Locale.ROOT))) {
                matched.add(term);
            }
        }
        return matched;
    }

    private String excerpt(String content, String firstMatchedTerm) {
        int index = content.toLowerCase(Locale.ROOT).indexOf(firstMatchedTerm.toLowerCase(Locale.ROOT));
        if (index < 0) {
            return content.length() <= EXCERPT_RADIUS * 2 ? content : content.substring(0, EXCERPT_RADIUS * 2) + "…";
        }
        int start = Math.max(0, index - EXCERPT_RADIUS);
        int end = Math.min(content.length(), index + firstMatchedTerm.length() + EXCERPT_RADIUS);
        StringBuilder excerpt = new StringBuilder();
        if (start > 0) {
            excerpt.append('…');
        }
        excerpt.append(content, start, end);
        if (end < content.length()) {
            excerpt.append('…');
        }
        return excerpt.toString();
    }

    private JsonNode buildSchema() {
        ObjectNode schema = objectMapper.createObjectNode();
        schema.put("type", "object");
        schema.put("additionalProperties", false);
        schema.putArray("required").add("exactTerms");
        ObjectNode properties = schema.putObject("properties");
        ObjectNode exactTerms = properties.putObject("exactTerms");
        exactTerms.put("type", "array");
        exactTerms.put("description", "Exact substrings to look for (case-insensitive), most specific first.");
        exactTerms.put("minItems", 1);
        exactTerms.put("maxItems", MAX_TERMS);
        ObjectNode items = exactTerms.putObject("items");
        items.put("type", "string");
        items.put("minLength", 1);
        items.put("maxLength", MAX_TERM_CHARS);
        ObjectNode scope = properties.putObject("scope");
        scope.put("type", "string");
        scope.put("description", "Search scope. Only CURRENT_CONVERSATION is supported.");
        scope.putArray("enum").add("CURRENT_CONVERSATION");
        ObjectNode topK = properties.putObject("topK");
        topK.put("type", "integer");
        topK.put("description", "Maximum number of hits to return (default 5, max 10).");
        topK.put("minimum", 1);
        topK.put("maximum", MAX_TOP_K);
        return schema;
    }
}
