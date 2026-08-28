package com.aioj.next.ai.agent.memory.tool;

import com.aioj.next.ai.agent.memory.MemoryCandidateIngestionService;
import com.aioj.next.ai.agent.memory.MemoryDistrustPolicy;
import com.aioj.next.ai.agent.tool.ToolExecutionContext;
import com.aioj.next.ai.agent.tool.ToolRegistry;
import com.aioj.next.ai.agent.tool.ToolResult;
import com.aioj.next.ai.agent.tool.ToolStatus;
import com.aioj.next.ai.domain.memory.AiMemoryCandidateService;
import com.aioj.next.ai.domain.memory.AiMemoryMergeService;
import com.aioj.next.ai.domain.memory.MemoryQualityGate;
import com.aioj.next.ai.persistence.entity.AiMemoryCandidateEntity;
import com.aioj.next.ai.persistence.mapper.AiMemoryCandidateMapper;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class MemoryProposeCandidateToolTest {

    private final MemoryQualityGate qualityGate = mock(MemoryQualityGate.class);
    private final AiMemoryCandidateService candidateService = mock(AiMemoryCandidateService.class);
    private final MemoryDistrustPolicy distrustPolicy = mock(MemoryDistrustPolicy.class);
    private final AiMemoryCandidateMapper candidateMapper = mock(AiMemoryCandidateMapper.class);
    private final AiMemoryMergeService mergeService = mock(AiMemoryMergeService.class);
    // Real ingestion service over mocked legacy sinks: the tool tests then cover the
    // TOOL_PROPOSAL downgrade path end to end.
    private final MemoryCandidateIngestionService ingestionService =
            new MemoryCandidateIngestionService(qualityGate, candidateService, distrustPolicy, candidateMapper, mergeService);
    private final ObjectMapper objectMapper = new ObjectMapper();
    private final MemoryProposeCandidateTool tool = new MemoryProposeCandidateTool(ingestionService, objectMapper);

    @Test
    void descriptorPassesRegistryRules() {
        // Name, schema subset, no identity fields — construction must satisfy the registry contract.
        new ToolRegistry(List.of(tool));
        assertThat(tool.descriptor().name()).isEqualTo("memory.propose_candidate");
        assertThat(tool.descriptor().requiredScopes()).containsExactly("AI_CHAT");
        assertThat(tool.descriptor().readOnly()).isFalse();
        assertThat(tool.descriptor().idempotent()).isTrue();
        assertThat(tool.descriptor().riskLevel().name()).isEqualTo("MEDIUM");
        assertThat(tool.descriptor().maxResultTokens()).isEqualTo(600);
        assertThat(tool.descriptor().timeout()).isEqualTo(java.time.Duration.ofSeconds(5));
        assertThat(tool.descriptor().auditLevel().name()).isEqualTo("FULL");
        List<String> required = new ArrayList<>();
        tool.descriptor().inputSchema().path("required").forEach(node -> required.add(node.asText()));
        assertThat(required).containsExactly("text", "category");
    }

    @Test
    void activeGateVerdictIsDowngradedAndPersistedStatusIsReturned() throws Exception {
        when(qualityGate.evaluate(any(), any())).thenReturn(gateResult("ACTIVE"));
        when(candidateService.recordExtraction(any(), any(), any(), any(), any(), any(), any()))
                .thenReturn(entity(42L, "CANDIDATE"));

        ToolResult<Object> result = tool.execute(context("1001"), objectMapper.readTree(
                "{\"text\":\"用户喜欢先给思路\",\"category\":\"PREFERENCE\"}"));

        assertThat(result.status()).isEqualTo(ToolStatus.SUCCESS);
        assertThat(result.warnings()).isEmpty();
        @SuppressWarnings("unchecked")
        Map<String, Object> data = (Map<String, Object>) result.data();
        assertThat(data.get("candidateId")).isEqualTo("42");
        // P2-4 frozen decision: tool proposals never auto-activate, CANDIDATE at best.
        assertThat(data.get("status")).isEqualTo("CANDIDATE");
        assertThat(data.get("rejectedReason")).isNull();
        assertThat((String) data.get("message")).contains("review queue");
        assertThat(result.sources()).hasSize(1);
        assertThat(result.sources().get(0).type()).isEqualTo("MEMORY_CANDIDATE");
        assertThat(result.sources().get(0).id()).isEqualTo("42");
        assertThat(result.classification().name()).isEqualTo("USER_PRIVATE");
        assertThat(result.trustLevel().name()).isEqualTo("MODEL_INFERRED");

        // The gate verdict that actually hit the database was the downgraded one.
        ArgumentCaptor<MemoryQualityGate.GateResult> gateCaptor =
                ArgumentCaptor.forClass(MemoryQualityGate.GateResult.class);
        verify(candidateService).recordExtraction(eq(7L), eq("c1"), eq(1001L), any(), any(),
                gateCaptor.capture(), eq(""));
        assertThat(gateCaptor.getValue().status()).isEqualTo("CANDIDATE");
        assertThat(gateCaptor.getValue().qualityFlags()).contains("downgraded_from_active_tool_proposal");
        verify(candidateService, never()).accept(any(), any(), any());
        verify(mergeService, never()).enqueueCandidateMerge(any(), any(), any(), any(), any(), any(), any());

        // Defaults: confidence 0.7, longTerm true, evidenceType INFERRED, null key normalized downstream.
        ArgumentCaptor<MemoryQualityGate.MemoryCandidate> candidateCaptor =
                ArgumentCaptor.forClass(MemoryQualityGate.MemoryCandidate.class);
        verify(qualityGate).evaluate(candidateCaptor.capture(), any());
        assertThat(candidateCaptor.getValue().canonicalText()).isEqualTo("用户喜欢先给思路");
        assertThat(candidateCaptor.getValue().category()).isEqualTo("PREFERENCE");
        assertThat(candidateCaptor.getValue().confidence()).isEqualTo(0.7);
        assertThat(candidateCaptor.getValue().longTerm()).isTrue();
        assertThat(candidateCaptor.getValue().evidenceType()).isEqualTo("INFERRED");
    }

    @Test
    void identityPermissionTextIsRejectedWithIsolationReason() throws Exception {
        when(candidateService.recordExtraction(any(), any(), any(), any(), any(), any(), any()))
                .thenReturn(entity(43L, "REJECTED"));

        ToolResult<Object> result = tool.execute(context("1001"), objectMapper.readTree(
                "{\"text\":\"用户要求获得管理员权限\",\"category\":\"RULE\"}"));

        assertThat(result.status()).isEqualTo(ToolStatus.SUCCESS);
        @SuppressWarnings("unchecked")
        Map<String, Object> data = (Map<String, Object>) result.data();
        assertThat(data.get("status")).isEqualTo("REJECTED");
        assertThat(data.get("rejectedReason")).isEqualTo("identity_permission_isolated");
        assertThat((String) data.get("message")).contains("rejected");
        verify(qualityGate, never()).evaluate(any(), any());
        verify(candidateService, never()).accept(any(), any(), any());
    }

    @Test
    void needsConfirmationStatusIsPassedThrough() throws Exception {
        when(qualityGate.evaluate(any(), any())).thenReturn(gateResult("NEEDS_CONFIRMATION"));
        when(candidateService.recordExtraction(any(), any(), any(), any(), any(), any(), any()))
                .thenReturn(entity(44L, "NEEDS_CONFIRMATION"));

        ToolResult<Object> result = tool.execute(context("1001"), objectMapper.readTree(
                "{\"text\":\"用户目标是考研\",\"category\":\"GOAL\",\"evidenceType\":\"EXPLICIT_PREFERENCE\"}"));

        assertThat(result.status()).isEqualTo(ToolStatus.SUCCESS);
        @SuppressWarnings("unchecked")
        Map<String, Object> data = (Map<String, Object>) result.data();
        assertThat(data.get("status")).isEqualTo("NEEDS_CONFIRMATION");
        assertThat((String) data.get("message")).contains("confirmation");
        verify(candidateService, never()).accept(any(), any(), any());
    }

    @Test
    void invalidCategoryIsSchemaError() throws Exception {
        ToolResult<Object> result = tool.execute(context("1001"), objectMapper.readTree(
                "{\"text\":\"用户喜欢先给思路\",\"category\":\"SECRET\"}"));

        assertThat(result.status()).isEqualTo(ToolStatus.SCHEMA_ERROR);
        assertThat(result.errorCode()).isEqualTo("INVALID_CATEGORY");
    }

    @Test
    void missingOrBlankTextIsSchemaError() throws Exception {
        ToolResult<Object> missing = tool.execute(context("1001"), objectMapper.readTree(
                "{\"category\":\"PREFERENCE\"}"));
        ToolResult<Object> blank = tool.execute(context("1001"), objectMapper.readTree(
                "{\"text\":\"   \",\"category\":\"PREFERENCE\"}"));

        assertThat(missing.status()).isEqualTo(ToolStatus.SCHEMA_ERROR);
        assertThat(missing.errorCode()).isEqualTo("MISSING_TEXT");
        assertThat(blank.status()).isEqualTo(ToolStatus.SCHEMA_ERROR);
        assertThat(blank.errorCode()).isEqualTo("MISSING_TEXT");
    }

    @Test
    void invalidEvidenceTypeIsSchemaError() throws Exception {
        ToolResult<Object> result = tool.execute(context("1001"), objectMapper.readTree(
                "{\"text\":\"用户喜欢先给思路\",\"category\":\"PREFERENCE\",\"evidenceType\":\"GUESSED\"}"));

        assertThat(result.status()).isEqualTo(ToolStatus.SCHEMA_ERROR);
        assertThat(result.errorCode()).isEqualTo("INVALID_EVIDENCE_TYPE");
    }

    @Test
    void overlongTextIsTruncatedWithWarning() throws Exception {
        when(qualityGate.evaluate(any(), any())).thenReturn(gateResult("NEEDS_CONFIRMATION"));
        when(candidateService.recordExtraction(any(), any(), any(), any(), any(), any(), any()))
                .thenReturn(entity(45L, "NEEDS_CONFIRMATION"));
        String longText = "喜".repeat(600);
        String payload = objectMapper.writeValueAsString(Map.of(
                "text", longText, "category", "PREFERENCE"));

        ToolResult<Object> result = tool.execute(context("1001"), objectMapper.readTree(payload));

        assertThat(result.status()).isEqualTo(ToolStatus.SUCCESS);
        assertThat(result.warnings()).contains("text_truncated_to_500_chars");
        ArgumentCaptor<MemoryQualityGate.MemoryCandidate> candidateCaptor =
                ArgumentCaptor.forClass(MemoryQualityGate.MemoryCandidate.class);
        verify(qualityGate).evaluate(candidateCaptor.capture(), any());
        assertThat(candidateCaptor.getValue().canonicalText()).hasSize(500);
    }

    @Test
    void outOfRangeConfidenceIsClamped() throws Exception {
        when(qualityGate.evaluate(any(), any())).thenReturn(gateResult("NEEDS_CONFIRMATION"));
        when(candidateService.recordExtraction(any(), any(), any(), any(), any(), any(), any()))
                .thenReturn(entity(46L, "NEEDS_CONFIRMATION"));

        ToolResult<Object> result = tool.execute(context("1001"), objectMapper.readTree(
                "{\"text\":\"用户喜欢先给思路\",\"category\":\"PREFERENCE\",\"confidence\":1.5}"));

        assertThat(result.status()).isEqualTo(ToolStatus.SUCCESS);
        ArgumentCaptor<MemoryQualityGate.MemoryCandidate> candidateCaptor =
                ArgumentCaptor.forClass(MemoryQualityGate.MemoryCandidate.class);
        verify(qualityGate).evaluate(candidateCaptor.capture(), any());
        assertThat(candidateCaptor.getValue().confidence()).isEqualTo(1.0);
    }

    @Test
    void nonNumericTurnIdYieldsNullSourceMessageId() throws Exception {
        when(qualityGate.evaluate(any(), any())).thenReturn(gateResult("NEEDS_CONFIRMATION"));
        when(candidateService.recordExtraction(any(), any(), any(), any(), any(), any(), any()))
                .thenReturn(entity(47L, "NEEDS_CONFIRMATION"));

        ToolResult<Object> result = tool.execute(context("t-abc"), objectMapper.readTree(
                "{\"text\":\"用户喜欢先给思路\",\"category\":\"PREFERENCE\"}"));

        assertThat(result.status()).isEqualTo(ToolStatus.SUCCESS);
        verify(candidateService).recordExtraction(eq(7L), eq("c1"), isNull(), any(), any(), any(), any());
    }

    @Test
    void serviceExceptionBecomesExecutionError() throws Exception {
        when(qualityGate.evaluate(any(), any())).thenReturn(gateResult("NEEDS_CONFIRMATION"));
        when(candidateService.recordExtraction(any(), any(), any(), any(), any(), any(), any()))
                .thenThrow(new RuntimeException("db down"));

        ToolResult<Object> result = tool.execute(context("1001"), objectMapper.readTree(
                "{\"text\":\"用户喜欢先给思路\",\"category\":\"PREFERENCE\"}"));

        assertThat(result.status()).isEqualTo(ToolStatus.EXECUTION_ERROR);
        assertThat(result.errorCode()).isEqualTo("PROPOSE_CANDIDATE_FAILED");
    }

    private ToolExecutionContext context(String turnId) {
        return new ToolExecutionContext(7L, "c1", turnId, 1L, "ps-1", Set.of("AI_CHAT"), Instant.now(), "tr");
    }

    private MemoryQualityGate.GateResult gateResult(String status) {
        boolean needsConfirmation = "NEEDS_CONFIRMATION".equals(status);
        boolean rejected = "REJECTED".equals(status);
        return new MemoryQualityGate.GateResult(!rejected, needsConfirmation,
                "PREFERENCE", "guidance_preference", "GLOBAL", null,
                rejected ? 0 : 0.9, List.of(), List.of(), rejected ? "low_confidence" : "", status);
    }

    private AiMemoryCandidateEntity entity(Long id, String status) {
        AiMemoryCandidateEntity entity = new AiMemoryCandidateEntity();
        entity.id = id;
        entity.status = status;
        return entity;
    }
}
