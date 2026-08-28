package com.aioj.next.ai.domain.memory;

import com.aioj.next.ai.domain.AiMemoryService;
import com.aioj.next.ai.domain.AiRetrievalService;
import com.aioj.next.ai.domain.AiCompletion;
import com.aioj.next.ai.persistence.entity.AiMemoryCandidateEntity;
import com.aioj.next.ai.persistence.entity.AiMemoryClaimEntity;
import com.aioj.next.ai.persistence.entity.AiMemoryEvidenceEntity;
import com.aioj.next.ai.persistence.entity.AiMemoryVersionEntity;
import com.aioj.next.ai.persistence.entity.AiUserMemoryEntity;
import com.aioj.next.ai.persistence.mapper.AiMemoryCandidateMapper;
import com.aioj.next.ai.persistence.mapper.AiMemoryClaimMapper;
import com.aioj.next.ai.persistence.mapper.AiMemoryEvidenceMapper;
import com.aioj.next.ai.persistence.mapper.AiMemoryVersionMapper;
import com.aioj.next.ai.persistence.mapper.AiUserMemoryMapper;
import com.aioj.next.common.error.DomainException;
import com.baomidou.mybatisplus.core.conditions.query.QueryWrapper;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class AiMemoryCandidateServiceTest {
    @Mock
    private AiMemoryCandidateMapper candidateMapper;
    @Mock
    private AiMemoryEvidenceMapper evidenceMapper;
    @Mock
    private AiMemoryClaimMapper claimMapper;
    @Mock
    private AiMemoryVersionMapper versionMapper;
    @Mock
    private AiUserMemoryMapper memoryMapper;
    @Mock
    private AiRetrievalService retrievalService;
    @Mock
    private AiMemoryMergeService mergeService;

    private AiMemoryCandidateService service;

    @BeforeEach
    void setUp() {
        service = new AiMemoryCandidateService(
                candidateMapper,
                evidenceMapper,
                claimMapper,
                versionMapper,
                memoryMapper,
                retrievalService,
                mergeService,
                new ObjectMapper().findAndRegisterModules()
        );
    }

    @Test
    void acceptPlannerResolutionCandidateQueuesMerge() {
        AiMemoryCandidateEntity candidate = resolutionCandidate(500L, 901L, 902L);
        AiUserMemoryEntity memory = memory(901L, 7L);
        when(candidateMapper.selectById(500L)).thenReturn(candidate);
        when(mergeService.enqueueCandidateMerge(eq(7L), eq(500L), any(), eq(901L), eq(902L), eq("RESOLVE"), any()))
                .thenReturn(new AiMemoryMergeService.MergeEnqueueResult(candidate, memory, null));
        when(mergeService.pendingResponse(candidate, memory)).thenReturn(new com.aioj.next.contract.ai.AiMemoryResponse(
                901L, "weakness", "二分答案弱点", "weakness", "queued", memory.getConfidence(), memory.getSource(),
                AiMemoryMergeService.STATUS_MERGE_QUEUED, null, null, null
        ));

        var response = service.accept(7L, 500L, null);

        assertThat(response.id()).isEqualTo(901L);
        assertThat(response.status()).isEqualTo(AiMemoryMergeService.STATUS_MERGE_QUEUED);
        verify(mergeService).enqueueCandidateMerge(eq(7L), eq(500L), any(), eq(901L), eq(902L), eq("RESOLVE"), any());
    }

    @Test
    void acceptPlannerResolutionCandidateRejectsCrossUserTargetMemory() {
        AiMemoryCandidateEntity candidate = resolutionCandidate(500L, 901L, 902L);
        when(candidateMapper.selectById(500L)).thenReturn(candidate);
        when(mergeService.enqueueCandidateMerge(eq(7L), eq(500L), any(), eq(901L), eq(902L), eq("RESOLVE"), any()))
                .thenThrow(new DomainException(com.aioj.next.common.error.ErrorCode.FORBIDDEN, "Target AI memory belongs to another user"));

        assertThatThrownBy(() -> service.accept(7L, 500L, null))
                .isInstanceOf(DomainException.class)
                .hasMessageContaining("belongs to another user");
    }

    @Test
    void acceptMergeQueuedCandidateIsIdempotentAndDoesNotEnqueueAgain() {
        AiMemoryCandidateEntity candidate = extractionCandidate(501L);
        candidate.status = AiMemoryMergeService.STATUS_MERGE_QUEUED;
        when(candidateMapper.selectById(501L)).thenReturn(candidate);
        when(mergeService.pendingResponse(candidate, null)).thenReturn(new com.aioj.next.contract.ai.AiMemoryResponse(
                501L, "preference", "偏好", "guidance_preference", "queued", candidate.writeScore, "USER_CONFIRMED",
                AiMemoryMergeService.STATUS_MERGE_QUEUED, null, null, null
        ));

        var response = service.accept(7L, 501L, null);

        assertThat(response.status()).isEqualTo(AiMemoryMergeService.STATUS_MERGE_QUEUED);
        verify(mergeService, never()).enqueueCandidateMerge(any(), any(), any(), any(), any(), any(), any());
    }

    @Test
    void acceptProcessedCandidateIsRejectedAndDoesNotRequeue() {
        AiMemoryCandidateEntity candidate = extractionCandidate(502L);
        candidate.status = AiMemoryMergeService.STATUS_MERGED;
        when(candidateMapper.selectById(502L)).thenReturn(candidate);

        assertThatThrownBy(() -> service.accept(7L, 502L, null))
                .isInstanceOf(DomainException.class)
                .hasMessageContaining("already been processed");
        verify(mergeService, never()).enqueueCandidateMerge(any(), any(), any(), any(), any(), any(), any());
    }

    @Test
    void rejectNonReviewableCandidateFails() {
        AiMemoryCandidateEntity queued = extractionCandidate(503L);
        queued.status = AiMemoryMergeService.STATUS_MERGE_QUEUED;
        when(candidateMapper.selectById(503L)).thenReturn(queued);

        assertThatThrownBy(() -> service.reject(7L, 503L, "no"))
                .isInstanceOf(DomainException.class)
                .hasMessageContaining("already being merged");
        verify(candidateMapper, never()).updateById(any(AiMemoryCandidateEntity.class));
    }

    @Test
    void recordExtractionReturnsExistingCandidateForSameSourceMessageAndSignal() {
        AiMemoryCandidateEntity existing = extractionCandidate(700L);
        when(candidateMapper.selectOne(any())).thenReturn(existing);

        AiMemoryCandidateEntity result = service.recordExtraction(
                7L,
                "c-async",
                200L,
                new AiCompletion.MemorySignal("preference", "先给提示", 0.9, "explicit", "EXPLICIT"),
                new MemoryQualityGate.MemoryCandidate(
                        "preference",
                        "preference",
                        "先给提示",
                        "{}",
                        "GLOBAL",
                        null,
                        "EXPLICIT",
                        0.9,
                        true,
                        false,
                        false,
                        false,
                        false
                ),
                new MemoryQualityGate.GateResult(
                        true,
                        false,
                        "preference",
                        "preference",
                        "GLOBAL",
                        null,
                        0.9,
                        List.of(),
                        List.of(),
                        null,
                        "ACTIVE"
                ),
                "用户明确希望先给提示"
        );

        assertThat(result).isSameAs(existing);
        verify(candidateMapper, never()).insert(any(AiMemoryCandidateEntity.class));
        verify(evidenceMapper, never()).insert(any(AiMemoryEvidenceEntity.class));
    }

    @Test
    void recordExtractionSanitizesHardRejectedCandidateAndEvidence() {
        service.recordExtraction(
                7L,
                "c-sensitive",
                201L,
                new AiCompletion.MemorySignal("preference", "我的 token 是 sk-secret-value", 0.9, "token=sk-secret-value", "EXPLICIT_REMEMBER"),
                new MemoryQualityGate.MemoryCandidate(
                        "preference",
                        "api_key",
                        "我的 token 是 sk-secret-value",
                        "{\"raw\":\"sk-secret-value\"}",
                        "GLOBAL",
                        null,
                        "EXPLICIT_REMEMBER",
                        0.9,
                        true,
                        false,
                        false,
                        false,
                        false
                ),
                new MemoryQualityGate.GateResult(
                        false,
                        false,
                        "PREFERENCE",
                        "api_key",
                        "GLOBAL",
                        null,
                        0,
                        List.of("privacy_sensitive"),
                        List.of(),
                        "privacy_sensitive",
                        "REJECTED"
                ),
                "用户说 token=sk-secret-value"
        );

        ArgumentCaptor<AiMemoryCandidateEntity> candidateCaptor = ArgumentCaptor.forClass(AiMemoryCandidateEntity.class);
        verify(candidateMapper).insert(candidateCaptor.capture());
        assertThat(candidateCaptor.getValue().canonicalText).isEqualTo("[memory candidate rejected: privacy_sensitive]");
        assertThat(candidateCaptor.getValue().valueJson).isEqualTo("{}");
        assertThat(candidateCaptor.getValue().canonicalText).doesNotContain("sk-secret-value", "token=");
        ArgumentCaptor<AiMemoryEvidenceEntity> evidenceCaptor = ArgumentCaptor.forClass(AiMemoryEvidenceEntity.class);
        verify(evidenceMapper).insert(evidenceCaptor.capture());
        assertThat(evidenceCaptor.getValue().evidenceText).isEqualTo("[memory candidate rejected: privacy_sensitive]");
        assertThat(evidenceCaptor.getValue().reason).isEqualTo("privacy_sensitive");
        assertThat(evidenceCaptor.getValue().evidenceText).doesNotContain("sk-secret-value", "token=");
    }

    @Test
    void enqueueConfirmedMemoryPopulatesRequiredColumnsBeforeFirstInsert() {
        service.enqueueConfirmedMemory(7L, new AiMemoryCandidateService.ConfirmedMemoryCandidateRequest(
                "weakness",
                "backpack_dp",
                "背包状态定义需要先明确",
                "{\"kind\":\"weakness\"}",
                "STUDENT_POSTMORTEM_WEAKNESS",
                "42",
                "USER_CONFIRMED",
                "提交轨迹显示状态转移不稳定",
                "student accepted weakness candidate",
                0.91
        ));

        ArgumentCaptor<AiMemoryCandidateEntity> candidateCaptor = ArgumentCaptor.forClass(AiMemoryCandidateEntity.class);
        verify(candidateMapper).insert(candidateCaptor.capture());
        AiMemoryCandidateEntity inserted = candidateCaptor.getValue();
        assertThat(inserted.canonicalText).isEqualTo("背包状态定义需要先明确");
        assertThat(inserted.valueJson).isEqualTo("{\"kind\":\"weakness\"}");
        assertThat(inserted.status).isEqualTo("NEEDS_CONFIRMATION");
        assertThat(inserted.createdAt).isNotNull();
        assertThat(inserted.updatedAt).isNotNull();
        verify(candidateMapper, never()).updateById(any(AiMemoryCandidateEntity.class));
    }

    @Test
    void listDefaultsToPendingReviewCandidatesOnly() {
        when(candidateMapper.selectList(any())).thenReturn(List.of());

        service.list(7L, null);

        ArgumentCaptor<QueryWrapper<AiMemoryCandidateEntity>> queryCaptor = ArgumentCaptor.forClass(QueryWrapper.class);
        verify(candidateMapper).selectList(queryCaptor.capture());
        assertThat(queryCaptor.getValue().getSqlSegment()).contains("status IN");
        assertThat(queryCaptor.getValue().getSqlSegment()).doesNotContain("status <>");
    }

    @Test
    void listDefaultsIncludeAwaitingClarificationCandidates() {
        AiMemoryCandidateEntity candidate = extractionCandidate(702L);
        candidate.status = "AWAITING_CLARIFICATION";
        when(candidateMapper.selectList(any())).thenReturn(List.of(candidate));

        var responses = service.list(7L, null);

        assertThat(responses).hasSize(1);
        assertThat(responses.get(0).status()).isEqualTo("AWAITING_CLARIFICATION");
    }

    @Test
    void nextClarificationCandidateSkipsAlreadyAskedCandidates() {
        AiMemoryCandidateEntity asked = extractionCandidate(703L);
        asked.status = "NEEDS_CONFIRMATION";
        asked.valueJson = """
                {"clarification":{"requestId":"memory_candidate_703","askedAt":"2026-06-25T10:00:00"}}
                """;
        AiMemoryCandidateEntity fresh = extractionCandidate(704L);
        fresh.status = "NEEDS_CONFIRMATION";
        when(candidateMapper.selectList(any())).thenReturn(List.of(asked, fresh));

        var result = service.nextClarificationCandidate(7L);

        assertThat(result).containsSame(fresh);
    }

    @Test
    void markAwaitingClarificationStoresMetadataAndStatus() {
        AiMemoryCandidateEntity candidate = extractionCandidate(705L);
        candidate.status = "NEEDS_CONFIRMATION";
        when(candidateMapper.selectById(705L)).thenReturn(candidate);

        service.markAwaitingClarification(7L, 705L, "memory_candidate_705", "c-chat", 900L);

        ArgumentCaptor<AiMemoryCandidateEntity> candidateCaptor = ArgumentCaptor.forClass(AiMemoryCandidateEntity.class);
        verify(candidateMapper).updateById(candidateCaptor.capture());
        assertThat(candidateCaptor.getValue().status).isEqualTo("AWAITING_CLARIFICATION");
        assertThat(candidateCaptor.getValue().valueJson)
                .contains("memory_candidate_705", "c-chat", "900")
                .doesNotContain("codeText", "stdout", "stderr", "token");
    }

    @Test
    void returnToNeedsConfirmationKeepsClarificationMetadataButAllowsManualReview() {
        AiMemoryCandidateEntity candidate = extractionCandidate(706L);
        candidate.status = "AWAITING_CLARIFICATION";
        candidate.valueJson = """
                {"clarification":{"requestId":"memory_candidate_706","askedAt":"2026-06-25T10:00:00"}}
                """;
        when(candidateMapper.selectById(706L)).thenReturn(candidate);

        service.returnToNeedsConfirmation(7L, 706L, "SKIPPED", "memory_clarification_skipped");

        ArgumentCaptor<AiMemoryCandidateEntity> candidateCaptor = ArgumentCaptor.forClass(AiMemoryCandidateEntity.class);
        verify(candidateMapper).updateById(candidateCaptor.capture());
        assertThat(candidateCaptor.getValue().status).isEqualTo("NEEDS_CONFIRMATION");
        assertThat(candidateCaptor.getValue().valueJson).contains("memory_candidate_706", "SKIPPED", "answeredAt");
        assertThat(candidateCaptor.getValue().rejectedReason).isEqualTo("memory_clarification_skipped");
    }

    @Test
    void responseKeepsQualityAndAmbiguityReasons() {
        AiMemoryCandidateEntity candidate = extractionCandidate(701L);
        candidate.status = "CANDIDATE";
        candidate.writeScore = BigDecimal.valueOf(0.62);
        candidate.qualityFlags = "[\"low_confidence\"]";
        candidate.ambiguityFlags = "[\"conflict_with_active_memory\"]";
        candidate.rejectedReason = "needs_review";
        when(candidateMapper.selectList(any())).thenReturn(List.of(candidate));

        var responses = service.list(7L, null);

        assertThat(responses).hasSize(1);
        assertThat(responses.get(0).writeScore()).isEqualByComparingTo("0.62");
        assertThat(responses.get(0).qualityFlags()).containsExactly("low_confidence");
        assertThat(responses.get(0).ambiguityFlags()).containsExactly("conflict_with_active_memory");
        assertThat(responses.get(0).rejectedReason()).isEqualTo("needs_review");
    }

    @Test
    void markClaimStatusManualDisableLowersConfidenceByFactor() {
        AiUserMemoryEntity memory = memory(901L, 7L);
        AiMemoryClaimEntity claim = claim(902L, 901L, 7L);
        claim.confidence = BigDecimal.valueOf(0.8);
        when(claimMapper.selectOne(any(QueryWrapper.class))).thenReturn(claim);

        service.markClaimStatus(memory, "DISABLED", "manual_disable");

        // V3 P2-7: user distrust lowers claim confidence ×0.85 (merge WEAKEN semantics).
        ArgumentCaptor<AiMemoryClaimEntity> captor = ArgumentCaptor.forClass(AiMemoryClaimEntity.class);
        verify(claimMapper).updateById(captor.capture());
        assertThat(captor.getValue().status).isEqualTo("DISABLED");
        assertThat(captor.getValue().confidence).isEqualByComparingTo("0.6800");
        verify(versionMapper).insert(any(AiMemoryVersionEntity.class));
    }

    @Test
    void markClaimStatusDeletedKeepsConfidenceUntouched() {
        AiUserMemoryEntity memory = memory(901L, 7L);
        AiMemoryClaimEntity claim = claim(902L, 901L, 7L);
        claim.confidence = BigDecimal.valueOf(0.8);
        when(claimMapper.selectOne(any(QueryWrapper.class))).thenReturn(claim);

        service.markClaimStatus(memory, "DELETED", "manual_delete");

        ArgumentCaptor<AiMemoryClaimEntity> captor = ArgumentCaptor.forClass(AiMemoryClaimEntity.class);
        verify(claimMapper).updateById(captor.capture());
        assertThat(captor.getValue().status).isEqualTo("DELETED");
        assertThat(captor.getValue().confidence).isEqualByComparingTo("0.8");
    }

    @Test
    void syncManualClaimRevivesUserDisabledClaimWithSmallConfidenceBoost() {
        AiUserMemoryEntity memory = memory(901L, 7L);
        AiMemoryClaimEntity claim = claim(902L, 901L, 7L);
        claim.status = "DISABLED";
        claim.confidence = BigDecimal.valueOf(0.5);
        when(claimMapper.selectOne(any(QueryWrapper.class))).thenReturn(claim);

        service.syncManualClaim(memory, "manual_enable");

        // V3 P2-7: manual enable is an explicit user re-acceptance — the distrusted claim
        // reactivates and confidence rises by +0.1 from its old value (not the memory value).
        ArgumentCaptor<AiMemoryClaimEntity> captor = ArgumentCaptor.forClass(AiMemoryClaimEntity.class);
        verify(claimMapper).updateById(captor.capture());
        assertThat(captor.getValue().status).isEqualTo("ACTIVE");
        assertThat(captor.getValue().confidence).isEqualByComparingTo("0.6000");
        verify(versionMapper).insert(any(AiMemoryVersionEntity.class));
        verify(evidenceMapper).insert(any(AiMemoryEvidenceEntity.class));
    }

    @Test
    void syncManualClaimKeepsActiveClaimMaxConfidenceSemantics() {
        AiUserMemoryEntity memory = memory(901L, 7L);
        AiMemoryClaimEntity claim = claim(902L, 901L, 7L);
        claim.confidence = BigDecimal.valueOf(0.95);
        when(claimMapper.selectOne(any(QueryWrapper.class))).thenReturn(claim);

        service.syncManualClaim(memory, "manual_update");

        // Not a DISABLED revival: legacy max-confidence semantics are untouched (no +0.1 boost).
        ArgumentCaptor<AiMemoryClaimEntity> captor = ArgumentCaptor.forClass(AiMemoryClaimEntity.class);
        verify(claimMapper).updateById(captor.capture());
        assertThat(captor.getValue().status).isEqualTo("ACTIVE");
        assertThat(captor.getValue().confidence).isEqualByComparingTo("0.95");
    }

    private static AiMemoryCandidateEntity resolutionCandidate(Long id, Long memoryId, Long claimId) {
        AiMemoryCandidateEntity candidate = new AiMemoryCandidateEntity();
        candidate.id = id;
        candidate.userId = 7L;
        candidate.category = "WEAKNESS";
        candidate.memoryKey = "binary_search_answer";
        candidate.canonicalText = "建议将旧弱点标记为已解决。";
        candidate.valueJson = """
                {"candidateKind":"WEAKNESS_RESOLUTION","plannerAction":"RESOLVE","targetMemoryId":"%s","targetClaimId":"%s"}
                """.formatted(memoryId, claimId).trim();
        candidate.scopeType = "GLOBAL";
        candidate.evidenceType = "RESOLVE";
        candidate.extractionConfidence = BigDecimal.valueOf(0.92);
        candidate.writeScore = BigDecimal.valueOf(0.92);
        candidate.isLongTerm = Boolean.TRUE;
        candidate.isProblemSpecific = Boolean.FALSE;
        candidate.isHypothetical = Boolean.FALSE;
        candidate.isQuoted = Boolean.FALSE;
        candidate.needsConfirmation = Boolean.TRUE;
        candidate.qualityFlags = "[]";
        candidate.ambiguityFlags = "[]";
        candidate.sourceConversationId = "c-p6";
        candidate.sourceMessageId = 200L;
        candidate.status = "NEEDS_CONFIRMATION";
        candidate.createdAt = LocalDateTime.now();
        candidate.updatedAt = LocalDateTime.now();
        return candidate;
    }

    private static AiMemoryCandidateEntity extractionCandidate(Long id) {
        AiMemoryCandidateEntity candidate = new AiMemoryCandidateEntity();
        candidate.id = id;
        candidate.userId = 7L;
        candidate.category = "preference";
        candidate.memoryKey = "preference";
        candidate.canonicalText = "先给提示";
        candidate.valueJson = "{}";
        candidate.scopeType = "GLOBAL";
        candidate.evidenceType = "EXPLICIT";
        candidate.extractionConfidence = BigDecimal.valueOf(0.9);
        candidate.writeScore = BigDecimal.valueOf(0.9);
        candidate.isLongTerm = Boolean.TRUE;
        candidate.isProblemSpecific = Boolean.FALSE;
        candidate.isHypothetical = Boolean.FALSE;
        candidate.isQuoted = Boolean.FALSE;
        candidate.needsConfirmation = Boolean.FALSE;
        candidate.qualityFlags = "[]";
        candidate.ambiguityFlags = "[]";
        candidate.sourceConversationId = "c-async";
        candidate.sourceMessageId = 200L;
        candidate.status = "ACTIVE";
        candidate.createdAt = LocalDateTime.now();
        candidate.updatedAt = LocalDateTime.now();
        return candidate;
    }

    private static AiUserMemoryEntity memory(Long id, Long userId) {
        AiUserMemoryEntity memory = new AiUserMemoryEntity();
        memory.setId(id);
        memory.setUserId(userId);
        memory.setCategory("weakness");
        memory.setTitle("二分答案弱点");
        memory.setMemoryType("weakness");
        memory.setContent("我以前不会二分答案。");
        memory.setConfidence(BigDecimal.valueOf(0.9));
        memory.setSource(AiMemoryService.SOURCE_USER_CONFIRMED);
        memory.setStatus(AiMemoryService.STATUS_ACTIVE);
        memory.setCreatedAt(LocalDateTime.now());
        memory.setUpdatedAt(LocalDateTime.now());
        return memory;
    }

    private static AiMemoryClaimEntity claim(Long id, Long legacyMemoryId, Long userId) {
        AiMemoryClaimEntity claim = new AiMemoryClaimEntity();
        claim.id = id;
        claim.userId = userId;
        claim.legacyMemoryId = legacyMemoryId;
        claim.category = "WEAKNESS";
        claim.memoryKey = "binary_search_answer";
        claim.canonicalText = "我以前不会二分答案。";
        claim.status = AiMemoryService.STATUS_ACTIVE;
        claim.version = 1;
        claim.createdAt = LocalDateTime.now();
        claim.updatedAt = LocalDateTime.now();
        return claim;
    }
}
