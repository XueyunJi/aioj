package com.aioj.next.ai.domain;

import com.aioj.next.ai.persistence.entity.AiTurnEntity;
import com.aioj.next.ai.persistence.mapper.AiTurnMapper;
import com.aioj.next.common.error.DomainException;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.dao.DuplicateKeyException;

import java.time.LocalDateTime;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class AiTurnServiceTest {

    @Mock
    private AiTurnMapper turnMapper;

    private AiTurnService service;

    @BeforeEach
    void setUp() {
        service = new AiTurnService(turnMapper);
    }

    @Test
    void beginTurnInsertsNewTurnWithNextSeq() {
        when(turnMapper.selectObjs(any())).thenReturn(List.of(3L));
        when(turnMapper.insert(any(AiTurnEntity.class))).thenReturn(1);

        AiTurnService.BeginTurnOutcome outcome = service.beginTurn("c-1", "client-1");

        assertThat(outcome.created()).isTrue();
        assertThat(outcome.turn().getTurnSeq()).isEqualTo(4L);
        assertThat(outcome.turn().getStatus()).isEqualTo(AiTurnService.STATUS_RECEIVED);
        assertThat(outcome.turn().getClientTurnId()).isEqualTo("client-1");
    }

    @Test
    void beginTurnStartsAtOneWhenConversationHasNoTurns() {
        when(turnMapper.selectObjs(any())).thenReturn(List.of());
        when(turnMapper.insert(any(AiTurnEntity.class))).thenReturn(1);

        AiTurnService.BeginTurnOutcome outcome = service.beginTurn("c-1", "client-1");

        assertThat(outcome.turn().getTurnSeq()).isEqualTo(1L);
    }

    @Test
    void beginTurnReturnsExistingTurnOnClientKeyConflict() {
        AiTurnEntity existing = turn("t-1", "c-1", "client-1", 7L, AiTurnService.STATUS_GENERATING);
        when(turnMapper.selectObjs(any())).thenReturn(List.of(7L));
        when(turnMapper.insert(any(AiTurnEntity.class))).thenThrow(new DuplicateKeyException("uk_turn_client"));
        when(turnMapper.selectOne(any())).thenReturn(existing);

        AiTurnService.BeginTurnOutcome outcome = service.beginTurn("c-1", "client-1");

        assertThat(outcome.created()).isFalse();
        assertThat(outcome.turn().getId()).isEqualTo("t-1");
        assertThat(outcome.turn().getStatus()).isEqualTo(AiTurnService.STATUS_GENERATING);
        verify(turnMapper, times(1)).insert(any(AiTurnEntity.class));
    }

    @Test
    void beginTurnRetriesWithFreshSeqOnSeqKeyConflict() {
        when(turnMapper.selectObjs(any())).thenReturn(List.of(1L), List.of(2L));
        when(turnMapper.insert(any(AiTurnEntity.class)))
                .thenThrow(new DuplicateKeyException("uk_turn_seq"))
                .thenReturn(1);
        when(turnMapper.selectOne(any())).thenReturn(null);

        AiTurnService.BeginTurnOutcome outcome = service.beginTurn("c-1", "client-1");

        assertThat(outcome.created()).isTrue();
        assertThat(outcome.turn().getTurnSeq()).isEqualTo(3L);
        verify(turnMapper, times(2)).insert(any(AiTurnEntity.class));
    }

    @Test
    void beginTurnThrowsConflictAfterExhaustingSeqRetries() {
        when(turnMapper.selectObjs(any())).thenReturn(List.of(1L));
        when(turnMapper.insert(any(AiTurnEntity.class))).thenThrow(new DuplicateKeyException("uk_turn_seq"));
        when(turnMapper.selectOne(any())).thenReturn(null);

        assertThatThrownBy(() -> service.beginTurn("c-1", "client-1"))
                .isInstanceOf(DomainException.class)
                .hasMessageContaining("Concurrent AI turn creation conflict");
        verify(turnMapper, times(3)).insert(any(AiTurnEntity.class));
    }

    @Test
    void beginTurnGeneratesServerClientIdWhenRequestHasNone() {
        when(turnMapper.selectObjs(any())).thenReturn(List.of());
        when(turnMapper.insert(any(AiTurnEntity.class))).thenReturn(1);

        AiTurnService.BeginTurnOutcome outcome = service.beginTurn("c-1", null);

        assertThat(outcome.turn().getClientTurnId()).startsWith("srv-");
    }

    @Test
    void casTransitionsReportWhetherTheRowWasMoved() {
        when(turnMapper.update(isNull(), any())).thenReturn(1);
        assertThat(service.advanceToGenerating("t-1", "t-1")).isTrue();
        assertThat(service.completeTurn("t-1")).isTrue();
        assertThat(service.failTurn("t-1", AiTurnService.STATUS_FAILED_RETRYABLE, "TURN_TIMEOUT")).isTrue();

        when(turnMapper.update(isNull(), any())).thenReturn(0);
        assertThat(service.advanceToGenerating("t-1", "t-1")).isFalse();
        assertThat(service.completeTurn("t-1")).isFalse();
        assertThat(service.failTurn("t-1", AiTurnService.STATUS_FAILED_RETRYABLE, "TURN_TIMEOUT")).isFalse();
    }

    @Test
    void refuseTurnReportsWhetherTheRowWasMoved() {
        when(turnMapper.update(isNull(), any())).thenReturn(1);
        assertThat(service.refuseTurn("t-1")).isTrue();

        when(turnMapper.update(isNull(), any())).thenReturn(0);
        assertThat(service.refuseTurn("t-1")).isFalse();
    }

    @Test
    void refusedStatusIsTerminal() {
        assertThat(AiTurnService.isTerminal(AiTurnService.STATUS_REFUSED)).isTrue();
    }

    private static AiTurnEntity turn(String id, String conversationId, String clientTurnId, long turnSeq, String status) {
        AiTurnEntity turn = new AiTurnEntity();
        turn.setId(id);
        turn.setConversationId(conversationId);
        turn.setClientTurnId(clientTurnId);
        turn.setTurnSeq(turnSeq);
        turn.setStatus(status);
        turn.setStateVersion(0L);
        turn.setCreatedAt(LocalDateTime.now());
        return turn;
    }
}
