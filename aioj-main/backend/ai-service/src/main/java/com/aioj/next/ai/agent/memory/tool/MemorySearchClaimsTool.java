package com.aioj.next.ai.agent.memory.tool;

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
import com.aioj.next.ai.persistence.entity.AiMemoryClaimEntity;
import com.aioj.next.ai.persistence.mapper.AiMemoryClaimMapper;
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
 * Built-in memory tool {@code memory.search_claims} (P2-3): read-only recall
 * over the caller's long-term memory claims. Frozen product decision Q4: only
 * {@code ACTIVE} claims are ever returned — DISABLED/SUPERSEDED/RESOLVED/
 * CANDIDATE rows are hard-filtered in SQL and never reach the model.
 */
@Component
public class MemorySearchClaimsTool implements AgentTool {

    private static final int DEFAULT_TOP_K = 5;
    private static final int MAX_TOP_K = 10;
    private static final int SCAN_LIMIT = 50;
    private static final int MAX_QUERY_CHARS = 100;
    private static final String STATUS_ACTIVE = "ACTIVE";
    private static final Set<String> KNOWN_CATEGORIES = Set.of(
            "PREFERENCE", "RULE", "HABIT", "GOAL", "PROFILE", "WEAKNESS", "MANUAL_NOTE");

    private final AiMemoryClaimMapper claimMapper;
    private final ObjectMapper objectMapper;
    private final ToolDescriptor descriptor;

    public MemorySearchClaimsTool(AiMemoryClaimMapper claimMapper, ObjectMapper objectMapper) {
        this.claimMapper = claimMapper;
        this.objectMapper = objectMapper;
        this.descriptor = new ToolDescriptor(
                "memory.search_claims",
                "1.0.0",
                "Search the user's long-term memory (preferences, rules, goals, weaknesses, profile facts). "
                        + "Call only when the answer depends on the user's preferences, level, or habits — "
                        + "do not call every turn. Returns ACTIVE memory claims only; supporting evidence "
                        + "quotes must be fetched with memory.fetch_evidence.",
                buildSchema(),
                ToolRiskLevel.LOW,
                true,
                true,
                Set.of("AI_CHAT"),
                Set.of(DataClassification.USER_PRIVATE),
                1500,
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
        if (!category.isEmpty() && !KNOWN_CATEGORIES.contains(category)) {
            return ToolResult.failure(null, ToolStatus.SCHEMA_ERROR, "INVALID_CATEGORY",
                    "category must be one of " + KNOWN_CATEGORIES);
        }
        int topK = input.path("topK").isIntegralNumber()
                ? Math.min(MAX_TOP_K, Math.max(1, input.path("topK").asInt()))
                : DEFAULT_TOP_K;

        QueryWrapper<AiMemoryClaimEntity> wrapper = new QueryWrapper<AiMemoryClaimEntity>()
                .eq("user_id", context.userId())
                .eq("status", STATUS_ACTIVE);
        if (!category.isEmpty()) {
            wrapper.eq("category", category);
        }
        if (!query.isEmpty()) {
            // Escape LIKE wildcards; MySQL LIKE treats backslash as the default escape char,
            // and the default utf8mb4 collation makes the match case-insensitive.
            wrapper.like("canonical_text", escapeLike(query));
        }
        wrapper.orderByDesc("pinned", "updated_at").last("LIMIT " + SCAN_LIMIT);

        List<AiMemoryClaimEntity> candidates;
        try {
            candidates = claimMapper.selectList(wrapper);
        } catch (RuntimeException ex) {
            return ToolResult.failure(null, ToolStatus.EXECUTION_ERROR, "SEARCH_QUERY_FAILED",
                    "memory claim search failed");
        }

        List<Map<String, Object>> claims = new ArrayList<>();
        for (AiMemoryClaimEntity claim : candidates) {
            Map<String, Object> item = new LinkedHashMap<>();
            item.put("claimId", String.valueOf(claim.id));
            item.put("category", claim.category);
            item.put("memoryKey", claim.memoryKey);
            item.put("canonicalText", claim.canonicalText);
            item.put("confidence", claim.confidence);
            item.put("supportCount", claim.supportCount);
            item.put("pinned", claim.pinned);
            item.put("lastConfirmedAt", claim.lastConfirmedAt == null ? null : claim.lastConfirmedAt.toString());
            claims.add(item);
            if (claims.size() >= topK) {
                break;
            }
        }
        List<SourceRef> sources = claims.stream()
                .map(item -> new SourceRef("MEMORY_CLAIM", String.valueOf(item.get("claimId"))))
                .toList();
        Map<String, Object> data = new LinkedHashMap<>();
        data.put("claims", claims);
        data.put("claimCount", claims.size());
        data.put("query", query);
        data.put("category", category.isEmpty() ? null : category);
        return ToolResult.success(null, data, sources, DataClassification.USER_PRIVATE, TrustLevel.DERIVED_SUMMARY);
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
        query.put("description", "Optional keyword matched case-insensitively against the memory text. "
                + "Omit to recall by category or all active memories.");
        query.put("maxLength", MAX_QUERY_CHARS);
        ObjectNode category = properties.putObject("category");
        category.put("type", "string");
        category.put("description", "Optional memory category filter.");
        KNOWN_CATEGORIES.forEach(category.putArray("enum")::add);
        ObjectNode topK = properties.putObject("topK");
        topK.put("type", "integer");
        topK.put("description", "Maximum number of claims to return (default 5, max 10).");
        topK.put("minimum", 1);
        topK.put("maximum", MAX_TOP_K);
        return schema;
    }
}
