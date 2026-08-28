package com.aioj.next.ai.domain;

import com.aioj.next.ai.persistence.entity.AiContestAssistanceLegacySnapshotEntity;
import com.aioj.next.ai.persistence.entity.AiContestAssistanceModelUsageEntity;
import com.aioj.next.ai.persistence.entity.AiContestAssistanceTurnEntity;
import com.aioj.next.ai.persistence.entity.AiConversationEntity;
import com.aioj.next.ai.persistence.entity.AiMessageEntity;
import com.aioj.next.ai.persistence.mapper.AiContestAssistanceLegacySnapshotMapper;
import com.aioj.next.ai.persistence.mapper.AiContestAssistanceModelUsageMapper;
import com.aioj.next.ai.persistence.mapper.AiContestAssistanceTurnMapper;
import com.aioj.next.ai.persistence.mapper.AiConversationMapper;
import com.aioj.next.ai.persistence.mapper.AiMessageMapper;
import com.aioj.next.contract.ai.AdminContestAiAssistanceSummary;
import com.aioj.next.contract.ai.AdminContestAiConversationSummary;
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

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyMap;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class ContestAiAssistanceStatisticsServiceTest {
    private static final long CONTEST_ID = 9L;
    private static final long RUN_ID = 3L;
    private static final long USER_ID = 7L;

    @Mock private AiContestAssistanceTurnMapper turnMapper;
    @Mock private AiContestAssistanceModelUsageMapper usageMapper;
    @Mock private AiContestAssistanceLegacySnapshotMapper snapshotMapper;
    @Mock private AiConversationMapper conversationMapper;
    @Mock private AiMessageMapper messageMapper;
    @Mock private ProblemServiceClient problemServiceClient;
    @Mock private OperationAuditWriter auditWriter;

    private ContestAiAssistanceStatisticsService service;

    @BeforeEach
    void setUp() {
        service = new ContestAiAssistanceStatisticsService(turnMapper, usageMapper, snapshotMapper,
                conversationMapper, messageMapper, problemServiceClient, auditWriter);
        when(problemServiceClient.contestRunWindows(CONTEST_ID)).thenReturn(List.of(
                new ContestRunWindow(RUN_ID, instant("2026-08-05T09:00:00"), instant("2026-08-05T10:30:00"))));
    }

    @Test
    void summariesAggregateOneLedgerRowPerTurnAllProviderUsageAndHistoricalEstimate() {
        AiContestAssistanceTurnEntity first = turn(101L, "turn-1", USER_ID, "c-1", "2026-08-05T10:00:00",
                "PRIVATE_CONTEST_QUESTION", "COMPLETE");
        AiContestAssistanceTurnEntity second = turn(102L, "turn-2", USER_ID, "c-1", "2026-08-05T10:31:00",
                "PUBLIC_FULL_CODE_REQUEST", "PARTIAL");
        AiContestAssistanceTurnEntity outsideGrace = turn(103L, "turn-3", USER_ID, "c-2", "2026-08-05T10:31:01",
                "NONE", "COMPLETE");
        when(turnMapper.selectList(any(LambdaQueryWrapper.class))).thenReturn(List.of(first, second, outsideGrace));
        when(usageMapper.selectList(any(LambdaQueryWrapper.class))).thenReturn(List.of(
                usage(101L, 10, 4),
                usage(101L, 3, 2), // understanding + Agent: both must be retained.
                usage(102L, 7, 5), // safe regeneration remains an independent aggregate.
                usage(103L, 999, 999)
        ));
        when(snapshotMapper.selectList(any(LambdaQueryWrapper.class))).thenReturn(List.of(
                snapshot(USER_ID, 1, 12, 6, 1, 1, "2026-08-04T10:00:00")
        ));
        when(problemServiceClient.contestParticipantProfiles(CONTEST_ID)).thenReturn(List.of(
                new ContestParticipantProfile(USER_ID, RUN_ID, "stu007", "Student Seven")
        ));

        List<AdminContestAiAssistanceSummary> summaries = service.summaries(CONTEST_ID, RUN_ID);

        assertThat(summaries).hasSize(1);
        AdminContestAiAssistanceSummary summary = summaries.get(0);
        assertThat(summary.userId()).isEqualTo(USER_ID);
        assertThat(summary.account()).isEqualTo("stu007");
        assertThat(summary.turnCount()).isEqualTo(3); // two V3 ledger turns + one V63 historical estimate.
        assertThat(summary.promptTokens()).isEqualTo(32);
        assertThat(summary.completionTokens()).isEqualTo(17);
        assertThat(summary.conversationCount()).isEqualTo(2); // live c-1 once + historical snapshot.
        assertThat(summary.interceptedCount()).isEqualTo(3); // max one per live turn, plus historical estimate.
        assertThat(summary.dataSource()).isEqualTo(ContestAiAssistanceStatisticsService.DATA_SOURCE_MIXED);
        assertThat(summary.tokenAccountingStatus()).isEqualTo(ContestAiAssistanceStatisticsService.TOKEN_ACCOUNTING_PARTIAL);
        assertThat(summary.lastUsedAt()).isEqualTo(instant("2026-08-05T10:31:00"));
    }

    @Test
    void legacySnapshotHasNarrowConversationAndTranscriptFallbackInsideWindow() {
        when(turnMapper.selectList(any(LambdaQueryWrapper.class))).thenReturn(List.of());
        when(snapshotMapper.selectList(any(LambdaQueryWrapper.class))).thenReturn(List.of(
                snapshot(USER_ID, 2, 20, 10, 1, 0, "2026-08-05T10:00:00")
        ));
        AiConversationEntity conversation = conversation("legacy-c", USER_ID);
        when(conversationMapper.selectList(any(LambdaQueryWrapper.class))).thenReturn(List.of(conversation));
        when(messageMapper.selectList(any(LambdaQueryWrapper.class))).thenReturn(List.of(
                message("legacy-c", "2026-08-05T10:00:00"),
                message("legacy-c", "2026-08-05T11:00:00")
        ));

        List<AdminContestAiConversationSummary> conversations = service.conversations(CONTEST_ID, RUN_ID, USER_ID);

        assertThat(conversations).singleElement().satisfies(summary -> {
            assertThat(summary.conversationId()).isEqualTo("legacy-c");
            assertThat(summary.messageCount()).isEqualTo(1);
        });
    }

    @Test
    void transcriptUsesLedgerOrHistoricalFallbackAuthorizationAndAuditsView() {
        when(turnMapper.selectList(any(LambdaQueryWrapper.class))).thenReturn(List.of());
        when(snapshotMapper.selectList(any(LambdaQueryWrapper.class))).thenReturn(List.of(
                snapshot(USER_ID, 1, 0, 0, 1, 0, "2026-08-05T10:00:00")
        ));
        when(conversationMapper.selectList(any(LambdaQueryWrapper.class))).thenReturn(List.of(conversation("legacy-c", USER_ID)));
        when(messageMapper.selectList(any(LambdaQueryWrapper.class))).thenReturn(List.of(
                message("legacy-c", "2026-08-05T10:00:00")
        ));

        assertThat(service.messages(CONTEST_ID, RUN_ID, USER_ID, "legacy-c", 99L))
                .singleElement()
                .satisfies(message -> assertThat(message.content()).isEqualTo("message"));
        verify(auditWriter).record(eq("AI_CONTEST_TRANSCRIPT_VIEWED"), eq("AI_CONVERSATION"), isNull(),
                eq("VIEWED"), anyMap(), eq(99L), eq(CONTEST_ID), isNull(), eq(USER_ID));
    }

    private AiContestAssistanceTurnEntity turn(Long id, String turnId, Long userId, String conversationId,
                                                String startedAt, String interceptType, String tokenStatus) {
        AiContestAssistanceTurnEntity entity = new AiContestAssistanceTurnEntity();
        entity.setId(id);
        entity.setTurnId(turnId);
        entity.setUserId(userId);
        entity.setContestId(CONTEST_ID);
        entity.setContestRunId(RUN_ID);
        entity.setConversationId(conversationId);
        entity.setStartedAt(LocalDateTime.parse(startedAt));
        entity.setInterceptType(interceptType);
        entity.setTokenAccountingStatus(tokenStatus);
        return entity;
    }

    private AiContestAssistanceModelUsageEntity usage(Long assistanceTurnId, long promptTokens, long completionTokens) {
        AiContestAssistanceModelUsageEntity entity = new AiContestAssistanceModelUsageEntity();
        entity.setAssistanceTurnId(assistanceTurnId);
        entity.setPromptTokens(promptTokens);
        entity.setCompletionTokens(completionTokens);
        return entity;
    }

    private AiContestAssistanceLegacySnapshotEntity snapshot(Long userId, long turns, long promptTokens,
                                                              long completionTokens, long conversations, long intercepted,
                                                              String lastUsedAt) {
        AiContestAssistanceLegacySnapshotEntity entity = new AiContestAssistanceLegacySnapshotEntity();
        entity.setContestId(CONTEST_ID);
        entity.setContestRunId(RUN_ID);
        entity.setUserId(userId);
        entity.setTurnCount(turns);
        entity.setPromptTokens(promptTokens);
        entity.setCompletionTokens(completionTokens);
        entity.setConversationCount(conversations);
        entity.setInterceptedCount(intercepted);
        entity.setLastUsedAt(LocalDateTime.parse(lastUsedAt));
        return entity;
    }

    private AiConversationEntity conversation(String id, Long userId) {
        AiConversationEntity entity = new AiConversationEntity();
        entity.setId(id);
        entity.setUserId(userId);
        entity.setContestId(CONTEST_ID);
        entity.setContestRunId(RUN_ID);
        entity.setTitle("Legacy conversation");
        return entity;
    }

    private AiMessageEntity message(String conversationId, String createdAt) {
        AiMessageEntity entity = new AiMessageEntity();
        entity.setId(1L);
        entity.setConversationId(conversationId);
        entity.setRole("user");
        entity.setContent("message");
        entity.setStatus("COMPLETED");
        entity.setCreatedAt(LocalDateTime.parse(createdAt));
        return entity;
    }

    private Instant instant(String localDateTime) {
        return LocalDateTime.parse(localDateTime).atZone(ZoneId.systemDefault()).toInstant();
    }
}
