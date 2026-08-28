package com.aioj.next.ai.agent.memory.tool;

import com.aioj.next.ai.agent.context.DataClassification;
import com.aioj.next.ai.agent.context.TrustLevel;
import com.aioj.next.ai.agent.memory.MemoryCandidateIngestionService;
import com.aioj.next.ai.agent.tool.AgentTool;
import com.aioj.next.ai.agent.tool.SourceRef;
import com.aioj.next.ai.agent.tool.ToolAuditLevel;
import com.aioj.next.ai.agent.tool.ToolDescriptor;
import com.aioj.next.ai.agent.tool.ToolExecutionContext;
import com.aioj.next.ai.agent.tool.ToolResult;
import com.aioj.next.ai.agent.tool.ToolRiskLevel;
import com.aioj.next.ai.agent.tool.ToolStatus;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.springframework.stereotype.Component;

import java.time.Duration;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

/**
 * Built-in memory tool {@code memory.propose_candidate} (P2-4): lets the model
 * propose one long-term memory candidate mid-conversation. Frozen product
 * decision: tool proposals are MORE injection-exposed than curator side-channel
 * proposals, so they land as CANDIDATE at best — never auto-ACTIVE — via
 * {@link MemoryCandidateIngestionService.IngestMode#TOOL_PROPOSAL}. The model
 * is told the real persisted status (candidateId + final status), never a
 * fabricated one.
 */
@Component
public class MemoryProposeCandidateTool implements AgentTool {

    static final int MAX_TEXT_CHARS = 500;
    static final int MAX_KEY_CHARS = 100;
    static final double DEFAULT_CONFIDENCE = 0.7;
    static final String WARNING_TEXT_TRUNCATED = "text_truncated_to_500_chars";
    private static final String EVIDENCE_DEFAULT = "INFERRED";
    private static final Set<String> KNOWN_CATEGORIES = Set.of(
            "PREFERENCE", "RULE", "HABIT", "GOAL", "PROFILE", "WEAKNESS", "MANUAL_NOTE");
    private static final Set<String> KNOWN_EVIDENCE_TYPES = Set.of(
            "EXPLICIT_PREFERENCE", "REPEATED_BEHAVIOR", "INFERRED");
    private static final String STATUS_REJECTED = "REJECTED";
    private static final String STATUS_NEEDS_CONFIRMATION = "NEEDS_CONFIRMATION";

    private final MemoryCandidateIngestionService ingestionService;
    private final ObjectMapper objectMapper;
    private final ToolDescriptor descriptor;

