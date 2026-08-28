package com.aioj.next.ai.agent.profile;

import com.aioj.next.ai.persistence.entity.AiProfileSignalEntity;
import com.aioj.next.ai.persistence.mapper.AiProfileSignalMapper;
import com.baomidou.mybatisplus.core.conditions.query.QueryWrapper;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.math.BigDecimal;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

class ProfileSignalIngestionServiceTest {

    private final AiProfileSignalMapper signalMapper = mock(AiProfileSignalMapper.class);
    private final ProfileSignalIngestionService service =
            new ProfileSignalIngestionService(signalMapper, new ObjectMapper());

    @Test
    void insertsPendingSignalsWithNormalizedFields() {
        when(signalMapper.selectCount(any(QueryWrapper.class))).thenReturn(0L);

        int inserted = service.recordChatTurnSignals(7L, "t-1", List.of(
                new ProfileSignalIngestionService.SignalProposal("对位运算不熟", "weakness", "位运算", "negative", 0.756),
                new ProfileSignalIngestionService.SignalProposal("前缀和掌握良好", "MASTERY", null, null, 1.4)),
                ProfileSignalIngestionService.SOURCE_TYPE_CHAT_TURN);

        assertThat(inserted).isEqualTo(2);
        ArgumentCaptor<AiProfileSignalEntity> captor = ArgumentCaptor.forClass(AiProfileSignalEntity.class);
        verify(signalMapper, times(2)).insert(captor.capture());

        AiProfileSignalEntity first = captor.getAllValues().get(0);
        assertThat(first.getUserId()).isEqualTo(7L);
        assertThat(first.getSignalType()).isEqualTo("WEAKNESS");
        assertThat(first.getKnowledgeNode()).isEqualTo("位运算");
        assertThat(first.getPolarity()).isEqualTo("NEGATIVE");
        assertThat(first.getScore()).isEqualByComparingTo(new BigDecimal("0.7560"));
        assertThat(first.getSourceType()).isEqualTo("CHAT_TURN");
        assertThat(first.getSourceId()).isEqualTo("t-1");
        assertThat(first.getStatus()).isEqualTo("PENDING");
        assertThat(first.getPayloadJson()).contains("对位运算不熟");
        assertThat(first.getCreatedAt()).isNotNull();

        AiProfileSignalEntity second = captor.getAllValues().get(1);
        assertThat(second.getKnowledgeNode()).isNull();
        assertThat(second.getPolarity()).isEqualTo("NEUTRAL");
        assertThat(second.getScore()).isEqualByComparingTo(new BigDecimal("1.0000"));
    }

    @Test
    void skipsWhenSourceAlreadyRecorded() {
        when(signalMapper.selectCount(any(QueryWrapper.class))).thenReturn(2L);

        int inserted = service.recordChatTurnSignals(7L, "t-1", List.of(
                new ProfileSignalIngestionService.SignalProposal("信号", "WEAKNESS", null, "NEUTRAL", 0.5)),
                "CHAT_TURN");

        assertThat(inserted).isZero();
        verify(signalMapper, never()).insert(any(AiProfileSignalEntity.class));
    }

    @Test
    void blankSignalsAreSkipped() {
        when(signalMapper.selectCount(any(QueryWrapper.class))).thenReturn(0L);

        int inserted = service.recordChatTurnSignals(7L, "t-1", List.of(
                new ProfileSignalIngestionService.SignalProposal("   ", "WEAKNESS", null, "NEUTRAL", 0.5),
                new ProfileSignalIngestionService.SignalProposal("有效观察", "PROGRESS", null, "POSITIVE", 0.6)),
                "CHAT_TURN");

        assertThat(inserted).isEqualTo(1);
        verify(signalMapper, times(1)).insert(any(AiProfileSignalEntity.class));
    }

    @Test
    void emptyInputReturnsZeroWithoutDbAccess() {
        assertThat(service.recordChatTurnSignals(7L, "t-1", List.of(), "CHAT_TURN")).isZero();
        assertThat(service.recordChatTurnSignals(7L, "t-1", null, "CHAT_TURN")).isZero();
        assertThat(service.recordChatTurnSignals(null, "t-1", List.of(
                new ProfileSignalIngestionService.SignalProposal("s", null, null, null, 0.5)), "CHAT_TURN"))
                .isZero();
        verifyNoInteractions(signalMapper);
    }

