package com.aioj.next.ai.domain;

import com.aioj.next.ai.domain.memory.AiMemoryCandidateService;
import com.aioj.next.ai.domain.memory.AiMemoryMergeService;
import com.aioj.next.ai.domain.memory.MemoryQualityGate;
import com.aioj.next.ai.persistence.entity.AiLearningWeaknessEntity;
import com.aioj.next.ai.persistence.entity.AiMemoryCandidateEntity;
import com.aioj.next.ai.persistence.entity.AiMemoryJobEntity;
import com.aioj.next.ai.persistence.entity.AiUserMemoryEntity;
import com.aioj.next.ai.persistence.mapper.AiLearningWeaknessMapper;
import com.aioj.next.ai.persistence.mapper.AiMemoryClaimMapper;
import com.aioj.next.ai.persistence.mapper.AiMemoryRecallLogMapper;
import com.aioj.next.ai.persistence.mapper.AiUserMemoryMapper;
import com.aioj.next.contract.ai.AiMemoryUpsertRequest;
import com.aioj.next.contract.ai.StudentPostmortemWeaknessConfirmRequest;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class AiMemoryServiceTest {
    @Mock
    private AiUserMemoryMapper memoryMapper;
    @Mock
    private AiProvider aiProvider;
    @Mock
    private AiRetrievalService retrievalService;
    @Mock
    private MemoryQualityGate memoryQualityGate;
    @Mock
    private AiMemoryCandidateService memoryCandidateService;
    @Mock
    private AiMemoryClaimMapper memoryClaimMapper;
    @Mock
    private AiMemoryRecallLogMapper memoryRecallLogMapper;
    @Mock
    private AiLearningWeaknessMapper learningWeaknessMapper;

    private AiMemoryService service;

    @BeforeEach
    void setUp() {
        service = new AiMemoryService(memoryMapper, aiProvider,
                new AiCapacityService(new com.aioj.next.ai.config.AiProperties()),
                retrievalService, memoryQualityGate,
                memoryCandidateService, memoryClaimMapper, memoryRecallLogMapper, learningWeaknessMapper,
                new com.aioj.next.ai.config.AiProperties());
    }

    @Test
    void confirmedStudentPostmortemWeaknessQueuesMemoryMergeAndWritesWeaknessJson() {
        doAnswer(invocation -> {
            AiLearningWeaknessEntity weakness = invocation.getArgument(0);
            weakness.id = 902L;
            return 1;
        }).when(learningWeaknessMapper).insert(any(AiLearningWeaknessEntity.class));
        when(learningWeaknessMapper.selectOne(any())).thenReturn(null);
        AiMemoryCandidateEntity mergeCandidate = new AiMemoryCandidateEntity();
        mergeCandidate.id = 903L;
        AiMemoryJobEntity mergeJob = new AiMemoryJobEntity();
        mergeJob.setId(904L);
        when(memoryCandidateService.enqueueConfirmedMemory(any(), any()))
                .thenReturn(new AiMemoryMergeService.MergeEnqueueResult(mergeCandidate, null, mergeJob));

        String longKnowledgeNode = "二分答案边界和单调性".repeat(20);
        String longSymptom = "在最大化最小值问题中容易混淆 check 函数和二分边界。".repeat(30);
        var response = service.confirmStudentPostmortemWeakness(new StudentPostmortemWeaknessConfirmRequest(
                7L,
                301L,
                401L,
                501L,
                601L,
                701L,
                longKnowledgeNode,
                longSymptom,
                List.of("二分", "贪心", "二分"),
                List.of("A 题多次提交后才通过", "check(d) 边界说明不完整"),
                0.87
        ));

        assertThat(response.memoryId()).isNull();
        assertThat(response.weaknessId()).isEqualTo(902L);
        assertThat(response.mergeCandidateId()).isEqualTo(903L);
        assertThat(response.mergeJobId()).isEqualTo(904L);
        assertThat(response.mergeStatus()).isEqualTo(AiMemoryMergeService.STATUS_MERGE_QUEUED);
        ArgumentCaptor<AiLearningWeaknessEntity> captor = ArgumentCaptor.forClass(AiLearningWeaknessEntity.class);
        verify(learningWeaknessMapper).insert(captor.capture());
        AiLearningWeaknessEntity weakness = captor.getValue();
        assertThat(weakness.knowledgeNode).hasSizeLessThanOrEqualTo(128);
        assertThat(weakness.symptom).hasSizeLessThanOrEqualTo(500);
        assertThat(weakness.tags).isEqualTo("[\"二分\",\"贪心\"]");
        assertThat(weakness.evidenceJson).startsWith("[\"A 题多次提交后才通过\"");
        ArgumentCaptor<AiMemoryCandidateService.ConfirmedMemoryCandidateRequest> mergeCaptor =
                ArgumentCaptor.forClass(AiMemoryCandidateService.ConfirmedMemoryCandidateRequest.class);
        verify(memoryCandidateService).enqueueConfirmedMemory(any(), mergeCaptor.capture());
        assertThat(mergeCaptor.getValue().category()).isEqualTo("weakness");
        assertThat(mergeCaptor.getValue().scopeType()).isEqualTo("STUDENT_POSTMORTEM_WEAKNESS");
        verify(memoryMapper, never()).insert(any(AiUserMemoryEntity.class));
        verify(memoryCandidateService, never()).syncManualClaim(any(), any());
    }

    @Test
    void updatePreservesResolvedAndSupersededStatuses() {
        AiUserMemoryEntity memory = memory(901L, "ACTIVE");
        when(memoryMapper.selectById(901L)).thenReturn(memory);

        service.update(7L, 901L, new AiMemoryUpsertRequest(
                "weakness",
                "旧弱点",
                "weakness",
                "我以前不会二分答案。",
                null,
                "RESOLVED"
        ));

        ArgumentCaptor<AiUserMemoryEntity> captor = ArgumentCaptor.forClass(AiUserMemoryEntity.class);
        verify(memoryMapper).updateById(captor.capture());
        assertThat(captor.getValue().getStatus()).isEqualTo("RESOLVED");
    }

    @Test
    void filterRecallableRetrievalHitsDropsResolvedMemoryChunks() {
        AiRetrievalService.AiRetrievalHit resolvedHit = hit("901");
        AiRetrievalService.AiRetrievalHit activeHit = hit("902");
        when(memoryMapper.selectById(901L)).thenReturn(memory(901L, "RESOLVED"));
        when(memoryMapper.selectById(902L)).thenReturn(memory(902L, "ACTIVE"));

        List<AiRetrievalService.AiRetrievalHit> filtered = service.filterRecallableRetrievalHits(
                7L,
                List.of(resolvedHit, activeHit, AiRetrievalService.AiRetrievalHit.legacy("ordinary"))
        );

        assertThat(filtered).extracting(AiRetrievalService.AiRetrievalHit::ownerId)
                .containsExactly("902", "");
    }

    @Test
    void candidateGateDoesNotAutomaticallyUpsertActiveMemory() {
        AiCompletion.MemorySignal signal = signal("guidance_preference", "以后讲题先给提示。", "EXPLICIT_USER_PREFERENCE", 0.88);
        when(aiProvider.extractMemories("请记住以后先给提示。", "好的。")).thenReturn(List.of(signal));
        when(memoryQualityGate.evaluate(any(), any())).thenReturn(gate("CANDIDATE", true, false, 0.69, List.of(), List.of()));
        when(memoryMapper.selectOne(any())).thenReturn(null);

        int activated = service.extractAndSave(7L, "c-p2", 300L, "请记住以后先给提示。", "好的。");

        assertThat(activated).isZero();
        verify(memoryCandidateService).recordExtraction(any(), any(), any(), any(), any(), any(), any());
        verify(memoryMapper, never()).insert(any(AiUserMemoryEntity.class));
        verify(memoryMapper, never()).updateById(any(AiUserMemoryEntity.class));
        verify(retrievalService, never()).indexChunk(any(), any(), any(), any());
        verify(memoryCandidateService, never()).syncClaimFromMemory(any(), any(), any(), any());
    }

    @Test
    void conflictingActiveMemoryBecomesReviewCandidate() {
        AiCompletion.MemorySignal signal = signal("guidance_preference", "以后讲题直接给完整答案。", "EXPLICIT_USER_PREFERENCE", 0.96);
        AiUserMemoryEntity existing = memory(901L, "ACTIVE");
        existing.setMemoryType("guidance_preference");
        existing.setContent("以后讲题先给提示，不要直接给完整答案。");
        when(aiProvider.extractMemories("以后讲题直接给完整答案。", "好的。")).thenReturn(List.of(signal));
        when(memoryQualityGate.evaluate(any(), any())).thenReturn(gate("ACTIVE", true, false, 0.93, List.of(), List.of()));
        when(memoryMapper.selectOne(any())).thenReturn(existing);

        service.extractAndSave(7L, "c-p2", 301L, "以后讲题直接给完整答案。", "好的。");

        ArgumentCaptor<MemoryQualityGate.GateResult> gateCaptor = ArgumentCaptor.forClass(MemoryQualityGate.GateResult.class);
        verify(memoryCandidateService).recordExtraction(any(), any(), any(), any(), any(), gateCaptor.capture(), any());
        assertThat(gateCaptor.getValue().status()).isEqualTo("NEEDS_CONFIRMATION");
        assertThat(gateCaptor.getValue().ambiguityFlags()).contains("conflict_with_active_memory");
        verify(memoryMapper, never()).insert(any(AiUserMemoryEntity.class));
        verify(retrievalService, never()).indexChunk(any(), any(), any(), any());
    }

    private static AiUserMemoryEntity memory(Long id, String status) {
        AiUserMemoryEntity memory = new AiUserMemoryEntity();
        memory.setId(id);
        memory.setUserId(7L);
        memory.setCategory("weakness");
        memory.setTitle("二分答案弱点");
        memory.setMemoryType("weakness");
        memory.setContent("我以前不会二分答案，容易弄错 check 单调性。");
        memory.setStatus(status);
        memory.setCreatedAt(java.time.LocalDateTime.now());
        memory.setUpdatedAt(java.time.LocalDateTime.now());
        return memory;
    }

    private static AiCompletion.MemorySignal signal(String type, String content, String evidenceType, double confidence) {
        return new AiCompletion.MemorySignal(type, content, confidence, "test", evidenceType);
    }

    private static MemoryQualityGate.GateResult gate(
            String status,
            boolean accepted,
            boolean needsConfirmation,
            double score,
            List<String> qualityFlags,
            List<String> ambiguityFlags
    ) {
        return new MemoryQualityGate.GateResult(
                accepted,
                needsConfirmation,
                "PREFERENCE",
                "guidance_preference",
                "GLOBAL",
                null,
                score,
                qualityFlags,
                ambiguityFlags,
                accepted ? "" : "rejected",
                status
        );
    }

    private static AiRetrievalService.AiRetrievalHit hit(String ownerId) {
        return new AiRetrievalService.AiRetrievalHit(
                "memory",
                ownerId,
                "长期记忆：weakness - 我以前不会二分答案。",
                1.0,
                List.of("test"),
                null,
                null,
                null,
                null,
                null,
                null,
                null,
                AiRetrievalService.SENSITIVITY_USER_PRIVATE_SAFE,
                Map.of()
        );
    }
}
