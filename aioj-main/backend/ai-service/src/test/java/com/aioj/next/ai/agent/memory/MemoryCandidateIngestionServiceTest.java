package com.aioj.next.ai.agent.memory;

import com.aioj.next.ai.domain.AiCompletion;
import com.aioj.next.ai.domain.memory.AiMemoryCandidateService;
import com.aioj.next.ai.domain.memory.AiMemoryMergeService;
import com.aioj.next.ai.domain.memory.MemoryQualityGate;
import com.aioj.next.ai.persistence.entity.AiMemoryCandidateEntity;
import com.aioj.next.ai.persistence.mapper.AiMemoryCandidateMapper;
import com.aioj.next.contract.ai.AiMemoryCandidateActionRequest;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

class MemoryCandidateIngestionServiceTest {

    private final MemoryQualityGate qualityGate = mock(MemoryQualityGate.class);
    private final AiMemoryCandidateService candidateService = mock(AiMemoryCandidateService.class);
    private final MemoryDistrustPolicy distrustPolicy = mock(MemoryDistrustPolicy.class);
    private final AiMemoryCandidateMapper candidateMapper = mock(AiMemoryCandidateMapper.class);
    private final AiMemoryMergeService mergeService = mock(AiMemoryMergeService.class);
    private final MemoryCandidateIngestionService service =
            new MemoryCandidateIngestionService(qualityGate, candidateService, distrustPolicy, candidateMapper, mergeService);

    @Test
    void activeGateResultIsRecordedAndAcceptedAutomatically() {
        when(qualityGate.evaluate(any(), any())).thenReturn(gateResult("ACTIVE"));
        when(candidateService.recordExtraction(any(), any(), any(), any(), any(), any(), any()))
                .thenReturn(entity(42L, "ACTIVE"));

        MemoryCandidateIngestionService.IngestResult result = service.ingest(
                7L, "c-1", 100L, List.of(proposal("用户喜欢先给思路")), "以后都先给思路", "好的");

        assertThat(result.active()).isEqualTo(1);
        assertThat(result.needsConfirmation()).isZero();
        assertThat(result.rejected()).isZero();
        assertThat(result.preRejected()).isZero();

        ArgumentCaptor<AiCompletion.MemorySignal> signalCaptor = ArgumentCaptor.forClass(AiCompletion.MemorySignal.class);
        verify(candidateService).recordExtraction(eq(7L), eq("c-1"), eq(100L), signalCaptor.capture(),
                any(), any(), eq("以后都先给思路"));
        assertThat(signalCaptor.getValue().type()).isEqualTo("PREFERENCE");
        assertThat(signalCaptor.getValue().content()).isEqualTo("用户喜欢先给思路");
        assertThat(signalCaptor.getValue().reason()).isEqualTo("curator_digest");
        assertThat(signalCaptor.getValue().evidenceType()).isEqualTo("EXPLICIT_PREFERENCE");

        ArgumentCaptor<AiMemoryCandidateActionRequest> acceptCaptor =
                ArgumentCaptor.forClass(AiMemoryCandidateActionRequest.class);
        // Auto-activation goes straight to the merge queue (accept() rejects
        // freshly gate-ACTIVE rows as non-reviewable by design).
        verify(mergeService).enqueueCandidateMerge(eq(7L), eq(42L), acceptCaptor.capture(),
                isNull(), isNull(), eq("AUTO_MEMORY_EXTRACTION"), eq("auto_memory_extraction"));
        assertThat(acceptCaptor.getValue().reason()).isEqualTo("auto_memory_extraction");
        verify(candidateService, never()).accept(any(), any(), any());
    }

    @Test
    void needsConfirmationIsRecordedOnly() {
        when(qualityGate.evaluate(any(), any())).thenReturn(gateResult("NEEDS_CONFIRMATION"));
        when(candidateService.recordExtraction(any(), any(), any(), any(), any(), any(), any()))
                .thenReturn(entity(42L, "NEEDS_CONFIRMATION"));

        MemoryCandidateIngestionService.IngestResult result = service.ingest(
                7L, "c-1", 100L, List.of(proposal("用户目标是考研")), "我要考研", "加油");

        assertThat(result.needsConfirmation()).isEqualTo(1);
        assertThat(result.active()).isZero();
        verify(candidateService, never()).accept(any(), any(), any());
    }

