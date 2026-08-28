package com.aioj.next.ai.domain.memory;

import com.aioj.next.ai.config.AiProperties;
import com.aioj.next.ai.domain.AiCapacityService;
import com.aioj.next.ai.domain.AiModelCompletionClient;
import com.aioj.next.ai.domain.AiModelConfigResolver;
import com.aioj.next.ai.domain.AiModelEffectiveConfig;
import com.aioj.next.ai.domain.AiModelScope;
import com.aioj.next.ai.domain.AiQuotaService;
import com.aioj.next.ai.domain.AiRetrievalService;
import com.aioj.next.ai.persistence.entity.AiDomainEventEntity;
import com.aioj.next.ai.persistence.entity.AiMemoryCandidateEntity;
import com.aioj.next.ai.persistence.entity.AiMemoryClaimEntity;
import com.aioj.next.ai.persistence.entity.AiMemoryEvidenceEntity;
import com.aioj.next.ai.persistence.entity.AiMemoryJobEntity;
import com.aioj.next.ai.persistence.entity.AiMemoryVersionEntity;
import com.aioj.next.ai.persistence.entity.AiUserMemoryEntity;
import com.aioj.next.ai.persistence.mapper.AiMemoryCandidateMapper;
import com.aioj.next.ai.persistence.mapper.AiMemoryClaimMapper;
import com.aioj.next.ai.persistence.mapper.AiMemoryEvidenceMapper;
import com.aioj.next.ai.persistence.mapper.AiMemoryVersionMapper;
import com.aioj.next.ai.persistence.mapper.AiUserMemoryMapper;
import com.aioj.next.contract.ai.AiMemoryCandidateActionRequest;
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
import java.util.Map;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.ArgumentMatchers.anyDouble;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.atLeastOnce;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class AiMemoryMergeServiceTest {
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
    private AiMemoryEventService eventService;
    @Mock
    private AiModelConfigResolver configResolver;
    @Mock
    private AiModelCompletionClient completionClient;
    @Mock
    private AiQuotaService quotaService;

    private AiMemoryMergeService service;
    private ObjectMapper objectMapper;

    @BeforeEach
    void setUp() {
        objectMapper = new ObjectMapper().findAndRegisterModules();
        service = new AiMemoryMergeService(
                candidateMapper,
                evidenceMapper,
                claimMapper,
                versionMapper,
                memoryMapper,
                retrievalService,
                eventService,
                new AiMemoryEventPayloadSanitizer(),
                objectMapper,
                configResolver,
                completionClient,
                quotaService,
                new AiCapacityService(new AiProperties())
        );
    }

    @Test
    void enqueueCandidateMergeMarksCandidateQueuedAndCreatesIdempotentJob() {
        AiMemoryCandidateEntity candidate = candidate();
        when(candidateMapper.selectById(100L)).thenReturn(candidate);
        AiDomainEventEntity event = new AiDomainEventEntity();
        event.setId(200L);
        AiMemoryJobEntity job = new AiMemoryJobEntity();
        job.setId(300L);
        when(eventService.recordEvent(
                eq(AiMemoryJobTypes.EVENT_AI_MEMORY_MERGE_REQUESTED),
                eq(7L),
                eq("AI_MEMORY_CANDIDATE"),
                eq("100"),
                eq("ai-memory-merge-requested:100"),
                any(),
                eq(AiMemoryEventService.SENSITIVITY_USER_PRIVATE_SAFE),
                any()
        )).thenReturn(new AiMemoryEventService.RecordedEvent(event, List.of(job)));

        var result = service.enqueueCandidateMerge(
                7L,
                100L,
                new AiMemoryCandidateActionRequest("preference", null, "guidance_preference", "先给提示", "user accepted"),
                null,
                null,
                "ACCEPT",
                "user accepted"
        );

        assertThat(result.job().getId()).isEqualTo(300L);
        ArgumentCaptor<AiMemoryCandidateEntity> candidateCaptor = ArgumentCaptor.forClass(AiMemoryCandidateEntity.class);
        verify(candidateMapper).updateById(candidateCaptor.capture());
        assertThat(candidateCaptor.getValue().status).isEqualTo(AiMemoryMergeService.STATUS_MERGE_QUEUED);
        assertThat(candidateCaptor.getValue().valueJson).contains("memoryMerge", "user accepted");
    }

    @Test
    void enqueueCandidateMergeReturnsExistingQueuedCandidateWithoutNewJob() {
        AiMemoryCandidateEntity candidate = candidate();
        candidate.status = AiMemoryMergeService.STATUS_MERGE_QUEUED;
        when(candidateMapper.selectById(100L)).thenReturn(candidate);

        var result = service.enqueueCandidateMerge(
                7L,
                100L,
                new AiMemoryCandidateActionRequest("preference", null, "guidance_preference", "先给提示", "user accepted"),
                null,
                null,
                "ACCEPT",
                "user accepted"
        );

        assertThat(result.candidate()).isSameAs(candidate);
        assertThat(result.job()).isNull();
        verify(candidateMapper, never()).updateById(any(AiMemoryCandidateEntity.class));
        verify(eventService, never()).recordEvent(any(), any(), any(), any(), any(), any(), any(), any());
    }

    @Test
    void enqueueCandidateMergeRejectsProcessedCandidateWithoutRequeue() {
        AiMemoryCandidateEntity candidate = candidate();
        candidate.status = AiMemoryMergeService.STATUS_MERGED;
        when(candidateMapper.selectById(100L)).thenReturn(candidate);

        assertThatThrownBy(() -> service.enqueueCandidateMerge(
                7L,
                100L,
                new AiMemoryCandidateActionRequest("preference", null, "guidance_preference", "先给提示", "user accepted"),
                null,
                null,
                "ACCEPT",
                "user accepted"
        ))
                .hasMessageContaining("already been processed");
        verify(candidateMapper, never()).updateById(any(AiMemoryCandidateEntity.class));
        verify(eventService, never()).recordEvent(any(), any(), any(), any(), any(), any(), any(), any());
    }

    @Test
    void enqueueCandidateMergeAdmitsFreshGateActiveCandidateForAutoExtraction() {
        // V3 P2: a candidate landing ACTIVE straight from recordExtraction (no
        // memoryMerge marker) is a legitimate AUTO_MEMORY_EXTRACTION input — this is
        // the path the curator auto-activation flow takes.
        AiMemoryCandidateEntity candidate = candidate();
        candidate.status = "ACTIVE";
        when(candidateMapper.selectById(100L)).thenReturn(candidate);
        AiDomainEventEntity event = new AiDomainEventEntity();
        event.setId(200L);
        AiMemoryJobEntity job = new AiMemoryJobEntity();
        job.setId(300L);
        when(eventService.recordEvent(
                eq(AiMemoryJobTypes.EVENT_AI_MEMORY_MERGE_REQUESTED),
                eq(7L),
                eq("AI_MEMORY_CANDIDATE"),
                eq("100"),
                eq("ai-memory-merge-requested:100"),
                any(),
                eq(AiMemoryEventService.SENSITIVITY_USER_PRIVATE_SAFE),
                any()
        )).thenReturn(new AiMemoryEventService.RecordedEvent(event, List.of(job)));

        var result = service.enqueueCandidateMerge(
                7L,
                100L,
                new AiMemoryCandidateActionRequest("PREFERENCE", null, "guidance_preference", "用户偏好先给提示。", "auto_memory_extraction"),
                null,
                null,
                "AUTO_MEMORY_EXTRACTION",
                "auto_memory_extraction"
        );

        assertThat(result.job()).isNotNull();
        ArgumentCaptor<AiMemoryCandidateEntity> candidateCaptor = ArgumentCaptor.forClass(AiMemoryCandidateEntity.class);
        verify(candidateMapper).updateById(candidateCaptor.capture());
        assertThat(candidateCaptor.getValue().status).isEqualTo(AiMemoryMergeService.STATUS_MERGE_QUEUED);
    }

    @Test
    void enqueueCandidateMergeRejectsPostMergeActiveCandidateWithMarker() {
        AiMemoryCandidateEntity candidate = candidate();
        candidate.status = "ACTIVE";
        candidate.valueJson = "{\"memoryMerge\":{\"action\":\"AUTO_MEMORY_EXTRACTION\"}}";
        when(candidateMapper.selectById(100L)).thenReturn(candidate);

        assertThatThrownBy(() -> service.enqueueCandidateMerge(
                7L,
                100L,
                new AiMemoryCandidateActionRequest("PREFERENCE", null, "guidance_preference", "用户偏好先给提示。", "auto_memory_extraction"),
                null,
                null,
                "AUTO_MEMORY_EXTRACTION",
                "auto_memory_extraction"
        ))
                .hasMessageContaining("already been processed");
        verify(candidateMapper, never()).updateById(any(AiMemoryCandidateEntity.class));
    }

    @Test
    void enqueueCandidateMergeRejectsFreshActiveCandidateForUserAccept() {
        // The relaxation is AUTO-only: a user ACCEPT on an ACTIVE row still throws.
        AiMemoryCandidateEntity candidate = candidate();
        candidate.status = "ACTIVE";
        when(candidateMapper.selectById(100L)).thenReturn(candidate);

        assertThatThrownBy(() -> service.enqueueCandidateMerge(
                7L,
                100L,
                new AiMemoryCandidateActionRequest("preference", null, "guidance_preference", "先给提示", "user accepted"),
                null,
                null,
                "ACCEPT",
                "user accepted"
        ))
                .hasMessageContaining("already been processed");
        verify(candidateMapper, never()).updateById(any(AiMemoryCandidateEntity.class));
    }

    @Test
    void handleJobMergesSimilarCandidateIntoExistingMemoryAndDeduplicatesEvidence() {
        AiMemoryCandidateEntity candidate = candidate();
        candidate.status = AiMemoryMergeService.STATUS_MERGE_QUEUED;
        AiUserMemoryEntity target = memory();
        when(candidateMapper.selectById(100L)).thenReturn(candidate);
        when(memoryMapper.selectById(300L)).thenReturn(target);
        when(memoryMapper.selectList(any(QueryWrapper.class))).thenReturn(List.of(target));
        when(configResolver.effectiveConfig(AiModelScope.MEMORY_EXTRACTION)).thenReturn(config());
        when(completionClient.complete(any(AiModelEffectiveConfig.class), anyList(), anyDouble(), anyInt(), anyBoolean()))
                .thenReturn(new AiModelCompletionClient.CompletionResult("""
                        {
                          "decision":"MERGE",
                          "targetMemoryId":300,
                          "mergedContent":"用户偏好先获得提示，再逐步讲解。",
                          "confidence":0.91,
                          "supportDelta":2,
                          "contradictionDelta":0,
                          "evidenceItems":["用户确认希望先给提示"],
                          "candidateStatus":"MERGED",
                          "reviewReason":"same preference"
                        }
                        """, "deepseek", "deepseek-v4-pro", 12, 8));
        doAnswer(invocation -> {
            AiMemoryClaimEntity claim = invocation.getArgument(0);
            claim.id = 400L;
            return 1;
        }).when(claimMapper).insert(any(AiMemoryClaimEntity.class));

        AiMemoryJobEntity job = new AiMemoryJobEntity();
        job.setPayloadJson("""
                {"userId":7,"candidateId":100,"targetMemoryId":300,"action":"ADMIN_APPROVE"}
                """);
        service.handleJob(job);

        ArgumentCaptor<AiUserMemoryEntity> memoryCaptor = ArgumentCaptor.forClass(AiUserMemoryEntity.class);
        verify(memoryMapper).updateById(memoryCaptor.capture());
        assertThat(memoryCaptor.getValue().getId()).isEqualTo(300L);
        assertThat(memoryCaptor.getValue().getContent()).isEqualTo("用户偏好先获得提示，再逐步讲解。");
        ArgumentCaptor<AiMemoryCandidateEntity> candidateCaptor = ArgumentCaptor.forClass(AiMemoryCandidateEntity.class);
        verify(candidateMapper).updateById(candidateCaptor.capture());
        assertThat(candidateCaptor.getValue().status).isEqualTo(AiMemoryMergeService.STATUS_MERGED);
        ArgumentCaptor<AiMemoryEvidenceEntity> evidenceCaptor = ArgumentCaptor.forClass(AiMemoryEvidenceEntity.class);
        verify(evidenceMapper).insert(evidenceCaptor.capture());
        assertThat(evidenceCaptor.getValue().evidenceType).isEqualTo("MODEL_MERGE_SUPPORT");
        verify(versionMapper).insert(any(AiMemoryVersionEntity.class));
        verify(retrievalService).deleteOwner(7L, "memory", "300");
        verify(retrievalService).indexChunk(eq(7L), eq("memory"), eq("300"), any());
    }

    @Test
    void handleJobSupersedesEquivalentActiveDuplicatesAfterModelMerge() {
        AiMemoryCandidateEntity candidate = candidate();
        candidate.status = AiMemoryMergeService.STATUS_MERGE_QUEUED;
        candidate.canonicalText = "User prefers complete solution code instead of step-by-step hints.";
        AiUserMemoryEntity target = memory();
        target.setMemoryType("guidance_preference");
        target.setContent("User prefers complete solution code instead of step-by-step hints.");
        AiUserMemoryEntity duplicate = memory();
        duplicate.setId(301L);
        duplicate.setMemoryType("answer_style_preference");
        duplicate.setContent("User prefers complete solution code instead of step-by-step hints.");
        when(candidateMapper.selectById(100L)).thenReturn(candidate);
        when(memoryMapper.selectById(300L)).thenReturn(target);
        when(memoryMapper.selectList(any(QueryWrapper.class))).thenReturn(List.of(target, duplicate));
        when(configResolver.effectiveConfig(AiModelScope.MEMORY_EXTRACTION)).thenReturn(config());
        when(completionClient.complete(any(AiModelEffectiveConfig.class), anyList(), anyDouble(), anyInt(), anyBoolean()))
                .thenReturn(new AiModelCompletionClient.CompletionResult("""
                        {
                          "decision":"MERGE",
                          "targetMemoryId":300,
                          "memoryType":"guidance_preference",
                          "mergedContent":"User prefers complete solution code instead of step-by-step hints.",
                          "confidence":0.89,
                          "supportDelta":1,
                          "contradictionDelta":0,
                          "evidenceItems":["same preference"],
                          "candidateStatus":"MERGED",
                          "reviewReason":"same description object"
                        }
                        """, "deepseek", "deepseek-v4-pro", 12, 8));
        doAnswer(invocation -> {
            AiMemoryClaimEntity claim = invocation.getArgument(0);
            claim.id = 400L;
            return 1;
        }).when(claimMapper).insert(any(AiMemoryClaimEntity.class));

        AiMemoryJobEntity job = new AiMemoryJobEntity();
        job.setPayloadJson("""
                {"userId":7,"candidateId":100,"targetMemoryId":300,"action":"ACCEPT"}
                """);
        service.handleJob(job);

        ArgumentCaptor<AiUserMemoryEntity> memoryCaptor = ArgumentCaptor.forClass(AiUserMemoryEntity.class);
        verify(memoryMapper, org.mockito.Mockito.times(2)).updateById(memoryCaptor.capture());
        assertThat(memoryCaptor.getAllValues()).anySatisfy(memory -> {
            assertThat(memory.getId()).isEqualTo(300L);
            assertThat(memory.getStatus()).isEqualTo("ACTIVE");
        });
        assertThat(memoryCaptor.getAllValues()).anySatisfy(memory -> {
            assertThat(memory.getId()).isEqualTo(301L);
            assertThat(memory.getStatus()).isEqualTo("SUPERSEDED");
            assertThat(memory.getSource()).isEqualTo("AI_MEMORY_MERGED");
        });
        verify(retrievalService).deleteOwner(7L, "memory", "301");
    }

    @Test
    void handleJobParsesFirstJsonObjectFromFencedModelResponse() {
        AiMemoryCandidateEntity candidate = candidate();
        candidate.status = AiMemoryMergeService.STATUS_MERGE_QUEUED;
        AiUserMemoryEntity target = memory();
        when(candidateMapper.selectById(100L)).thenReturn(candidate);
        when(memoryMapper.selectById(300L)).thenReturn(target);
        when(memoryMapper.selectList(any(QueryWrapper.class))).thenReturn(List.of(target));
        when(configResolver.effectiveConfig(AiModelScope.MEMORY_EXTRACTION)).thenReturn(config());
        when(completionClient.complete(any(AiModelEffectiveConfig.class), anyList(), anyDouble(), anyInt(), anyBoolean()))
                .thenReturn(new AiModelCompletionClient.CompletionResult("""
                        ```json
                        {
                          "decision":"MERGE",
                          "targetMemoryId":300,
                          "mergedContent":"用户偏好先获得提示，再逐步讲解。",
                          "confidence":0.91,
                          "supportDelta":2,
                          "contradictionDelta":0,
                          "evidenceItems":["用户确认希望先给提示"],
                          "candidateStatus":"MERGED",
                          "reviewReason":"same preference"
                        }
                        ```
                        已按要求返回 JSON。
                        {"ignored":"trailing object"}
                        """, "deepseek", "deepseek-v4-pro", 12, 8));
        doAnswer(invocation -> {
            AiMemoryClaimEntity claim = invocation.getArgument(0);
            claim.id = 400L;
            return 1;
        }).when(claimMapper).insert(any(AiMemoryClaimEntity.class));

        AiMemoryJobEntity job = new AiMemoryJobEntity();
        job.setPayloadJson("""
                {"userId":7,"candidateId":100,"targetMemoryId":300,"action":"ADMIN_APPROVE"}
                """);
        service.handleJob(job);

        ArgumentCaptor<AiMemoryCandidateEntity> candidateCaptor = ArgumentCaptor.forClass(AiMemoryCandidateEntity.class);
        verify(candidateMapper).updateById(candidateCaptor.capture());
        assertThat(candidateCaptor.getValue().status).isEqualTo(AiMemoryMergeService.STATUS_MERGED);
        verify(memoryMapper).updateById(any(AiUserMemoryEntity.class));
        verify(evidenceMapper).insert(any(AiMemoryEvidenceEntity.class));
    }

    @Test
    void handleJobSkipsLeadingNonDecisionJsonObjectFromModelResponse() {
        AiMemoryCandidateEntity candidate = candidate();
        candidate.status = AiMemoryMergeService.STATUS_MERGE_QUEUED;
        AiUserMemoryEntity target = memory();
        when(candidateMapper.selectById(100L)).thenReturn(candidate);
        when(memoryMapper.selectById(300L)).thenReturn(target);
        when(memoryMapper.selectList(any(QueryWrapper.class))).thenReturn(List.of(target));
        when(configResolver.effectiveConfig(AiModelScope.MEMORY_EXTRACTION)).thenReturn(config());
        when(completionClient.complete(any(AiModelEffectiveConfig.class), anyList(), anyDouble(), anyInt(), anyBoolean()))
                .thenReturn(new AiModelCompletionClient.CompletionResult("""
                        分析元信息：{"note":"not the final decision"}
                        ```json
                        {
                          "decision":"MERGE",
                          "targetMemoryId":300,
                          "mergedContent":"用户偏好先获得提示，再逐步讲解。",
                          "confidence":0.9,
                          "supportDelta":1,
                          "contradictionDelta":0,
                          "evidenceItems":["用户确认希望先给提示"],
                          "candidateStatus":"MERGED",
                          "reviewReason":"same preference"
                        }
                        ```
                        """, "deepseek", "deepseek-v4-pro", 12, 8));
        doAnswer(invocation -> {
            AiMemoryClaimEntity claim = invocation.getArgument(0);
            claim.id = 400L;
            return 1;
        }).when(claimMapper).insert(any(AiMemoryClaimEntity.class));

        AiMemoryJobEntity job = new AiMemoryJobEntity();
        job.setPayloadJson("""
                {"userId":7,"candidateId":100,"targetMemoryId":300,"action":"ADMIN_APPROVE"}
                """);
        service.handleJob(job);

        verify(memoryMapper).updateById(any(AiUserMemoryEntity.class));
        verify(evidenceMapper).insert(any(AiMemoryEvidenceEntity.class));
    }

    @Test
    void handleJobParsesNestedChineseAliasDecisionFromModelResponse() {
        AiMemoryCandidateEntity candidate = candidate();
        candidate.status = AiMemoryMergeService.STATUS_MERGE_QUEUED;
        AiUserMemoryEntity target = memory();
        when(candidateMapper.selectById(100L)).thenReturn(candidate);
        when(memoryMapper.selectById(300L)).thenReturn(target);
        when(memoryMapper.selectList(any(QueryWrapper.class))).thenReturn(List.of(target));
        when(configResolver.effectiveConfig(AiModelScope.MEMORY_EXTRACTION)).thenReturn(config());
        when(completionClient.complete(any(AiModelEffectiveConfig.class), anyList(), anyDouble(), anyInt(), anyBoolean()))
                .thenReturn(new AiModelCompletionClient.CompletionResult("""
                        [{"结果":{
                          "决定":"合并",
                          "目标记忆ID":"300",
                          "合并内容":"用户偏好先获得提示，再逐步讲解。",
                          "置信度":0.9,
                          "支持增量":1,
                          "冲突增量":0,
                          "证据项":["用户确认希望先给提示"],
                          "候选状态":"MERGED",
                          "审查原因":"same preference"
                        }}]
                        """, "deepseek", "deepseek-v4-pro", 12, 8));
        doAnswer(invocation -> {
            AiMemoryClaimEntity claim = invocation.getArgument(0);
            claim.id = 400L;
            return 1;
        }).when(claimMapper).insert(any(AiMemoryClaimEntity.class));

        AiMemoryJobEntity job = new AiMemoryJobEntity();
        job.setPayloadJson("""
                {"userId":7,"candidateId":100,"targetMemoryId":300,"action":"ADMIN_APPROVE"}
                """);
        service.handleJob(job);

        ArgumentCaptor<AiUserMemoryEntity> memoryCaptor = ArgumentCaptor.forClass(AiUserMemoryEntity.class);
        verify(memoryMapper).updateById(memoryCaptor.capture());
        assertThat(memoryCaptor.getValue().getContent()).isEqualTo("用户偏好先获得提示，再逐步讲解。");
        verify(evidenceMapper).insert(any(AiMemoryEvidenceEntity.class));
    }

    @Test
    void handleJobAppliesSplitActionsAndSupersedesBroadMemory() {
        AiMemoryCandidateEntity candidate = candidate();
        candidate.status = AiMemoryMergeService.STATUS_MERGE_QUEUED;
        candidate.canonicalText = "用户不喜欢香蕉。";
        AiUserMemoryEntity broad = memory();
        broad.setContent("用户喜欢苹果和香蕉。");
        when(candidateMapper.selectById(100L)).thenReturn(candidate);
        when(memoryMapper.selectById(300L)).thenReturn(broad);
        when(memoryMapper.selectList(any(QueryWrapper.class))).thenReturn(List.of(broad));
        when(configResolver.effectiveConfig(AiModelScope.MEMORY_EXTRACTION)).thenReturn(config());
        when(completionClient.complete(any(AiModelEffectiveConfig.class), anyList(), anyDouble(), anyInt(), anyBoolean()))
                .thenReturn(new AiModelCompletionClient.CompletionResult("""
                        {
                          "actions":[
                            {"action":"SUPERSEDE","targetMemoryId":300,"confidence":0.9,"reason":"旧记忆包含相互冲突的多个子概念"},
                            {"action":"SPLIT_CREATE","memoryKey":"apple_preference","category":"PREFERENCE","canonicalText":"用户喜欢吃苹果。","confidence":0.95,"supportDelta":2,"contradictionDelta":0,"evidenceItems":["旧记忆中苹果偏好未被反驳"]},
                            {"action":"SPLIT_CREATE","memoryKey":"banana_preference","category":"PREFERENCE","canonicalText":"用户对香蕉偏好存在反证，暂按较低置信度记录。","confidence":0.42,"supportDelta":0,"contradictionDelta":1,"evidenceItems":["用户表示不喜欢香蕉"]}
                          ],
                          "candidateStatus":"MERGED",
                          "reviewReason":"split conflicting preference"
                        }
                        """, "deepseek", "deepseek-v4-pro", 12, 8));
        AtomicLong memoryIds = new AtomicLong(500L);
        doAnswer(invocation -> {
            AiUserMemoryEntity memory = invocation.getArgument(0);
            memory.setId(memoryIds.getAndIncrement());
            return 1;
        }).when(memoryMapper).insert(any(AiUserMemoryEntity.class));
        doAnswer(invocation -> {
            AiMemoryClaimEntity claim = invocation.getArgument(0);
            claim.id = memoryIds.getAndIncrement();
            return 1;
        }).when(claimMapper).insert(any(AiMemoryClaimEntity.class));

        AiMemoryJobEntity job = new AiMemoryJobEntity();
        job.setPayloadJson("""
                {"userId":7,"candidateId":100,"targetMemoryId":300,"action":"MAINTENANCE_DEDUPE"}
                """);
        service.handleJob(job);

        ArgumentCaptor<AiUserMemoryEntity> insertedMemories = ArgumentCaptor.forClass(AiUserMemoryEntity.class);
        verify(memoryMapper, org.mockito.Mockito.times(2)).insert(insertedMemories.capture());
        assertThat(insertedMemories.getAllValues())
                .extracting(AiUserMemoryEntity::getContent)
                .contains("用户喜欢吃苹果。", "用户对香蕉偏好存在反证，暂按较低置信度记录。");
        ArgumentCaptor<AiUserMemoryEntity> updatedMemories = ArgumentCaptor.forClass(AiUserMemoryEntity.class);
        verify(memoryMapper, atLeastOnce()).updateById(updatedMemories.capture());
        assertThat(updatedMemories.getAllValues()).anySatisfy(memory -> {
            assertThat(memory.getId()).isEqualTo(300L);
            assertThat(memory.getStatus()).isEqualTo("SUPERSEDED");
        });
        ArgumentCaptor<AiMemoryEvidenceEntity> evidenceCaptor = ArgumentCaptor.forClass(AiMemoryEvidenceEntity.class);
        verify(evidenceMapper, atLeastOnce()).insert(evidenceCaptor.capture());
        assertThat(evidenceCaptor.getAllValues())
                .extracting(item -> item.evidenceType)
                .contains("MODEL_MERGE_SPLIT");
    }

    @Test
    void handleJobTreatsTemporaryContradictionAsLowConfidenceWeakening() {
        AiMemoryCandidateEntity candidate = candidate();
        candidate.status = AiMemoryMergeService.STATUS_MERGE_QUEUED;
        candidate.canonicalText = "我现在不想吃香蕉。";
        AiUserMemoryEntity target = memory();
        target.setContent("用户喜欢吃香蕉。");
        target.setConfidence(BigDecimal.valueOf(0.8));
        when(candidateMapper.selectById(100L)).thenReturn(candidate);
        when(memoryMapper.selectById(300L)).thenReturn(target);
        when(memoryMapper.selectList(any(QueryWrapper.class))).thenReturn(List.of(target));
        when(configResolver.effectiveConfig(AiModelScope.MEMORY_EXTRACTION)).thenReturn(config());
        when(completionClient.complete(any(AiModelEffectiveConfig.class), anyList(), anyDouble(), anyInt(), anyBoolean()))
                .thenReturn(new AiModelCompletionClient.CompletionResult("""
                        {
                          "actions":[
                            {"action":"WEAKEN","targetMemoryId":300,"canonicalText":"用户喜欢吃香蕉，但存在一次临时反证，需后续确认。","confidence":0.35,"supportDelta":0,"contradictionDelta":1,"evidenceItems":["用户说现在不想吃香蕉"],"reason":"temporary wording"}
                          ],
                          "candidateStatus":"MERGED",
                          "reviewReason":"temporary contradiction"
                        }
                        """, "deepseek", "deepseek-v4-pro", 12, 8));
        doAnswer(invocation -> {
            AiMemoryClaimEntity claim = invocation.getArgument(0);
            claim.id = 400L;
            return 1;
        }).when(claimMapper).insert(any(AiMemoryClaimEntity.class));

        AiMemoryJobEntity job = new AiMemoryJobEntity();
        job.setPayloadJson("""
                {"userId":7,"candidateId":100,"targetMemoryId":300,"action":"ACCEPT"}
                """);
        service.handleJob(job);

        ArgumentCaptor<AiUserMemoryEntity> memoryCaptor = ArgumentCaptor.forClass(AiUserMemoryEntity.class);
        verify(memoryMapper).updateById(memoryCaptor.capture());
        assertThat(memoryCaptor.getValue().getContent()).contains("临时反证");
        assertThat(memoryCaptor.getValue().getConfidence()).isLessThan(BigDecimal.valueOf(0.8));
        ArgumentCaptor<AiMemoryEvidenceEntity> evidenceCaptor = ArgumentCaptor.forClass(AiMemoryEvidenceEntity.class);
        verify(evidenceMapper).insert(evidenceCaptor.capture());
        assertThat(evidenceCaptor.getValue().evidenceType).isEqualTo("MODEL_MERGE_CONTRADICTION");
    }

    @Test
    void enqueueMaintenanceGroupsSameObjectAcrossMemoryTypes() {
        AiUserMemoryEntity guidance = memory();
        guidance.setContent("用户偏好直接获得完整代码，而非分步讲解或提示。");
        AiUserMemoryEntity answerStyle = memory();
        answerStyle.setId(301L);
        answerStyle.setMemoryType("answer_style_preference");
        answerStyle.setContent("用户偏好直接获得完整代码，而非分步讲解或提示。");
        AiUserMemoryEntity unrelated = memory();
        unrelated.setId(302L);
        unrelated.setTitle("界面偏好");
        unrelated.setMemoryType("theme_preference");
        unrelated.setContent("用户偏好深色界面。");
        when(memoryMapper.selectList(any(QueryWrapper.class))).thenReturn(List.of(guidance, answerStyle, unrelated));
        when(memoryMapper.selectById(300L)).thenReturn(guidance);
        when(candidateMapper.selectOne(any(QueryWrapper.class))).thenReturn(null);
        AtomicReference<AiMemoryCandidateEntity> insertedCandidate = new AtomicReference<>();
        doAnswer(invocation -> {
            AiMemoryCandidateEntity candidate = invocation.getArgument(0);
            candidate.id = 700L;
            insertedCandidate.set(candidate);
            return 1;
        }).when(candidateMapper).insert(any(AiMemoryCandidateEntity.class));
        when(candidateMapper.selectById(700L)).thenAnswer(invocation -> insertedCandidate.get());
        AiDomainEventEntity event = new AiDomainEventEntity();
        event.setId(800L);
        AiMemoryJobEntity job = new AiMemoryJobEntity();
        job.setId(900L);
        when(eventService.recordEvent(any(), any(), any(), any(), any(), any(), any(), any()))
                .thenReturn(new AiMemoryEventService.RecordedEvent(event, List.of(job)));

        var response = service.enqueueMaintenance(
                7L,
                new com.aioj.next.contract.ai.AiMemoryMergeMaintenanceRequest(7L, null, 20)
        );

        assertThat(response.scannedMemories()).isEqualTo(3);
        assertThat(response.relatedGroups()).isEqualTo(1);
        assertThat(response.queuedJobs()).isEqualTo(1);
        assertThat(response.candidateIds()).containsExactly(700L);
        assertThat(insertedCandidate.get().canonicalText)
                .contains("#300", "#301")
                .doesNotContain("#302");
    }

    @Test
    void handleJobKeepsAutoExtractionMergeFromRevivingUserDisabledClaim() {
        AiMemoryCandidateEntity candidate = candidate();
        candidate.status = AiMemoryMergeService.STATUS_MERGE_QUEUED;
        AiUserMemoryEntity target = memory();
        AiMemoryClaimEntity distrusted = distrustedClaim(0.6);
        when(candidateMapper.selectById(100L)).thenReturn(candidate);
        when(memoryMapper.selectById(300L)).thenReturn(target);
        when(memoryMapper.selectList(any(QueryWrapper.class))).thenReturn(List.of(target));
        when(claimMapper.selectOne(any(QueryWrapper.class))).thenReturn(distrusted);
        when(configResolver.effectiveConfig(AiModelScope.MEMORY_EXTRACTION)).thenReturn(config());
        when(completionClient.complete(any(AiModelEffectiveConfig.class), anyList(), anyDouble(), anyInt(), anyBoolean()))
                .thenReturn(new AiModelCompletionClient.CompletionResult("""
                        {
                          "decision":"MERGE",
                          "targetMemoryId":300,
                          "mergedContent":"用户偏好先获得提示，再逐步讲解。",
                          "confidence":0.91,
                          "supportDelta":2,
                          "contradictionDelta":0,
                          "evidenceItems":["用户确认希望先给提示"],
                          "candidateStatus":"MERGED",
                          "reviewReason":"same preference"
                        }
                        """, "deepseek", "deepseek-v4-pro", 12, 8));

        AiMemoryJobEntity job = new AiMemoryJobEntity();
        job.setPayloadJson("""
                {"userId":7,"candidateId":100,"targetMemoryId":300,"action":"AUTO_MEMORY_EXTRACTION"}
                """);
        service.handleJob(job);

        // V3 P2-7 distrust guard: an automatic merge must not revive a user-disabled claim.
        verify(claimMapper, never()).updateById(any(AiMemoryClaimEntity.class));
        verify(claimMapper, never()).insert(any(AiMemoryClaimEntity.class));
        assertThat(distrusted.status).isEqualTo("DISABLED");
        assertThat(distrusted.confidence).isEqualByComparingTo("0.6");
        // The memory-side merge itself still applies; only the claim revival is blocked.
        verify(memoryMapper).updateById(any(AiUserMemoryEntity.class));
        ArgumentCaptor<AiMemoryCandidateEntity> candidateCaptor = ArgumentCaptor.forClass(AiMemoryCandidateEntity.class);
        verify(candidateMapper).updateById(candidateCaptor.capture());
        assertThat(candidateCaptor.getValue().status).isEqualTo(AiMemoryMergeService.STATUS_MERGED);
    }

    @Test
    void handleJobWithoutActionTreatsMergeAsAutoAndKeepsDistrustedClaimInactive() {
        AiMemoryCandidateEntity candidate = candidate();
        candidate.status = AiMemoryMergeService.STATUS_MERGE_QUEUED;
        AiUserMemoryEntity target = memory();
        AiMemoryClaimEntity distrusted = distrustedClaim(0.6);
        when(candidateMapper.selectById(100L)).thenReturn(candidate);
        when(memoryMapper.selectById(300L)).thenReturn(target);
        when(memoryMapper.selectList(any(QueryWrapper.class))).thenReturn(List.of(target));
        when(claimMapper.selectOne(any(QueryWrapper.class))).thenReturn(distrusted);
        when(configResolver.effectiveConfig(AiModelScope.MEMORY_EXTRACTION)).thenReturn(config());
        when(completionClient.complete(any(AiModelEffectiveConfig.class), anyList(), anyDouble(), anyInt(), anyBoolean()))
                .thenReturn(new AiModelCompletionClient.CompletionResult("""
                        {
                          "decision":"MERGE",
                          "targetMemoryId":300,
                          "mergedContent":"用户偏好先获得提示，再逐步讲解。",
                          "confidence":0.91,
                          "supportDelta":2,
                          "contradictionDelta":0,
                          "evidenceItems":["用户确认希望先给提示"],
                          "candidateStatus":"MERGED",
                          "reviewReason":"same preference"
                        }
                        """, "deepseek", "deepseek-v4-pro", 12, 8));

        // Legacy job payload without an "action" field defaults to AUTO (never revives).
        AiMemoryJobEntity job = new AiMemoryJobEntity();
        job.setPayloadJson("""
                {"userId":7,"candidateId":100,"targetMemoryId":300}
                """);
        service.handleJob(job);

        verify(claimMapper, never()).updateById(any(AiMemoryClaimEntity.class));
        verify(claimMapper, never()).insert(any(AiMemoryClaimEntity.class));
        assertThat(distrusted.status).isEqualTo("DISABLED");
    }

    @Test
    void handleJobRevivesDistrustedClaimOnUserAcceptWithSmallConfidenceBoost() {
        AiMemoryCandidateEntity candidate = candidate();
        candidate.status = AiMemoryMergeService.STATUS_MERGE_QUEUED;
        AiUserMemoryEntity target = memory();
        AiMemoryClaimEntity distrusted = distrustedClaim(0.5);
        when(candidateMapper.selectById(100L)).thenReturn(candidate);
        when(memoryMapper.selectById(300L)).thenReturn(target);
        when(memoryMapper.selectList(any(QueryWrapper.class))).thenReturn(List.of(target));
        when(claimMapper.selectOne(any(QueryWrapper.class))).thenReturn(distrusted);
        when(configResolver.effectiveConfig(AiModelScope.MEMORY_EXTRACTION)).thenReturn(config());
        when(completionClient.complete(any(AiModelEffectiveConfig.class), anyList(), anyDouble(), anyInt(), anyBoolean()))
                .thenReturn(new AiModelCompletionClient.CompletionResult("""
                        {
                          "decision":"MERGE",
                          "targetMemoryId":300,
                          "mergedContent":"用户偏好先获得提示，再逐步讲解。",
                          "confidence":0.91,
                          "supportDelta":2,
                          "contradictionDelta":0,
                          "evidenceItems":["用户确认希望先给提示"],
                          "candidateStatus":"MERGED",
                          "reviewReason":"same preference"
                        }
                        """, "deepseek", "deepseek-v4-pro", 12, 8));

        AiMemoryJobEntity job = new AiMemoryJobEntity();
        job.setPayloadJson("""
                {"userId":7,"candidateId":100,"targetMemoryId":300,"action":"ACCEPT"}
                """);
        service.handleJob(job);

        // V3 P2-7: explicit user re-acceptance clears the distrust — claim reactivates and
        // confidence rises by +0.1 from its old value (NOT to the model confidence).
        ArgumentCaptor<AiMemoryClaimEntity> claimCaptor = ArgumentCaptor.forClass(AiMemoryClaimEntity.class);
        verify(claimMapper).updateById(claimCaptor.capture());
        assertThat(claimCaptor.getValue().status).isEqualTo("ACTIVE");
        assertThat(claimCaptor.getValue().confidence).isEqualByComparingTo("0.6000");
        assertThat(claimCaptor.getValue().version).isEqualTo(3);
        verify(versionMapper).insert(any(AiMemoryVersionEntity.class));
    }

    @Test
    void handleJobReviveConfidenceBoostIsCappedAtOne() {
        AiMemoryCandidateEntity candidate = candidate();
        candidate.status = AiMemoryMergeService.STATUS_MERGE_QUEUED;
        AiUserMemoryEntity target = memory();
        AiMemoryClaimEntity distrusted = distrustedClaim(0.95);
        when(candidateMapper.selectById(100L)).thenReturn(candidate);
        when(memoryMapper.selectById(300L)).thenReturn(target);
        when(memoryMapper.selectList(any(QueryWrapper.class))).thenReturn(List.of(target));
        when(claimMapper.selectOne(any(QueryWrapper.class))).thenReturn(distrusted);
        when(configResolver.effectiveConfig(AiModelScope.MEMORY_EXTRACTION)).thenReturn(config());
        when(completionClient.complete(any(AiModelEffectiveConfig.class), anyList(), anyDouble(), anyInt(), anyBoolean()))
                .thenReturn(new AiModelCompletionClient.CompletionResult("""
                        {
                          "decision":"MERGE",
                          "targetMemoryId":300,
                          "mergedContent":"用户偏好先获得提示，再逐步讲解。",
                          "confidence":0.91,
                          "supportDelta":2,
                          "contradictionDelta":0,
                          "evidenceItems":["用户确认希望先给提示"],
                          "candidateStatus":"MERGED",
                          "reviewReason":"same preference"
                        }
                        """, "deepseek", "deepseek-v4-pro", 12, 8));

        AiMemoryJobEntity job = new AiMemoryJobEntity();
        job.setPayloadJson("""
                {"userId":7,"candidateId":100,"targetMemoryId":300,"action":"ACCEPT"}
                """);
        service.handleJob(job);

        ArgumentCaptor<AiMemoryClaimEntity> claimCaptor = ArgumentCaptor.forClass(AiMemoryClaimEntity.class);
        verify(claimMapper).updateById(claimCaptor.capture());
        assertThat(claimCaptor.getValue().status).isEqualTo("ACTIVE");
        assertThat(claimCaptor.getValue().confidence).isEqualByComparingTo("1.0000");
    }

    @Test
    void handleJobAutoExtractionMergeStillSupersedesEquivalentActiveDuplicates() {
        AiMemoryCandidateEntity candidate = candidate();
        candidate.status = AiMemoryMergeService.STATUS_MERGE_QUEUED;
        candidate.canonicalText = "User prefers complete solution code instead of step-by-step hints.";
        AiUserMemoryEntity target = memory();
        target.setMemoryType("guidance_preference");
        target.setContent("User prefers complete solution code instead of step-by-step hints.");
        AiUserMemoryEntity duplicate = memory();
        duplicate.setId(301L);
        duplicate.setMemoryType("answer_style_preference");
        duplicate.setContent("User prefers complete solution code instead of step-by-step hints.");
        when(candidateMapper.selectById(100L)).thenReturn(candidate);
        when(memoryMapper.selectById(300L)).thenReturn(target);
        when(memoryMapper.selectList(any(QueryWrapper.class))).thenReturn(List.of(target, duplicate));
        when(configResolver.effectiveConfig(AiModelScope.MEMORY_EXTRACTION)).thenReturn(config());
        when(completionClient.complete(any(AiModelEffectiveConfig.class), anyList(), anyDouble(), anyInt(), anyBoolean()))
                .thenReturn(new AiModelCompletionClient.CompletionResult("""
                        {
                          "decision":"MERGE",
                          "targetMemoryId":300,
                          "memoryType":"guidance_preference",
                          "mergedContent":"User prefers complete solution code instead of step-by-step hints.",
                          "confidence":0.89,
                          "supportDelta":1,
                          "contradictionDelta":0,
                          "evidenceItems":["same preference"],
                          "candidateStatus":"MERGED",
                          "reviewReason":"same description object"
                        }
                        """, "deepseek", "deepseek-v4-pro", 12, 8));
        doAnswer(invocation -> {
            AiMemoryClaimEntity claim = invocation.getArgument(0);
            claim.id = 400L;
            return 1;
        }).when(claimMapper).insert(any(AiMemoryClaimEntity.class));

        // V3 P2-7 verification: the equivalent-duplicate supersede pass runs for
        // AUTO_MEMORY_EXTRACTION merges exactly like for user ACCEPT merges.
        AiMemoryJobEntity job = new AiMemoryJobEntity();
        job.setPayloadJson("""
                {"userId":7,"candidateId":100,"targetMemoryId":300,"action":"AUTO_MEMORY_EXTRACTION"}
                """);
        service.handleJob(job);

        ArgumentCaptor<AiUserMemoryEntity> memoryCaptor = ArgumentCaptor.forClass(AiUserMemoryEntity.class);
        verify(memoryMapper, org.mockito.Mockito.times(2)).updateById(memoryCaptor.capture());
        assertThat(memoryCaptor.getAllValues()).anySatisfy(memory -> {
            assertThat(memory.getId()).isEqualTo(300L);
            assertThat(memory.getStatus()).isEqualTo("ACTIVE");
        });
        assertThat(memoryCaptor.getAllValues()).anySatisfy(memory -> {
            assertThat(memory.getId()).isEqualTo(301L);
            assertThat(memory.getStatus()).isEqualTo("SUPERSEDED");
            assertThat(memory.getSource()).isEqualTo("AI_MEMORY_MERGED");
        });
        verify(retrievalService).deleteOwner(7L, "memory", "301");
    }

    private static AiMemoryClaimEntity distrustedClaim(double confidence) {
        AiMemoryClaimEntity claim = new AiMemoryClaimEntity();
        claim.id = 400L;
        claim.userId = 7L;
        claim.legacyMemoryId = 300L;
        claim.scopeType = "GLOBAL";
        claim.category = "PREFERENCE";
        claim.memoryKey = "guidance_preference";
        claim.canonicalText = "用户偏好先给提示。";
        claim.confidence = BigDecimal.valueOf(confidence);
        claim.status = "DISABLED";
        claim.version = 2;
        claim.createdAt = LocalDateTime.now();
        claim.updatedAt = LocalDateTime.now();
        return claim;
    }

    private static AiMemoryCandidateEntity candidate() {
        AiMemoryCandidateEntity candidate = new AiMemoryCandidateEntity();
        candidate.id = 100L;
        candidate.userId = 7L;
        candidate.category = "PREFERENCE";
        candidate.memoryKey = "guidance_preference";
        candidate.canonicalText = "用户偏好先给提示。";
        candidate.valueJson = "{}";
        candidate.scopeType = "GLOBAL";
        candidate.evidenceType = "USER_ACCEPTED";
        candidate.writeScore = BigDecimal.valueOf(0.88);
        candidate.status = "NEEDS_CONFIRMATION";
        candidate.createdAt = LocalDateTime.now();
        candidate.updatedAt = LocalDateTime.now();
        return candidate;
    }

    private static AiUserMemoryEntity memory() {
        AiUserMemoryEntity memory = new AiUserMemoryEntity();
        memory.setId(300L);
        memory.setUserId(7L);
        memory.setCategory("preference");
        memory.setTitle("引导偏好");
        memory.setMemoryType("guidance_preference");
        memory.setContent("用户偏好先给提示。");
        memory.setConfidence(BigDecimal.valueOf(0.8));
        memory.setSource("USER_CONFIRMED");
        memory.setStatus("ACTIVE");
        memory.setCreatedAt(LocalDateTime.now());
        memory.setUpdatedAt(LocalDateTime.now());
        return memory;
    }

    private static AiModelEffectiveConfig config() {
        return new AiModelEffectiveConfig(
                AiModelScope.MEMORY_EXTRACTION,
                true,
                false,
                "TEST",
                "deepseek",
                "https://api.deepseek.com/chat/completions",
                "test-key",
                "environment",
                "ENVIRONMENT",
                "DEEPSEEK_API_KEY",
                "deepseek-v4-pro",
                true,
                false,
                "high",
                null,
                1200,
                null,
                null,
                null
        );
    }
}
