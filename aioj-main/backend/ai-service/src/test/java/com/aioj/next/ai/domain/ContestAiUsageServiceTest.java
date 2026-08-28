package com.aioj.next.ai.domain;

import com.aioj.next.ai.persistence.entity.AiConversationEntity;
import com.aioj.next.ai.persistence.entity.AiMessageEntity;
import com.aioj.next.ai.persistence.entity.AiUsageRecordEntity;
import com.aioj.next.ai.persistence.entity.OperationAuditEventEntity;
import com.aioj.next.ai.persistence.mapper.AiConversationMapper;
import com.aioj.next.ai.persistence.mapper.AiMessageMapper;
import com.aioj.next.ai.persistence.mapper.AiUsageRecordMapper;
import com.aioj.next.ai.persistence.mapper.OperationAuditEventMapper;
import com.aioj.next.common.error.DomainException;
import com.aioj.next.contract.ai.AdminContestAiConversationSummary;
import com.aioj.next.contract.ai.AdminContestAiMessageResponse;
import com.aioj.next.contract.ai.AdminContestAiUsageSummary;
import com.aioj.next.contract.ai.ContestParticipantProfile;
import com.aioj.next.contract.contest.ContestRunWindow;
import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyMap;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class ContestAiUsageServiceTest {
    @Mock
    private AiUsageRecordMapper usageMapper;
    @Mock
    private AiConversationMapper conversationMapper;
    @Mock
    private AiMessageMapper messageMapper;
    @Mock
    private OperationAuditEventMapper auditMapper;
    @Mock
    private ProblemServiceClient problemServiceClient;
    @Mock
    private OperationAuditWriter auditWriter;

    private ContestAiUsageService service;

    @BeforeEach
    void setUp() {
        service = new ContestAiUsageService(usageMapper, conversationMapper, messageMapper, auditMapper,
                problemServiceClient, auditWriter);
    }

    private AiUsageRecordEntity usage(Long userId, long prompt, long completion) {
        AiUsageRecordEntity record = new AiUsageRecordEntity();
        record.setUserId(userId);
        record.setContestId(9L);
        record.setContestRunId(3L);
        record.setPromptTokens(prompt);
        record.setCompletionTokens(completion);
        record.setCreatedAt(LocalDateTime.parse("2026-08-05T10:00:00"));
        return record;
    }

    private AiConversationEntity conversation(String id, Long userId) {
        AiConversationEntity entity = new AiConversationEntity();
        entity.setId(id);
        entity.setUserId(userId);
        entity.setContestId(9L);
        entity.setContestRunId(3L);
        entity.setTitle("conversation " + id);
        entity.setMode("assist");
        entity.setUpdatedAt(LocalDateTime.parse("2026-08-05T11:00:00"));
        return entity;
    }

    private Instant instant(String localDateTime) {
        return LocalDateTime.parse(localDateTime).atZone(ZoneId.systemDefault()).toInstant();
    }

    private AiMessageEntity message(String conversationId, String createdAt) {
        AiMessageEntity entity = new AiMessageEntity();
        entity.setConversationId(conversationId);
        entity.setRole("user");
        entity.setContent("message");
        entity.setStatus("COMPLETED");
        entity.setCreatedAt(LocalDateTime.parse(createdAt));
        return entity;
    }

    private OperationAuditEventEntity auditEvent(Long userId, String action, String status, String createdAt) {
        OperationAuditEventEntity event = new OperationAuditEventEntity();
        event.setActorUserId(userId);
        event.setContestId(9L);
        event.setContestRunId(3L);
        event.setAction(action);
        event.setStatus(status);
        event.setCreatedAt(instant(createdAt));
        return event;
    }

    @Test
    void summariesAggregateUsageConversationsAndBlocksPerStudent() {
        when(problemServiceClient.contestRunWindows(9L)).thenReturn(List.of(
                new ContestRunWindow(3L, instant("2026-08-05T09:00:00"), instant("2026-08-05T10:30:00"))));
        when(usageMapper.selectList(any(LambdaQueryWrapper.class)))
                .thenReturn(List.of(usage(7L, 10, 20), usage(7L, 5, 5), usage(8L, 1, 2)));
        when(conversationMapper.selectList(any(LambdaQueryWrapper.class)))
                .thenReturn(List.of(conversation("c-1", 7L)));
        when(messageMapper.selectList(any(LambdaQueryWrapper.class)))
                .thenReturn(List.of(message("c-1", "2026-08-05T10:00:00")));
        OperationAuditEventEntity block = new OperationAuditEventEntity();
        block.setActorUserId(7L);
        block.setContestId(9L);
        block.setContestRunId(3L);
        block.setAction("AI_CONTEST_REQUEST_BLOCKED");
        block.setCreatedAt(instant("2026-08-05T10:01:00"));
        when(auditMapper.selectList(any(LambdaQueryWrapper.class))).thenReturn(List.of(block));
        when(problemServiceClient.contestParticipantProfiles(9L)).thenReturn(List.of(
                new ContestParticipantProfile(7L, 3L, "stu001", "张三"),
                new ContestParticipantProfile(8L, 3L, "stu002", "李四")
        ));

        List<AdminContestAiUsageSummary> summaries = service.summaries(9L, 3L);

        assertEquals(2, summaries.size());
        AdminContestAiUsageSummary first = summaries.get(0);
        assertEquals(7L, first.userId());
        assertEquals("stu001", first.account());
        assertEquals("张三", first.displayName());
        assertEquals(2, first.callCount());
        assertEquals(15, first.promptTokens());
        assertEquals(25, first.completionTokens());
        assertEquals(1, first.conversationCount());
        assertEquals(1, first.blockedCount());
        assertEquals(0, first.evaluatedCount());
        assertEquals(0, first.constrainCount());
        assertEquals(0, first.refuseCount());
        assertEquals(0, first.degradedCount());
        AdminContestAiUsageSummary second = summaries.get(1);
        assertEquals(8L, second.userId());
        assertEquals(1, second.callCount());
        assertEquals(0, second.blockedCount());
        assertEquals(0, second.evaluatedCount());
    }

    @Test
    void summariesExcludeRecordsOutsideTheContestWindow() {
        // Window for run 3 ends 10:30 (+60s grace); usage/messages/blocks after that are excluded.
        when(problemServiceClient.contestRunWindows(9L)).thenReturn(List.of(
                new ContestRunWindow(3L, instant("2026-08-05T09:00:00"), instant("2026-08-05T10:30:00"))));
        AiUsageRecordEntity inside = usage(7L, 10, 20);
        AiUsageRecordEntity outside = usage(7L, 100, 200);
        outside.setCreatedAt(LocalDateTime.parse("2026-08-05T12:00:00"));
        when(usageMapper.selectList(any(LambdaQueryWrapper.class))).thenReturn(List.of(inside, outside));
        when(conversationMapper.selectList(any(LambdaQueryWrapper.class)))
                .thenReturn(List.of(conversation("c-1", 7L), conversation("c-2", 7L)));
        when(messageMapper.selectList(any(LambdaQueryWrapper.class))).thenReturn(List.of(
                message("c-1", "2026-08-05T10:00:00"),
                message("c-2", "2026-08-05T12:00:00")));
        when(problemServiceClient.contestParticipantProfiles(9L)).thenReturn(List.of(
                new ContestParticipantProfile(7L, 3L, "stu001", "张三")));

        List<AdminContestAiUsageSummary> summaries = service.summaries(9L, 3L);

        assertEquals(1, summaries.size());
        assertEquals(1, summaries.get(0).callCount());
        assertEquals(1, summaries.get(0).conversationCount());
    }

    @Test
    void summariesGroupGuardEvaluationsByDecisionAndCountDegraded() {
        // Window for run 3 ends 10:30 (+60s grace); the 12:00 evaluation is excluded.
        when(problemServiceClient.contestRunWindows(9L)).thenReturn(List.of(
                new ContestRunWindow(3L, instant("2026-08-05T09:00:00"), instant("2026-08-05T10:30:00"))));
        when(auditMapper.selectList(any(LambdaQueryWrapper.class))).thenReturn(List.of(
                auditEvent(7L, "AI_CONTEST_GUARD_EVALUATED", "PASS", "2026-08-05T10:00:00"),
                auditEvent(7L, "AI_CONTEST_GUARD_EVALUATED", "CONSTRAIN", "2026-08-05T10:01:00"),
                auditEvent(7L, "AI_CONTEST_GUARD_EVALUATED", "CONSTRAIN", "2026-08-05T10:02:00"),
                auditEvent(7L, "AI_CONTEST_GUARD_EVALUATED", "REFUSE", "2026-08-05T10:03:00"),
                auditEvent(7L, "AI_CONTEST_GUARD_EVALUATED", "CONSTRAIN", "2026-08-05T12:00:00"),
                auditEvent(8L, "AI_CONTEST_GUARD_DEGRADED", "DEGRADED", "2026-08-05T10:04:00")));

        List<AdminContestAiUsageSummary> summaries = service.summaries(9L, 3L);

        assertEquals(2, summaries.size());
        AdminContestAiUsageSummary first = summaries.get(0);
        assertEquals(7L, first.userId());
        assertEquals(4, first.evaluatedCount());
        assertEquals(2, first.constrainCount());
        assertEquals(1, first.refuseCount());
        assertEquals(0, first.degradedCount());
        assertEquals(0, first.blockedCount());
        AdminContestAiUsageSummary second = summaries.get(1);
        assertEquals(8L, second.userId());
        assertEquals(0, second.evaluatedCount());
        assertEquals(1, second.degradedCount());
    }

    @Test
    void summariesKeepBlockedCountCompatibleWithLegacyAndParticipantActions() {
        when(problemServiceClient.contestRunWindows(9L)).thenReturn(List.of(
                new ContestRunWindow(3L, instant("2026-08-05T09:00:00"), instant("2026-08-05T10:30:00"))));
        when(auditMapper.selectList(any(LambdaQueryWrapper.class))).thenReturn(List.of(
                auditEvent(7L, "AI_CONTEST_REQUEST_BLOCKED", "BLOCKED", "2026-08-05T10:00:00"),
                auditEvent(7L, "AI_CONTEST_LEAK_BLOCKED", "BLOCKED", "2026-08-05T10:01:00"),
                auditEvent(7L, "AI_CONTEST_LEAK_PARTICIPANT_BLOCKED", "BLOCKED", "2026-08-05T10:02:00"),
                auditEvent(7L, "AI_CONTEST_GUARD_EVALUATED", "REFUSE", "2026-08-05T10:03:00")));

        List<AdminContestAiUsageSummary> summaries = service.summaries(9L, 3L);

        assertEquals(1, summaries.size());
        AdminContestAiUsageSummary summary = summaries.get(0);
        assertEquals(3, summary.blockedCount());
        assertEquals(1, summary.evaluatedCount());
        assertEquals(1, summary.refuseCount());
    }

    @Test
    void conversationsExcludeMessagesOutsideTheContestWindow() {
        when(problemServiceClient.contestRunWindows(9L)).thenReturn(List.of(
                new ContestRunWindow(3L, instant("2026-08-05T09:00:00"), instant("2026-08-05T10:30:00"))));
        when(conversationMapper.selectList(any(LambdaQueryWrapper.class)))
                .thenReturn(List.of(conversation("c-1", 7L), conversation("c-2", 7L)));
        when(messageMapper.selectList(any(LambdaQueryWrapper.class))).thenReturn(List.of(
                message("c-1", "2026-08-05T10:00:00"),
                message("c-1", "2026-08-05T10:05:00"),
                message("c-2", "2026-08-05T12:00:00")));

        List<AdminContestAiConversationSummary> conversations = service.conversations(9L, 3L, 7L);

        assertEquals(1, conversations.size());
        assertEquals("c-1", conversations.get(0).conversationId());
        assertEquals(2, conversations.get(0).messageCount());
    }

    @Test
    void conversationsCountMessagesPerConversation() {
        when(conversationMapper.selectList(any(LambdaQueryWrapper.class)))
                .thenReturn(List.of(conversation("c-1", 7L)));
        AiMessageEntity first = new AiMessageEntity();
        first.setConversationId("c-1");
        first.setCreatedAt(LocalDateTime.parse("2026-08-05T10:00:00"));
        AiMessageEntity second = new AiMessageEntity();
        second.setConversationId("c-1");
        second.setCreatedAt(LocalDateTime.parse("2026-08-05T10:05:00"));
        when(messageMapper.selectList(any(LambdaQueryWrapper.class))).thenReturn(List.of(first, second));

        List<AdminContestAiConversationSummary> conversations = service.conversations(9L, 3L, 7L);

        assertEquals(1, conversations.size());
        assertEquals(2, conversations.get(0).messageCount());
        assertEquals("conversation c-1", conversations.get(0).title());
    }

    @Test
    void messagesRejectConversationFromAnotherContestOrUser() {
        AiConversationEntity entity = conversation("c-1", 7L);
        when(conversationMapper.selectById("c-1")).thenReturn(entity);

        assertThrows(DomainException.class, () -> service.messages(10L, 7L, "c-1", 99L));
        assertThrows(DomainException.class, () -> service.messages(9L, 8L, "c-1", 99L));
        verify(auditWriter, never()).record(anyString(), anyString(), any(), anyString(), anyMap(),
                anyLong(), any(), any(), any());
    }

    @Test
    void messagesReturnTranscriptAndRecordAuditView() {
        AiConversationEntity entity = conversation("c-1", 7L);
        when(conversationMapper.selectById("c-1")).thenReturn(entity);
        AiMessageEntity message = new AiMessageEntity();
        message.setId(1L);
        message.setConversationId("c-1");
        message.setRole("user");
        message.setContent("how do I start?");
        message.setStatus("COMPLETED");
        message.setCreatedAt(LocalDateTime.parse("2026-08-05T10:00:00"));
        when(messageMapper.selectList(any(LambdaQueryWrapper.class))).thenReturn(List.of(message));

        List<AdminContestAiMessageResponse> messages = service.messages(9L, 7L, "c-1", 99L);

        assertEquals(1, messages.size());
        assertEquals("how do I start?", messages.get(0).content());
        verify(auditWriter).record(eq("AI_CONTEST_TRANSCRIPT_VIEWED"), eq("AI_CONVERSATION"), isNull(),
                eq("VIEWED"), anyMap(), eq(99L), eq(9L), isNull(), eq(7L));
    }
}