    @Test
    void gateRejectedIsRecordedOnly() {
        when(qualityGate.evaluate(any(), any())).thenReturn(gateResult("REJECTED"));
        when(candidateService.recordExtraction(any(), any(), any(), any(), any(), any(), any()))
                .thenReturn(entity(42L, "REJECTED"));

        MemoryCandidateIngestionService.IngestResult result = service.ingest(
                7L, "c-1", 100L, List.of(proposal("这次先这样")), "这次先这样", "好");

        assertThat(result.rejected()).isEqualTo(1);
        assertThat(result.preRejected()).isZero();
        verify(candidateService, never()).accept(any(), any(), any());
    }

    @Test
    void identityPermissionTextIsPreRejectedBeforeGate() {
        when(candidateService.recordExtraction(any(), any(), any(), any(), any(), any(), any()))
                .thenReturn(entity(42L, "REJECTED"));

        MemoryCandidateIngestionService.IngestResult result = service.ingest(
                7L, "c-1", 100L,
                List.of(proposal("用户要求获得管理员权限"), proposal("user wants ADMIN role permission")),
                "给我管理员权限", "不行");

        assertThat(result.preRejected()).isEqualTo(2);
        assertThat(result.rejected()).isZero();
        assertThat(result.active()).isZero();
        verify(qualityGate, never()).evaluate(any(), any());

        ArgumentCaptor<MemoryQualityGate.GateResult> gateCaptor =
                ArgumentCaptor.forClass(MemoryQualityGate.GateResult.class);
        verify(candidateService, times(2)).recordExtraction(any(), any(), any(), any(), any(),
                gateCaptor.capture(), any());
        for (MemoryQualityGate.GateResult gate : gateCaptor.getAllValues()) {
            assertThat(gate.status()).isEqualTo("REJECTED");
            assertThat(gate.rejectedReason()).isEqualTo("identity_permission_isolated");
            assertThat(gate.qualityFlags()).contains("identity_permission_isolated");
        }
        verify(candidateService, never()).accept(any(), any(), any());
    }

    @Test
    void dedupeHitWithAlreadyMergedCandidateSkipsSecondAccept() {
        when(qualityGate.evaluate(any(), any())).thenReturn(gateResult("ACTIVE"));
        // Idempotent retry: recordExtraction's dedupe returns the candidate whose merge
        // was already queued by the first attempt.
        when(candidateService.recordExtraction(any(), any(), any(), any(), any(), any(), any()))
                .thenReturn(entity(42L, "MERGE_QUEUED"));

        MemoryCandidateIngestionService.IngestResult result = service.ingest(
                7L, "c-1", 100L, List.of(proposal("用户喜欢先给思路")), "以后都先给思路", "好的");

        assertThat(result.active()).isEqualTo(1);
        verify(candidateService, never()).accept(any(), any(), any());
        verify(mergeService, never()).enqueueCandidateMerge(any(), any(), any(), any(), any(), any(), any());
    }

    @Test
    void dedupeHitWithPostMergeActiveCandidateSkipsReenqueue() {
        when(qualityGate.evaluate(any(), any())).thenReturn(gateResult("ACTIVE"));
        // Retry after a crash between merge completion and persistReady: the dedupe twin
        // is post-merge ACTIVE and carries the memoryMerge marker — never re-enqueue.
        AiMemoryCandidateEntity twin = entity(42L, "ACTIVE");
        twin.valueJson = "{\"memoryMerge\":{\"action\":\"AUTO_MEMORY_EXTRACTION\"}}";
        when(candidateService.recordExtraction(any(), any(), any(), any(), any(), any(), any()))
                .thenReturn(twin);

        MemoryCandidateIngestionService.IngestResult result = service.ingest(
                7L, "c-1", 100L, List.of(proposal("用户喜欢先给思路")), "以后都先给思路", "好的");

        assertThat(result.active()).isEqualTo(1);
        verify(mergeService, never()).enqueueCandidateMerge(any(), any(), any(), any(), any(), any(), any());
    }