    public MemoryProposeCandidateTool(MemoryCandidateIngestionService ingestionService, ObjectMapper objectMapper) {
        this.ingestionService = ingestionService;
        this.objectMapper = objectMapper;
        this.descriptor = new ToolDescriptor(
                "memory.propose_candidate",
                "1.0.0",
                "Propose one long-term memory about the user. Call ONLY when the user has explicitly stated "
                        + "a stable preference, rule, goal, habit, or profile fact about themselves "
                        + "(e.g. \"always show the idea before code\", \"I am preparing for NOIP\"). "
                        + "Never propose problem statement details, sample data, current code, or temporary "
                        + "one-off session content. Identity/permission/role content is automatically rejected. "
                        + "The proposal is recorded in a review queue only — it never becomes active memory "
                        + "without passing the quality gate and user confirmation. "
                        + "Use evidenceType=EXPLICIT_PREFERENCE when the user stated it directly.",
                buildSchema(),
                ToolRiskLevel.MEDIUM,
                false,
                true,
                Set.of("AI_CHAT"),
                Set.of(DataClassification.USER_PRIVATE),
                600,
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
        if (!input.path("text").isTextual() || input.path("text").asText().trim().isEmpty()) {
            return ToolResult.failure(null, ToolStatus.SCHEMA_ERROR, "MISSING_TEXT",
                    "text is required and must be a non-empty string");
        }
        String text = input.path("text").asText().trim();
        List<String> warnings = new ArrayList<>();
        if (text.length() > MAX_TEXT_CHARS) {
            text = text.substring(0, MAX_TEXT_CHARS);
            warnings.add(WARNING_TEXT_TRUNCATED);
        }
        String category = input.path("category").isTextual()
                ? input.path("category").asText().trim().toUpperCase(Locale.ROOT) : "";
        if (!KNOWN_CATEGORIES.contains(category)) {
            return ToolResult.failure(null, ToolStatus.SCHEMA_ERROR, "INVALID_CATEGORY",
                    "category must be one of " + KNOWN_CATEGORIES);
        }
        String memoryKey = input.path("memoryKey").isTextual() ? input.path("memoryKey").asText().trim() : null;
        if (memoryKey != null && memoryKey.isEmpty()) {
            memoryKey = null;
        }
        if (memoryKey != null && memoryKey.length() > MAX_KEY_CHARS) {
            memoryKey = memoryKey.substring(0, MAX_KEY_CHARS);
        }
        double confidence = input.path("confidence").isNumber()
                ? input.path("confidence").asDouble() : DEFAULT_CONFIDENCE;
        confidence = Math.max(0.0, Math.min(1.0, confidence));
        boolean longTerm = !input.path("longTerm").isBoolean() || input.path("longTerm").asBoolean();
        String evidenceType = input.path("evidenceType").isTextual()
                ? input.path("evidenceType").asText().trim().toUpperCase(Locale.ROOT) : EVIDENCE_DEFAULT;
        if (evidenceType.isEmpty()) {
            evidenceType = EVIDENCE_DEFAULT;
        }
        if (!KNOWN_EVIDENCE_TYPES.contains(evidenceType)) {
            return ToolResult.failure(null, ToolStatus.SCHEMA_ERROR, "INVALID_EVIDENCE_TYPE",
                    "evidenceType must be one of " + KNOWN_EVIDENCE_TYPES);
        }

        MemoryCandidateIngestionService.CandidateProposal proposal =
                new MemoryCandidateIngestionService.CandidateProposal(
                        text, category, memoryKey, confidence, longTerm, evidenceType);
        MemoryCandidateIngestionService.IngestResult result;
        try {
            result = ingestionService.ingest(context.userId(), context.conversationId(),
                    parseTurnId(context.turnId()), List.of(proposal), null, null,
                    MemoryCandidateIngestionService.IngestMode.TOOL_PROPOSAL);
        } catch (RuntimeException ex) {
            return ToolResult.failure(null, ToolStatus.EXECUTION_ERROR, "PROPOSE_CANDIDATE_FAILED",
                    "memory candidate proposal failed");
        }
        if (result.items().isEmpty()) {
            return ToolResult.failure(null, ToolStatus.EXECUTION_ERROR, "PROPOSE_CANDIDATE_FAILED",
                    "memory candidate proposal produced no result");
        }

        MemoryCandidateIngestionService.ItemResult item = result.items().get(0);
        Map<String, Object> data = new LinkedHashMap<>();
        data.put("candidateId", item.candidateId() == null ? null : String.valueOf(item.candidateId()));
        data.put("status", item.finalStatus());
        data.put("rejectedReason", item.rejectedReason());
        data.put("message", messageFor(item));
        List<SourceRef> sources = item.candidateId() == null
                ? List.of()
                : List.of(new SourceRef("MEMORY_CANDIDATE", String.valueOf(item.candidateId())));
        return new ToolResult<>(null, ToolStatus.SUCCESS, data, sources,
                DataClassification.USER_PRIVATE, TrustLevel.MODEL_INFERRED,
                null, false, null, null, warnings, null, null);
    }

    private String messageFor(MemoryCandidateIngestionService.ItemResult item) {
        if (STATUS_REJECTED.equals(item.finalStatus())) {
            return "Proposal rejected by the memory quality gate and recorded for audit only.";
        }
        if (STATUS_NEEDS_CONFIRMATION.equals(item.finalStatus())) {
            return "Candidate recorded; it becomes active memory only after user confirmation.";
        }
        return "Candidate recorded in the review queue; it is not active memory yet.";
    }

    /** turnId is a string on the trusted context; best-effort numeric parse, else no source message. */
    private Long parseTurnId(String turnId) {
        if (turnId == null || turnId.isBlank()) {
            return null;
        }
        try {
            return Long.parseLong(turnId.trim());
        } catch (NumberFormatException ex) {
            return null;
        }
    }

    private JsonNode buildSchema() {
        ObjectNode schema = objectMapper.createObjectNode();
        schema.put("type", "object");
        schema.put("additionalProperties", false);
        ObjectNode properties = schema.putObject("properties");
        ObjectNode text = properties.putObject("text");
        text.put("type", "string");
        text.put("description", "Canonical memory text: a stable, user-specific preference, rule, goal, "
                + "habit, or profile fact. No problem details, no code, no temporary content.");
        text.put("minLength", 1);
        text.put("maxLength", MAX_TEXT_CHARS);
        ObjectNode category = properties.putObject("category");
        category.put("type", "string");
        category.put("description", "Memory category.");
        KNOWN_CATEGORIES.forEach(category.putArray("enum")::add);
        ObjectNode memoryKey = properties.putObject("memoryKey");
        memoryKey.put("type", "string");
        memoryKey.put("description", "Optional stable snake_case key for this memory (max 100 chars).");
        memoryKey.put("maxLength", MAX_KEY_CHARS);
        ObjectNode confidence = properties.putObject("confidence");
        confidence.put("type", "number");
        confidence.put("description", "Confidence that the user really stated this (default 0.7).");
        confidence.put("minimum", 0);
        confidence.put("maximum", 1);
        ObjectNode longTerm = properties.putObject("longTerm");
        longTerm.put("type", "boolean");
        longTerm.put("description", "Whether this is a stable long-term fact (default true). "
                + "Short-term/session content is rejected by the quality gate.");
        ObjectNode evidenceType = properties.putObject("evidenceType");
        evidenceType.put("type", "string");
        evidenceType.put("description", "Evidence kind (default INFERRED). Use EXPLICIT_PREFERENCE when the "
                + "user stated it directly; REPEATED_BEHAVIOR only for patterns observed across turns.");
        KNOWN_EVIDENCE_TYPES.forEach(evidenceType.putArray("enum")::add);
        schema.putArray("required").add("text").add("category");
        return schema;
    }
}