    @Test
    void knowledgeNodeIsNormalizedAtWriteTime() {
        when(signalMapper.selectCount(any(QueryWrapper.class))).thenReturn(0L);

        int inserted = service.recordChatTurnSignals(7L, "t-1", List.of(
                new ProfileSignalIngestionService.SignalProposal("二分掌握不稳", "WEAKNESS", "Binary Search", "NEGATIVE", 0.6),
                new ProfileSignalIngestionService.SignalProposal("遍历混淆", "WEAKNESS", "BFS/DFS 遍历", "NEGATIVE", 0.6),
                new ProfileSignalIngestionService.SignalProposal("CJK 保持不变", "MASTERY", "位运算", "POSITIVE", 0.9),
                new ProfileSignalIngestionService.SignalProposal("无节点", "MASTERY", "  ", "POSITIVE", 0.9)),
                ProfileSignalIngestionService.SOURCE_TYPE_CHAT_TURN);

        assertThat(inserted).isEqualTo(4);
        ArgumentCaptor<AiProfileSignalEntity> captor = ArgumentCaptor.forClass(AiProfileSignalEntity.class);
        verify(signalMapper, times(4)).insert(captor.capture());
        assertThat(captor.getAllValues().get(0).getKnowledgeNode()).isEqualTo("binary_search");
        assertThat(captor.getAllValues().get(1).getKnowledgeNode()).isEqualTo("bfs_dfs_遍历");
        assertThat(captor.getAllValues().get(2).getKnowledgeNode()).isEqualTo("位运算");
        assertThat(captor.getAllValues().get(3).getKnowledgeNode()).isNull();
        // payloadJson keeps the human-readable signal text untouched.
        assertThat(captor.getAllValues().get(0).getPayloadJson()).contains("二分掌握不稳");
    }

    @Test
    void judgedSubmissionSignalsUseJudgedSourceAndSubmissionId() {
        when(signalMapper.selectCount(any(QueryWrapper.class))).thenReturn(0L);

        int inserted = service.recordJudgedSubmissionSignals(7L, 456L, List.of(
                new ProfileSignalIngestionService.SignalProposal(
                        "wrong_answer on「二分练习」: wrong_answer, boundary", "WEAKNESS", "binary_search", "NEGATIVE", 0.62)));

        assertThat(inserted).isEqualTo(1);
        ArgumentCaptor<AiProfileSignalEntity> captor = ArgumentCaptor.forClass(AiProfileSignalEntity.class);
        verify(signalMapper).insert(captor.capture());
        AiProfileSignalEntity entity = captor.getValue();
        assertThat(entity.getUserId()).isEqualTo(7L);
        assertThat(entity.getSourceType()).isEqualTo(ProfileSignalIngestionService.SOURCE_TYPE_JUDGED_SUBMISSION);
        assertThat(entity.getSourceId()).isEqualTo("456");
        assertThat(entity.getSignalType()).isEqualTo("WEAKNESS");
        assertThat(entity.getKnowledgeNode()).isEqualTo("binary_search");
        assertThat(entity.getPolarity()).isEqualTo("NEGATIVE");
        assertThat(entity.getScore()).isEqualByComparingTo(new BigDecimal("0.6200"));
        assertThat(entity.getStatus()).isEqualTo("PENDING");
    }

    @Test
    void judgedSubmissionSignalsSkipWhenSubmissionAlreadyRecorded() {
        when(signalMapper.selectCount(any(QueryWrapper.class))).thenReturn(1L);

        int inserted = service.recordJudgedSubmissionSignals(7L, 456L, List.of(
                new ProfileSignalIngestionService.SignalProposal("信号", "WEAKNESS", "binary_search", "NEGATIVE", 0.5)));

        assertThat(inserted).isZero();
        verify(signalMapper, never()).insert(any(AiProfileSignalEntity.class));
    }

    @Test
    void judgedSubmissionSignalsRejectNullSubmissionId() {
        assertThat(service.recordJudgedSubmissionSignals(7L, null, List.of(
                new ProfileSignalIngestionService.SignalProposal("信号", "WEAKNESS", null, "NEGATIVE", 0.5))))
                .isZero();
        verifyNoInteractions(signalMapper);
    }
}