    @Test
    void blankProposalsAreSkipped() {
        MemoryCandidateIngestionService.IngestResult result = service.ingest(
                7L, "c-1", 100L,
                List.of(proposal("   "),
                        new MemoryCandidateIngestionService.CandidateProposal(null, null, null, 0.5, null, null)),
                "u", "a");

        assertThat(result.active()).isZero();
        assertThat(result.needsConfirmation()).isZero();
        assertThat(result.rejected()).isZero();
        assertThat(result.preRejected()).isZero();
        verifyNoInteractions(qualityGate, candidateService);
    }

    @Test
    void missingFieldsFallBackToDefaults() {
        when(qualityGate.evaluate(any(), any())).thenReturn(gateResult("NEEDS_CONFIRMATION"));
        when(candidateService.recordExtraction(any(), any(), any(), any(), any(), any(), any()))
                .thenReturn(entity(42L, "NEEDS_CONFIRMATION"));

        service.ingest(7L, "c-1", 100L,
                List.of(new MemoryCandidateIngestionService.CandidateProposal(
                        "用户偏好详细讲解", null, null, 0.8, null, null)),
                "u", "a");

        ArgumentCaptor<MemoryQualityGate.MemoryCandidate> candidateCaptor =
                ArgumentCaptor.forClass(MemoryQualityGate.MemoryCandidate.class);
        verify(qualityGate).evaluate(candidateCaptor.capture(), any());
        MemoryQualityGate.MemoryCandidate candidate = candidateCaptor.getValue();
        assertThat(candidate.category()).isEqualTo("MANUAL_NOTE");
        assertThat(candidate.memoryKey()).isEqualTo("manual_note");
        assertThat(candidate.canonicalText()).isEqualTo("用户偏好详细讲解");
        assertThat(candidate.evidenceType()).isEqualTo("INFERRED");
        assertThat(candidate.longTerm()).isTrue();
        assertThat(candidate.scopeType()).isEqualTo("GLOBAL");
        assertThat(candidate.scopeId()).isNull();
        assertThat(candidate.valueJson()).isEqualTo("{}");
        assertThat(candidate.confidence()).isEqualTo(0.8);
        assertThat(candidate.problemSpecific()).isFalse();
        assertThat(candidate.hypothetical()).isFalse();
        assertThat(candidate.quoted()).isFalse();
        assertThat(candidate.needsConfirmation()).isFalse();
    }

    @Test
    void evidenceTextIsTruncatedTo500Chars() {
        when(qualityGate.evaluate(any(), any())).thenReturn(gateResult("NEEDS_CONFIRMATION"));
        when(candidateService.recordExtraction(any(), any(), any(), any(), any(), any(), any()))
                .thenReturn(entity(42L, "NEEDS_CONFIRMATION"));

        service.ingest(7L, "c-1", 100L, List.of(proposal("用户喜欢先给思路")), "x".repeat(600), "a");

        ArgumentCaptor<String> evidenceCaptor = ArgumentCaptor.forClass(String.class);
        verify(candidateService).recordExtraction(any(), any(), any(), any(), any(), any(),
                evidenceCaptor.capture());
        assertThat(evidenceCaptor.getValue()).hasSize(500);
    }

