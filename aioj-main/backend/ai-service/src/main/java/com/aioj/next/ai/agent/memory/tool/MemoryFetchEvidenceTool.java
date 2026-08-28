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
import com.aioj.next.ai.persistence.entity.AiMemoryEvidenceEntity;
import com.aioj.next.ai.persistence.mapper.AiMemoryClaimMapper;
import com.aioj.next.ai.persistence.mapper.AiMemoryEvidenceMapper;
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
 * Built-in memory tool {@code memory.fetch_evidence} (P2-3): read-only fetch of
 * the verbatim evidence quotes backing one of the caller's memory claims. The
 * claim is always re-scoped to the caller ({@code user_id} from the trusted
 * execution context) so a model can never read another user's memory.
 */
@Component
public class MemoryFetchEvidenceTool implements AgentTool {

    private static final int DEFAULT_MAX_EVIDENCE = 3;
    private static final int MAX_MAX_EVIDENCE = 10;
    private static final int MAX_EVIDENCE_TEXT_CHARS = 500;
    private static final String STATUS_ACTIVE = "ACTIVE";

    private final AiMemoryClaimMapper claimMapper;
    private final AiMemoryEvidenceMapper evidenceMapper;
    private final ObjectMapper objectMapper;
    private final ToolDescriptor descriptor;

    public MemoryFetchEvidenceTool(AiMemoryClaimMapper claimMapper, AiMemoryEvidenceMapper evidenceMapper,
                                   ObjectMapper objectMapper) {
        this.claimMapper = claimMapper;
        this.evidenceMapper = evidenceMapper;
        this.objectMapper = objectMapper;
        this.descriptor = new ToolDescriptor(
                "memory.fetch_evidence",
                "1.0.0",
                "Fetch the verbatim evidence quotes backing one long-term memory claim of the current user. "
                        + "Call with a claimId returned by memory.search_claims when you need the exact user "
                        + "statements a memory derives from, or to check whether a memory is still active.",
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
        if (!input.path("claimId").isIntegralNumber()) {
            return ToolResult.failure(null, ToolStatus.SCHEMA_ERROR, "MISSING_CLAIM_ID",
                    "claimId is required and must be an integer");
        }
        long claimId = input.path("claimId").asLong();
        int maxEvidence = input.path("maxEvidence").isIntegralNumber()
                ? Math.min(MAX_MAX_EVIDENCE, Math.max(1, input.path("maxEvidence").asInt()))
                : DEFAULT_MAX_EVIDENCE;

        AiMemoryClaimEntity claim;
        List<AiMemoryEvidenceEntity> evidenceRows;
        try {
            claim = claimMapper.selectOne(new QueryWrapper<AiMemoryClaimEntity>()
                    .eq("id", claimId)
                    .eq("user_id", context.userId()));
            if (claim == null) {
                return ToolResult.failure(null, ToolStatus.NOT_FOUND, "CLAIM_NOT_FOUND",
                        "no memory claim with this id for the current user");
            }
            evidenceRows = evidenceMapper.selectList(new QueryWrapper<AiMemoryEvidenceEntity>()
                    .eq("claim_id", claimId)
                    .eq("user_id", context.userId())
                    .orderByDesc("created_at")
                    .last("LIMIT " + maxEvidence));
        } catch (RuntimeException ex) {
            return ToolResult.failure(null, ToolStatus.EXECUTION_ERROR, "EVIDENCE_QUERY_FAILED",
                    "memory evidence fetch failed");
        }

        List<Map<String, Object>> evidence = new ArrayList<>();
        for (AiMemoryEvidenceEntity row : evidenceRows) {
            Map<String, Object> item = new LinkedHashMap<>();
            item.put("evidenceId", String.valueOf(row.id));
            item.put("evidenceType", row.evidenceType);
            item.put("evidenceText", truncate(row.evidenceText));
            item.put("confidence", row.confidence);
            item.put("conversationId", row.conversationId);
            item.put("messageId", row.messageId == null ? null : String.valueOf(row.messageId));
            item.put("createdAt", row.createdAt == null ? null : row.createdAt.toString());
            evidence.add(item);
        }
        Map<String, Object> claimSummary = new LinkedHashMap<>();
        claimSummary.put("claimId", String.valueOf(claim.id));
        claimSummary.put("category", claim.category);
        claimSummary.put("memoryKey", claim.memoryKey);
        claimSummary.put("canonicalText", claim.canonicalText);
        claimSummary.put("status", claim.status);

        Map<String, Object> data = new LinkedHashMap<>();
        data.put("claim", claimSummary);
        data.put("evidence", evidence);
        data.put("evidenceCount", evidence.size());
        List<String> warnings = new ArrayList<>();
        if (!STATUS_ACTIVE.equals(claim.status)) {
            data.put("warning", "claim_not_active");
            warnings.add("claim_not_active");
        }
        List<SourceRef> sources = evidence.stream()
                .map(item -> new SourceRef("MEMORY_EVIDENCE", String.valueOf(item.get("evidenceId"))))
                .toList();
        return new ToolResult<>(null, ToolStatus.SUCCESS, data, sources,
                DataClassification.USER_PRIVATE, TrustLevel.USER_PROVIDED,
                null, false, null, null, warnings, null, null);
    }

    private String truncate(String text) {
        if (text == null || text.length() <= MAX_EVIDENCE_TEXT_CHARS) {
            return text;
        }
        return text.substring(0, MAX_EVIDENCE_TEXT_CHARS) + "…";
    }

    private JsonNode buildSchema() {
        ObjectNode schema = objectMapper.createObjectNode();
        schema.put("type", "object");
        schema.put("additionalProperties", false);
        schema.putArray("required").add("claimId");
        ObjectNode properties = schema.putObject("properties");
        ObjectNode claimId = properties.putObject("claimId");
        claimId.put("type", "integer");
        claimId.put("description", "Id of the memory claim to fetch evidence for "
                + "(from a memory.search_claims result).");
        ObjectNode maxEvidence = properties.putObject("maxEvidence");
        maxEvidence.put("type", "integer");
        maxEvidence.put("description", "Maximum number of evidence quotes to return (default 3, max 10).");
        maxEvidence.put("minimum", 1);
        maxEvidence.put("maximum", MAX_MAX_EVIDENCE);
        return schema;
    }
}
