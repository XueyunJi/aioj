package com.aioj.next.ai.domain.memory;

import com.aioj.next.ai.domain.AiMemoryService;
import com.aioj.next.ai.domain.OperationAuditWriter;
import com.aioj.next.ai.persistence.entity.AiLearningProfileEntity;
import com.aioj.next.ai.persistence.entity.AiMemoryCandidateEntity;
import com.aioj.next.ai.persistence.entity.AiMemoryEvidenceEntity;
import com.aioj.next.ai.persistence.entity.AiUserMemoryEntity;
import com.aioj.next.ai.persistence.mapper.AiLearningProfileMapper;
import com.aioj.next.ai.persistence.mapper.AiMemoryCandidateMapper;
import com.aioj.next.ai.persistence.mapper.AiMemoryClaimMapper;
import com.aioj.next.ai.persistence.mapper.AiMemoryEvidenceMapper;
import com.aioj.next.ai.persistence.mapper.AiUserMemoryMapper;
import com.aioj.next.contract.ai.AiMemoryReviewActionRequest;
import com.baomidou.mybatisplus.core.conditions.query.QueryWrapper;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
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
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class AiMemoryReviewServiceTest {
    @Mock
    private AiMemoryCandidateMapper candidateMapper;
    @Mock
    private AiMemoryEvidenceMapper evidenceMapper;
    @Mock
    private AiUserMemoryMapper memoryMapper;
    @Mock
    private AiMemoryClaimMapper claimMapper;
    @Mock
    private AiLearningProfileMapper profileMapper;
    @Mock
    private AiMemoryCandidateService candidateService;
    @Mock
    private AiMemoryService memoryService;
    @Mock
    private AiMemoryMergeService mergeService;
    @Mock
    private OperationAuditWriter auditWriter;

    private AiMemoryReviewService service;

    @BeforeEach
    void setUp() {
        service = new AiMemoryReviewService(
                candidateMapper,
                evidenceMapper,
                memoryMapper,
                claimMapper,
                profileMapper,
                candidateService,
                memoryService,
                mergeService,
                new AiMemoryEventPayloadSanitizer(),
                auditWriter,
                new ObjectMapper().findAndRegisterModules()
        );
    }

    @Test
    void listDefaultsToPendingCandidatesAndSanitizesPreview() {
        Page<AiMemoryCandidateEntity> page = new Page<>(1, 20);
        page.setRecords(List.of(candidate()));
        page.setTotal(1);
        when(candidateMapper.selectPage(any(), any())).thenReturn(page);

        var result = service.list(1, 20, null, null, null, null, null);

        assertThat(result.records()).hasSize(1);
        assertThat(result.records().get(0).canonicalText())
                .contains("[code line omitted]")
                .doesNotContain("#include", "token=sk-secret-value");
        ArgumentCaptor<QueryWrapper<AiMemoryCandidateEntity>> queryCaptor = ArgumentCaptor.forClass(QueryWrapper.class);
        verify(candidateMapper).selectPage(any(), queryCaptor.capture());
        assertThat(queryCaptor.getValue().getSqlSegment()).contains("status IN");
    }

    @Test
    void detailReturnsSafeEvidenceAndRelatedState() {
        AiMemoryCandidateEntity candidate = candidate();
        when(candidateMapper.selectById(100L)).thenReturn(candidate);
        when(evidenceMapper.selectList(any())).thenReturn(List.of(evidence()));
        when(memoryMapper.selectList(any())).thenReturn(List.of(memory(300L, 7L)));
        when(profileMapper.selectList(any())).thenReturn(List.of(profile()));

        var detail = service.detail(100L);

        assertThat(detail.candidate().userId()).isEqualTo(7L);
        assertThat(detail.evidence()).hasSize(1);
        assertThat(detail.evidence().get(0).evidenceText())
                .doesNotContain("stdout:", "WA hidden", "sk-secret-value")
                .contains("[raw output omitted]");
        assertThat(detail.relatedMemories()).hasSize(1);
        assertThat(detail.relatedProfiles()).hasSize(1);
    }

    @Test
    void detailForUserRejectsOtherUsersCandidate() {
        when(candidateMapper.selectById(100L)).thenReturn(candidate());

        assertThatThrownBy(() -> service.detailForUser(8L, 100L))
                .hasMessageContaining("not found");
    }

    @Test
    void approveUsesCandidateOwnerAndWritesSafeAuditSummary() {
        AiMemoryCandidateEntity candidate = candidate();
        when(candidateMapper.selectById(100L)).thenReturn(candidate);
        when(evidenceMapper.selectList(any())).thenReturn(List.of());
        when(memoryMapper.selectList(any())).thenReturn(List.of());
        when(profileMapper.selectList(any())).thenReturn(List.of());

        service.action(99L, 100L, new AiMemoryReviewActionRequest("APPROVE", null, null, null, null, "looks safe", null, null));

        verify(mergeService).enqueueCandidateMerge(eq(7L), eq(100L), any(), eq(null), eq(null), eq("ADMIN_APPROVE"), eq("looks safe"));
        ArgumentCaptor<Map<String, Object>> summaryCaptor = ArgumentCaptor.forClass(Map.class);
        verify(auditWriter).record(
                eq("AI_MEMORY_REVIEW_APPROVE"),
                eq("AI_MEMORY_CANDIDATE"),
                eq(100L),
                eq("SUCCESS"),
                summaryCaptor.capture(),
                eq(99L),
                eq(null),
                eq(null),
                eq(7L)
        );
        assertThat(summaryCaptor.getValue().toString())
                .doesNotContain("#include", "stdout:", "sk-secret-value", "prompt");
    }

    @Test
    void mergeRejectsCrossUserTargetMemory() {
        AiMemoryCandidateEntity candidate = candidate();
        when(candidateMapper.selectById(100L)).thenReturn(candidate);
        when(memoryMapper.selectById(300L)).thenReturn(memory(300L, 8L));

        assertThatThrownBy(() -> service.action(99L, 100L,
                new AiMemoryReviewActionRequest("MERGE", null, null, null, "merged", "merge", 300L, null)))
                .hasMessageContaining("belongs to another user");
    }

    @Test
    void requestUserConfirmationStoresAdminReviewMetadata() {
        AiMemoryCandidateEntity candidate = candidate();
        when(candidateMapper.selectById(100L)).thenReturn(candidate);
        when(evidenceMapper.selectList(any())).thenReturn(List.of());
        when(memoryMapper.selectList(any())).thenReturn(List.of());
        when(profileMapper.selectList(any())).thenReturn(List.of());

        service.action(99L, 100L,
                new AiMemoryReviewActionRequest("REQUEST_USER_CONFIRMATION", null, null, null, null, "ask student", null, null));

        ArgumentCaptor<AiMemoryCandidateEntity> candidateCaptor = ArgumentCaptor.forClass(AiMemoryCandidateEntity.class);
        verify(candidateMapper).updateById(candidateCaptor.capture());
        assertThat(candidateCaptor.getValue().status).isEqualTo("NEEDS_CONFIRMATION");
        assertThat(candidateCaptor.getValue().valueJson).contains("adminReview", "ask student");
    }

    private static AiMemoryCandidateEntity candidate() {
        AiMemoryCandidateEntity candidate = new AiMemoryCandidateEntity();
        candidate.id = 100L;
        candidate.userId = 7L;
        candidate.category = "WEAKNESS";
        candidate.memoryKey = "binary_search_answer";
        candidate.canonicalText = """
                我可能不会二分答案。
                #include <bits/stdc++.h>
                token=sk-secret-value
                """;
        candidate.valueJson = "{}";
        candidate.scopeType = "GLOBAL";
        candidate.evidenceType = "MODEL_EXTRACTED";
        candidate.extractionConfidence = BigDecimal.valueOf(0.62);
        candidate.writeScore = BigDecimal.valueOf(0.62);
        candidate.needsConfirmation = Boolean.TRUE;
        candidate.qualityFlags = "[\"high_impact_weakness\"]";
        candidate.ambiguityFlags = "[\"low_confidence\"]";
        candidate.status = "NEEDS_CONFIRMATION";
        candidate.sourceConversationId = "c-review";
        candidate.sourceMessageId = 200L;
        candidate.createdAt = LocalDateTime.now();
        candidate.updatedAt = LocalDateTime.now();
        return candidate;
    }

    private static AiMemoryEvidenceEntity evidence() {
        AiMemoryEvidenceEntity evidence = new AiMemoryEvidenceEntity();
        evidence.id = 200L;
        evidence.userId = 7L;
        evidence.candidateId = 100L;
        evidence.evidenceType = "MODEL_EXTRACTED";
        evidence.evidenceText = """
                stdout:
                WA hidden
                password=secret-value
                """;
        evidence.confidence = BigDecimal.valueOf(0.62);
        evidence.reason = "prompt should not leak";
        evidence.createdAt = LocalDateTime.now();
        return evidence;
    }

    private static AiUserMemoryEntity memory(Long id, Long userId) {
        AiUserMemoryEntity memory = new AiUserMemoryEntity();
        memory.setId(id);
        memory.setUserId(userId);
        memory.setCategory("weakness");
        memory.setTitle("二分答案");
        memory.setMemoryType("binary_search_answer");
        memory.setContent("二分答案仍需复习");
        memory.setConfidence(BigDecimal.valueOf(0.8));
        memory.setSource(AiMemoryService.SOURCE_USER_CONFIRMED);
        memory.setStatus(AiMemoryService.STATUS_ACTIVE);
        memory.setUpdatedAt(LocalDateTime.now());
        return memory;
    }

    private static AiLearningProfileEntity profile() {
        AiLearningProfileEntity profile = new AiLearningProfileEntity();
        profile.id = 400L;
        profile.userId = 7L;
        profile.category = "weakness";
        profile.profileKey = "binary_search_answer";
        profile.label = "二分答案";
        profile.state = "CANDIDATE";
        profile.confidence = BigDecimal.valueOf(0.62);
        profile.evidenceCount = 1;
        profile.updatedAt = LocalDateTime.now();
        return profile;
    }
}