    @Test
    void toolProposalDowngradesActiveGateToCandidateAndNeverAccepts() {
        when(qualityGate.evaluate(any(), any())).thenReturn(gateResult("ACTIVE"));
        when(candidateService.recordExtraction(any(), any(), any(), any(), any(), any(), any()))
                .thenReturn(entity(42L, "CANDIDATE"));

        MemoryCandidateIngestionService.IngestResult result = service.ingest(
                7L, "c-1", 100L, List.of(proposal("用户喜欢先给思路")), null, null,
                MemoryCandidateIngestionService.IngestMode.TOOL_PROPOSAL);

        assertThat(result.active()).isZero();
        assertThat(result.needsConfirmation()).isZero();
        assertThat(result.rejected()).isZero();
        assertThat(result.preRejected()).isZero();
        verify(candidateService, never()).accept(any(), any(), any());

        ArgumentCaptor<MemoryQualityGate.GateResult> gateCaptor =
                ArgumentCaptor.forClass(MemoryQualityGate.GateResult.class);
        verify(candidateService).recordExtraction(any(), any(), any(), any(), any(),
                gateCaptor.capture(), any());
        MemoryQualityGate.GateResult persisted = gateCaptor.getValue();
        assertThat(persisted.status()).isEqualTo("CANDIDATE");
        assertThat(persisted.qualityFlags()).contains("downgraded_from_active_tool_proposal");
        // Untouched fields are preserved from the original ACTIVE verdict.
        assertThat(persisted.normalizedCategory()).isEqualTo("PREFERENCE");
        assertThat(persisted.normalizedKey()).isEqualTo("guidance_preference");
        assertThat(persisted.scopeType()).isEqualTo("GLOBAL");
        assertThat(persisted.writeScore()).isEqualTo(0.9);

        assertThat(result.items()).hasSize(1);
        MemoryCandidateIngestionService.ItemResult item = result.items().get(0);
        assertThat(item.candidateId()).isEqualTo(42L);
        assertThat(item.finalStatus()).isEqualTo("CANDIDATE");
        assertThat(item.preRejected()).isFalse();
        assertThat(item.rejectedReason()).isNull();
        assertThat(item.text()).isEqualTo("用户喜欢先给思路");
    }

    @Test
    void toolProposalNeverAcceptsEvenWhenDedupeReturnsActiveEntity() {
        when(qualityGate.evaluate(any(), any())).thenReturn(gateResult("ACTIVE"));
        // Defensive: even if a dedupe hit surfaces an already-ACTIVE row, TOOL_PROPOSAL
        // mode must still refuse to call accept.
        when(candidateService.recordExtraction(any(), any(), any(), any(), any(), any(), any()))
                .thenReturn(entity(42L, "ACTIVE"));

        MemoryCandidateIngestionService.IngestResult result = service.ingest(
                7L, "c-1", 100L, List.of(proposal("用户喜欢先给思路")), null, null,
                MemoryCandidateIngestionService.IngestMode.TOOL_PROPOSAL);

        verify(candidateService, never()).accept(any(), any(), any());
        assertThat(result.active()).isZero();
        assertThat(result.items().get(0).finalStatus()).isEqualTo("ACTIVE");
    }

    @Test
    void toolProposalIdentityPermissionIsStillPreRejectedBeforeGate() {
        when(candidateService.recordExtraction(any(), any(), any(), any(), any(), any(), any()))
                .thenReturn(entity(43L, "REJECTED"));

        MemoryCandidateIngestionService.IngestResult result = service.ingest(
                7L, "c-1", 100L, List.of(proposal("用户要求获得管理员权限")), null, null,
                MemoryCandidateIngestionService.IngestMode.TOOL_PROPOSAL);

        assertThat(result.preRejected()).isEqualTo(1);
        verify(qualityGate, never()).evaluate(any(), any());
        verify(candidateService, never()).accept(any(), any(), any());

        MemoryCandidateIngestionService.ItemResult item = result.items().get(0);
        assertThat(item.preRejected()).isTrue();
        assertThat(item.finalStatus()).isEqualTo("REJECTED");
        assertThat(item.rejectedReason()).isEqualTo("identity_permission_isolated");
        assertThat(item.candidateId()).isEqualTo(43L);
    }

