package com.aioj.next.ai.domain;

import com.aioj.next.ai.domain.memory.AiMemoryCandidateService;
import com.aioj.next.ai.persistence.entity.AiMemoryClaimEntity;
import com.aioj.next.ai.persistence.entity.AiMemoryEvidenceEntity;
import com.aioj.next.ai.persistence.entity.AiMemoryVersionEntity;
import com.aioj.next.ai.persistence.entity.AiUserMemoryEntity;
import com.aioj.next.ai.persistence.mapper.AiMemoryClaimMapper;
import com.aioj.next.ai.persistence.mapper.AiMemoryEvidenceMapper;
import com.aioj.next.ai.persistence.mapper.AiMemoryVersionMapper;
import com.aioj.next.ai.persistence.mapper.AiUserMemoryMapper;
import com.aioj.next.contract.ai.AiChatRequest;
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
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class AiMemoryUpdatePlannerTest {
    private static final Long USER_ID = 7L;
    private static final String CONVERSATION_ID = "c-p6";
    private static final Long MESSAGE_ID = 200L;

    @Mock
    private AiUserMemoryMapper memoryMapper;
    @Mock
    private AiMemoryClaimMapper claimMapper;
    @Mock
    private AiMemoryEvidenceMapper evidenceMapper;
    @Mock
    private AiMemoryVersionMapper versionMapper;
    @Mock
    private AiLearningProfileService learningProfileService;
    @Mock
    private AiMemoryCandidateService candidateService;

    private AiMemoryUpdatePlanner planner;

    @BeforeEach
    void setUp() {
        planner = new AiMemoryUpdatePlanner(
                memoryMapper,
                claimMapper,
                evidenceMapper,
                versionMapper,
                learningProfileService,
                candidateService
        );
    }

    @Test
    void acceptedSubmissionWithBinarySearchExplanationResolvesComputedWeakness() {
        AiUserMemoryEntity memory = memory(10L, AiMemoryService.SOURCE_AI_EXTRACTED,
                "我总是不会二分答案，容易写错 check(d) 单调性和边界。");
        AiMemoryClaimEntity claim = claim(20L, memory.getId(), AiMemoryService.SOURCE_AI_EXTRACTED,
                "binary_search_answer", "不会二分答案 check 边界");
        when(learningProfileService.recallableWeaknessProfiles(USER_ID)).thenReturn(List.of(
                new AiLearningProfileService.LearningProfileWeakness(30L, "wrong_answer_binary_search", "ACTIVE", "二分答案弱点")
        ));
        when(memoryMapper.selectList(any())).thenReturn(List.of(memory));
        when(claimMapper.selectList(any())).thenReturn(List.of(claim));

        AiMemoryUpdatePlanner.PlanResult result = planner.afterTurn(
                USER_ID,
                CONVERSATION_ID,
                MESSAGE_ID,
                request("这次提交已经通过，我能解释 check(d) 的单调性和边界。"),
                completion("复盘：二分答案能通过是因为 check 函数单调，边界收缩正确。"),
                signal("ACCEPTED", "accepted_binary_search", true)
        );

        assertThat(result.primaryAction()).isEqualTo("RESOLVE");
        verify(learningProfileService).markProfileState(USER_ID, 30L, AiMemoryService.STATUS_RESOLVED);
        verify(candidateService).applyPlannerStatus(
                eq(USER_ID),
                eq(10L),
                eq(20L),
                eq("RESOLVE"),
                any(),
                eq("planner_resolve"),
                eq(CONVERSATION_ID),
                eq(MESSAGE_ID)
        );
        verify(candidateService, never()).createPlannerResolutionCandidate(eq(USER_ID), any());
    }

    @Test
    void acceptedSubmissionWithoutExplanationOnlyContradictsWeakness() {
        AiMemoryClaimEntity claim = claim(20L, null, AiMemoryService.SOURCE_AI_EXTRACTED,
                "binary_search_answer", "不会二分答案");
        when(learningProfileService.recallableWeaknessProfiles(USER_ID)).thenReturn(List.of());
        when(memoryMapper.selectList(any())).thenReturn(List.of());
        when(claimMapper.selectList(any())).thenReturn(List.of(claim));

        AiMemoryUpdatePlanner.PlanResult result = planner.afterTurn(
                USER_ID,
                CONVERSATION_ID,
                MESSAGE_ID,
                request("看看这个提交。"),
                completion("这个提交结果不错。"),
                signal("ACCEPTED", "accepted_binary_search")
        );

        assertThat(result.primaryAction()).isEqualTo("CONTRADICT");
        ArgumentCaptor<AiMemoryEvidenceEntity> evidence = ArgumentCaptor.forClass(AiMemoryEvidenceEntity.class);
        verify(evidenceMapper).insert(evidence.capture());
        assertThat(evidence.getValue().evidenceType).isEqualTo("CONTRADICT");
        verify(candidateService, never()).applyPlannerStatus(any(), any(), any(), any(), any(), any(), any(), any());
    }

    @Test
    void acceptedSubmissionWithBinaryKeywordsButNoMasterySignalOnlyContradictsWeakness() {
        AiMemoryClaimEntity claim = claim(20L, null, AiMemoryService.SOURCE_AI_EXTRACTED,
                "binary_search_answer", "不会二分答案");
        when(learningProfileService.recallableWeaknessProfiles(USER_ID)).thenReturn(List.of());
        when(memoryMapper.selectList(any())).thenReturn(List.of());
        when(claimMapper.selectList(any())).thenReturn(List.of(claim));

        AiMemoryUpdatePlanner.PlanResult result = planner.afterTurn(
                USER_ID,
                CONVERSATION_ID,
                MESSAGE_ID,
                request("这次题目涉及二分答案和 check 单调性。"),
                completion("可以继续观察 check 函数和边界。"),
                signal("ACCEPTED", "accepted_binary_search")
        );

        assertThat(result.primaryAction()).isEqualTo("CONTRADICT");
        verify(candidateService, never()).applyPlannerStatus(any(), any(), any(), any(), any(), any(), any(), any());
        verify(candidateService, never()).createPlannerResolutionCandidate(eq(USER_ID), any());
    }

    @Test
    void failedSpecificEvidenceSupersedesGenericComputedWeakness() {
        AiUserMemoryEntity generic = memory(10L, AiMemoryService.SOURCE_AI_EXTRACTED,
                "我经常在调试答案错误提交时找不到方向。");
        generic.setTitle("泛化调试弱点");
        AiMemoryClaimEntity claim = claim(20L, generic.getId(), AiMemoryService.SOURCE_AI_EXTRACTED,
                "wrong_answer_debugging", "调试答案错误提交很薄弱");
        when(learningProfileService.recallableWeaknessProfiles(USER_ID)).thenReturn(List.of(
                new AiLearningProfileService.LearningProfileWeakness(30L, "wrong_answer_debugging", "ACTIVE", "泛化调试弱点")
        ));
        when(memoryMapper.selectList(any())).thenReturn(List.of(generic));
        when(claimMapper.selectList(any())).thenReturn(List.of(claim));

        AiMemoryUpdatePlanner.PlanResult result = planner.afterTurn(
                USER_ID,
                CONVERSATION_ID,
                MESSAGE_ID,
                request("我这次二分答案 WA 了。"),
                completion("先检查 check 函数的单调性。"),
                signal("WRONG_ANSWER", "wrong_answer_binary_search")
        );

        assertThat(result.primaryAction()).isEqualTo("SUPERSEDE");
        verify(learningProfileService).markProfileState(USER_ID, 30L, AiMemoryService.STATUS_SUPERSEDED);
        verify(candidateService).applyPlannerStatus(
                eq(USER_ID),
                eq(10L),
                eq(20L),
                eq("SUPERSEDE"),
                any(),
                eq("planner_supersede"),
                eq(CONVERSATION_ID),
                eq(MESSAGE_ID)
        );
    }

    @Test
    void userConfirmedWeaknessCreatesResolutionCandidateInsteadOfAutoResolving() {
        AiUserMemoryEntity memory = memory(10L, AiMemoryService.SOURCE_USER_CONFIRMED,
                "我总是不会二分答案，遇到最大化最小值会混乱。");
        AiMemoryClaimEntity claim = claim(20L, memory.getId(), AiMemoryService.SOURCE_USER_CONFIRMED,
                "binary_search_answer", "不会二分答案");
        when(learningProfileService.recallableWeaknessProfiles(USER_ID)).thenReturn(List.of());
        when(memoryMapper.selectList(any())).thenReturn(List.of(memory));
        when(claimMapper.selectList(any())).thenReturn(List.of(claim));

        planner.afterTurn(
                USER_ID,
                CONVERSATION_ID,
                MESSAGE_ID,
                request("这次提交已经通过，我能解释二分答案 check 的单调性。"),
                completion("通过原因是 check(d) 单调，并且边界更新正确。"),
                signal("ACCEPTED", "accepted_binary_search", true)
        );

        ArgumentCaptor<AiMemoryCandidateService.ResolutionCandidateRequest> captor =
                ArgumentCaptor.forClass(AiMemoryCandidateService.ResolutionCandidateRequest.class);
        verify(candidateService).createPlannerResolutionCandidate(eq(USER_ID), captor.capture());
        assertThat(captor.getValue().plannerAction()).isEqualTo("RESOLVE");
        assertThat(captor.getValue().targetMemoryId()).isEqualTo(10L);
        assertThat(captor.getValue().targetClaimId()).isEqualTo(20L);
        verify(candidateService, never()).applyPlannerStatus(any(), any(), any(), any(), any(), any(), any(), any());
    }

    private static AiLearningProfileService.SubmissionAnalysisSignal signal(String status, String profileKey) {
        return signal(status, profileKey, false);
    }

    private static AiLearningProfileService.SubmissionAnalysisSignal signal(String status, String profileKey, boolean masteryEvidence) {
        return new AiLearningProfileService.SubmissionAnalysisSignal(
                1001L,
                3001L,
                status,
                "cpp",
                "hash-1",
                profileKey,
                List.of(profileKey, "binary-search"),
                masteryEvidence,
                true,
                "submission=1001\nstatus=" + status + "\ncodeHash=hash-1\naiSummary=safe summary",
                5001L
        );
    }

    private static AiChatRequest request(String message) {
        return new AiChatRequest("c-p6", null, message, "assist", null, null, null, "client-p6", null);
    }

    private static AiCompletion completion(String content) {
        return new AiCompletion(content, "mock", "mock-model", 0, 0);
    }

    private static AiUserMemoryEntity memory(Long id, String source, String content) {
        AiUserMemoryEntity memory = new AiUserMemoryEntity();
        memory.setId(id);
        memory.setUserId(USER_ID);
        memory.setCategory("weakness");
        memory.setTitle("二分答案弱点");
        memory.setMemoryType("weakness");
        memory.setContent(content);
        memory.setConfidence(BigDecimal.valueOf(0.9));
        memory.setSource(source);
        memory.setStatus(AiMemoryService.STATUS_ACTIVE);
        memory.setCreatedAt(LocalDateTime.now());
        memory.setUpdatedAt(LocalDateTime.now());
        return memory;
    }

    private static AiMemoryClaimEntity claim(Long id, Long legacyMemoryId, String sourceMode, String key, String text) {
        AiMemoryClaimEntity claim = new AiMemoryClaimEntity();
        claim.id = id;
        claim.userId = USER_ID;
        claim.legacyMemoryId = legacyMemoryId;
        claim.category = "WEAKNESS";
        claim.memoryKey = key;
        claim.canonicalText = text;
        claim.sourceMode = sourceMode;
        claim.status = AiMemoryService.STATUS_ACTIVE;
        claim.supportCount = 1;
        claim.contradictionCount = 0;
        claim.version = 1;
        claim.confidence = BigDecimal.valueOf(0.9);
        claim.createdAt = LocalDateTime.now();
        claim.updatedAt = LocalDateTime.now();
        return claim;
    }
}