    @Test
    void curatorAutoPathPopulatesItemResults() {
        when(qualityGate.evaluate(any(), any())).thenReturn(gateResult("NEEDS_CONFIRMATION"));
        when(candidateService.recordExtraction(any(), any(), any(), any(), any(), any(), any()))
                .thenReturn(entity(42L, "NEEDS_CONFIRMATION"));

        MemoryCandidateIngestionService.IngestResult result = service.ingest(
                7L, "c-1", 100L, List.of(proposal("用户目标是考研")), "我要考研", "加油");

        assertThat(result.needsConfirmation()).isEqualTo(1);
        assertThat(result.items()).hasSize(1);
        MemoryCandidateIngestionService.ItemResult item = result.items().get(0);
        assertThat(item.candidateId()).isEqualTo(42L);
        assertThat(item.finalStatus()).isEqualTo("NEEDS_CONFIRMATION");
        assertThat(item.preRejected()).isFalse();
    }

    @Test
    void distrustedKeyDowngradesActiveGateToCandidateAndNeverAccepts() {
        when(qualityGate.evaluate(any(), any())).thenReturn(gateResult("ACTIVE"));
        when(distrustPolicy.isDistrusted(any(), any(), any(), any(), any(), any())).thenReturn(true);
        when(candidateService.recordExtraction(any(), any(), any(), any(), any(), any(), any()))
                .thenReturn(entity(42L, "CANDIDATE"));

        MemoryCandidateIngestionService.IngestResult result = service.ingest(
                7L, "c-1", 100L, List.of(proposal("用户喜欢先给思路")), "以后都先给思路", "好的");

        // V3 P2-7 frozen decision: a user-distrusted key is never auto-activated.
        assertThat(result.active()).isZero();
        assertThat(result.needsConfirmation()).isZero();
        assertThat(result.rejected()).isZero();
        verify(candidateService, never()).accept(any(), any(), any());
        verify(distrustPolicy).isDistrusted(7L, "GLOBAL", null, "PREFERENCE", "guidance_preference", "用户喜欢先给思路");

        ArgumentCaptor<MemoryQualityGate.GateResult> gateCaptor =
                ArgumentCaptor.forClass(MemoryQualityGate.GateResult.class);
        verify(candidateService).recordExtraction(any(), any(), any(), any(), any(),
                gateCaptor.capture(), any());
        MemoryQualityGate.GateResult persisted = gateCaptor.getValue();
        assertThat(persisted.status()).isEqualTo("CANDIDATE");
        assertThat(persisted.qualityFlags()).contains("distrusted_key_no_auto_activation");
        // Untouched fields are preserved from the original ACTIVE verdict.
        assertThat(persisted.normalizedCategory()).isEqualTo("PREFERENCE");
        assertThat(persisted.normalizedKey()).isEqualTo("guidance_preference");
        assertThat(persisted.writeScore()).isEqualTo(0.9);
        assertThat(result.items().get(0).finalStatus()).isEqualTo("CANDIDATE");
    }

    @Test
    void distrustedKeyAlsoDowngradesInToolProposalMode() {
        when(qualityGate.evaluate(any(), any())).thenReturn(gateResult("ACTIVE"));
        when(distrustPolicy.isDistrusted(any(), any(), any(), any(), any(), any())).thenReturn(true);
        when(candidateService.recordExtraction(any(), any(), any(), any(), any(), any(), any()))
                .thenReturn(entity(42L, "CANDIDATE"));

        MemoryCandidateIngestionService.IngestResult result = service.ingest(
                7L, "c-1", 100L, List.of(proposal("用户喜欢先给思路")), null, null,
                MemoryCandidateIngestionService.IngestMode.TOOL_PROPOSAL);

        assertThat(result.active()).isZero();
        verify(candidateService, never()).accept(any(), any(), any());
        ArgumentCaptor<MemoryQualityGate.GateResult> gateCaptor =
                ArgumentCaptor.forClass(MemoryQualityGate.GateResult.class);
        verify(candidateService).recordExtraction(any(), any(), any(), any(), any(),
                gateCaptor.capture(), any());
        assertThat(gateCaptor.getValue().status()).isEqualTo("CANDIDATE");
        assertThat(gateCaptor.getValue().qualityFlags()).contains("distrusted_key_no_auto_activation");
    }

    @Test
    void trustedKeyKeepsAutomaticAcceptFlow() {
        when(qualityGate.evaluate(any(), any())).thenReturn(gateResult("ACTIVE"));
        when(distrustPolicy.isDistrusted(any(), any(), any(), any(), any(), any())).thenReturn(false);
        when(candidateService.recordExtraction(any(), any(), any(), any(), any(), any(), any()))
                .thenReturn(entity(42L, "ACTIVE"));

        MemoryCandidateIngestionService.IngestResult result = service.ingest(
                7L, "c-1", 100L, List.of(proposal("用户喜欢先给思路")), "以后都先给思路", "好的");

        assertThat(result.active()).isEqualTo(1);
        verify(mergeService).enqueueCandidateMerge(eq(7L), eq(42L), any(), isNull(), isNull(),
                eq("AUTO_MEMORY_EXTRACTION"), eq("auto_memory_extraction"));
    }

    @Test
    void nonActiveGateSkipsDistrustLookup() {
        when(qualityGate.evaluate(any(), any())).thenReturn(gateResult("NEEDS_CONFIRMATION"));
        when(candidateService.recordExtraction(any(), any(), any(), any(), any(), any(), any()))
                .thenReturn(entity(42L, "NEEDS_CONFIRMATION"));

        service.ingest(7L, "c-1", 100L, List.of(proposal("用户目标是考研")), "我要考研", "加油");

        verify(distrustPolicy, never()).isDistrusted(any(), any(), any(), any(), any(), any());
    }

    @Test
    void nullSourceMessageDedupeHitReturnsExistingCandidateWithoutWrite() {
        when(candidateMapper.selectList(any())).thenReturn(List.of(candidateRow(42L, "CANDIDATE",
                "Guidance_Preference", "用户喜欢先给思路")));

        MemoryCandidateIngestionService.IngestResult result = service.ingest(
                7L, "c-1", null, List.of(proposal("用户喜欢先给思路")), "以后都先给思路", "好的");

        assertThat(result.active()).isZero();
        assertThat(result.needsConfirmation()).isZero();
        assertThat(result.rejected()).isZero();
        assertThat(result.preRejected()).isZero();
        verify(candidateService, never()).recordExtraction(any(), any(), any(), any(), any(), any(), any());
        verify(candidateService, never()).accept(any(), any(), any());
        verify(qualityGate, never()).evaluate(any(), any());

        assertThat(result.items()).hasSize(1);
        MemoryCandidateIngestionService.ItemResult item = result.items().get(0);
        assertThat(item.candidateId()).isEqualTo(42L);
        assertThat(item.finalStatus()).isEqualTo("CANDIDATE");
        assertThat(item.deduplicated()).isTrue();
        assertThat(item.preRejected()).isFalse();
        assertThat(item.rejectedReason()).isNull();
    }

    @Test
    void nullSourceMessageDedupeAlsoAppliesInToolProposalMode() {
        when(candidateMapper.selectList(any())).thenReturn(List.of(candidateRow(77L, "ACTIVE",
                "guidance_preference", "用户喜欢先给思路")));

        MemoryCandidateIngestionService.IngestResult result = service.ingest(
                7L, "c-1", null, List.of(proposal("用户喜欢先给思路")), null, null,
                MemoryCandidateIngestionService.IngestMode.TOOL_PROPOSAL);

        verify(candidateService, never()).recordExtraction(any(), any(), any(), any(), any(), any(), any());
        verify(candidateService, never()).accept(any(), any(), any());
        MemoryCandidateIngestionService.ItemResult item = result.items().get(0);
        assertThat(item.candidateId()).isEqualTo(77L);
        assertThat(item.finalStatus()).isEqualTo("ACTIVE");
        assertThat(item.deduplicated()).isTrue();
    }

    @Test
    void nullSourceMessageDedupeMissProceedsWithNormalFlow() {
        when(candidateMapper.selectList(any())).thenReturn(List.of());
        when(qualityGate.evaluate(any(), any())).thenReturn(gateResult("NEEDS_CONFIRMATION"));
        when(candidateService.recordExtraction(any(), any(), any(), any(), any(), any(), any()))
                .thenReturn(entity(42L, "NEEDS_CONFIRMATION"));

        MemoryCandidateIngestionService.IngestResult result = service.ingest(
                7L, "c-1", null, List.of(proposal("用户目标是考研")), "我要考研", "加油");

        assertThat(result.needsConfirmation()).isEqualTo(1);
        verify(candidateService).recordExtraction(any(), any(), isNull(), any(), any(), any(), any());
        assertThat(result.items().get(0).deduplicated()).isFalse();
        assertThat(result.items().get(0).candidateId()).isEqualTo(42L);
    }

    @Test
    void nonNullSourceMessageSkipsActiveCandidateDedupe() {
        when(qualityGate.evaluate(any(), any())).thenReturn(gateResult("NEEDS_CONFIRMATION"));
        when(candidateService.recordExtraction(any(), any(), any(), any(), any(), any(), any()))
                .thenReturn(entity(42L, "NEEDS_CONFIRMATION"));

        service.ingest(7L, "c-1", 100L, List.of(proposal("用户目标是考研")), "我要考研", "加油");

        // The legacy recordExtraction dedupe covers this path; the new lookup must not fire.
        verify(candidateMapper, never()).selectList(any());
    }

    @Test
    void dedupeLookupFailureFailsOpen() {
        when(candidateMapper.selectList(any())).thenThrow(new RuntimeException("db down"));
        when(qualityGate.evaluate(any(), any())).thenReturn(gateResult("NEEDS_CONFIRMATION"));
        when(candidateService.recordExtraction(any(), any(), any(), any(), any(), any(), any()))
                .thenReturn(entity(42L, "NEEDS_CONFIRMATION"));

        MemoryCandidateIngestionService.IngestResult result = service.ingest(
                7L, "c-1", null, List.of(proposal("用户目标是考研")), "我要考研", "加油");

        assertThat(result.needsConfirmation()).isEqualTo(1);
        verify(candidateService).recordExtraction(any(), any(), any(), any(), any(), any(), any());
        assertThat(result.items().get(0).deduplicated()).isFalse();
    }

    @Test
    void keylessProposalDedupesOnNormalizedCanonicalText() {
        when(candidateMapper.selectList(any())).thenReturn(List.of(
                candidateRow(51L, "NEEDS_CONFIRMATION", "other_key", "用户   喜欢先给思路")));

        MemoryCandidateIngestionService.IngestResult result = service.ingest(
                7L, "c-1", null,
                List.of(new MemoryCandidateIngestionService.CandidateProposal(
                        "用户 喜欢先给思路", "PREFERENCE", null, 0.9, true, "EXPLICIT_PREFERENCE")),
                "以后都先给思路", "好的");

        verify(candidateService, never()).recordExtraction(any(), any(), any(), any(), any(), any(), any());
        MemoryCandidateIngestionService.ItemResult item = result.items().get(0);
        assertThat(item.candidateId()).isEqualTo(51L);
        assertThat(item.finalStatus()).isEqualTo("NEEDS_CONFIRMATION");
        assertThat(item.deduplicated()).isTrue();
    }

    private AiMemoryCandidateEntity candidateRow(Long id, String status, String memoryKey, String canonicalText) {
        AiMemoryCandidateEntity row = entity(id, status);
        row.memoryKey = memoryKey;
        row.canonicalText = canonicalText;
        return row;
    }

    private MemoryCandidateIngestionService.CandidateProposal proposal(String text) {
        return new MemoryCandidateIngestionService.CandidateProposal(
                text, "PREFERENCE", "guidance_preference", 0.9, true, "EXPLICIT_PREFERENCE");
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
