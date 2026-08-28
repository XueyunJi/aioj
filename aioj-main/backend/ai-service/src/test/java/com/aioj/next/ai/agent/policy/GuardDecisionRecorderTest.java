package com.aioj.next.ai.agent.policy;

import com.aioj.next.ai.persistence.entity.AiGuardDecisionEntity;
import com.aioj.next.ai.persistence.mapper.AiGuardDecisionMapper;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;

class GuardDecisionRecorderTest {

    private final AiGuardDecisionMapper mapper = mock(AiGuardDecisionMapper.class);
    private final GuardDecisionRecorder recorder = new GuardDecisionRecorder(mapper, new ObjectMapper());

    @Test
    void recordPersistsAllFields() {
        recorder.record("t-1", 7L, "c-1",
                GuardLayer.L3_FINGERPRINT_MSG, GuardDecision.REFUSE,
                List.of(new GuardDecisionRecorder.MatchedProblemRef(1001L, 5501L, 7701L, 99001L, "PRIVATE", "DEFAULT")),
                "private_contest_problem", new ObjectMapper().createObjectNode().put("similarity", 0.91),
                false, 12);

        ArgumentCaptor<AiGuardDecisionEntity> captor = ArgumentCaptor.forClass(AiGuardDecisionEntity.class);
        verify(mapper).insert(captor.capture());
        AiGuardDecisionEntity entity = captor.getValue();
        assertThat(entity.getTurnId()).isEqualTo("t-1");
        assertThat(entity.getUserId()).isEqualTo(7L);
        assertThat(entity.getConversationId()).isEqualTo("c-1");
        assertThat(entity.getLayer()).isEqualTo("L3_FINGERPRINT_MSG");
        assertThat(entity.getDecision()).isEqualTo("REFUSE");
        assertThat(entity.getMatchedProblemRefs()).contains("1001").contains("PRIVATE");
        assertThat(entity.getContestRunId()).isEqualTo(7701L);
        assertThat(entity.getReasonCode()).isEqualTo("private_contest_problem");
        assertThat(entity.getDetailJson()).contains("0.91");
        assertThat(entity.getDegraded()).isFalse();
        assertThat(entity.getLatencyMs()).isEqualTo(12);
        assertThat(entity.getCreatedAt()).isNotNull();
    }

    @Test
    void emptyMatchesPersistNullRefs() {
        recorder.record("t-2", 7L, "c-1",
                GuardLayer.L1_PARTICIPANT, GuardDecision.PASS, "non_participant", null, false, null);

        ArgumentCaptor<AiGuardDecisionEntity> captor = ArgumentCaptor.forClass(AiGuardDecisionEntity.class);
        verify(mapper).insert(captor.capture());
        assertThat(captor.getValue().getMatchedProblemRefs()).isNull();
        assertThat(captor.getValue().getDetailJson()).isNull();
        assertThat(captor.getValue().getContestRunId()).isNull();
    }

    @Test
    void contestRunIdComesFromFirstNonNullMatchedRef() {
        recorder.record("t-4", 7L, "c-1",
                GuardLayer.L3_FINGERPRINT_MSG, GuardDecision.CONSTRAIN,
                List.of(
                        new GuardDecisionRecorder.MatchedProblemRef(1001L, 5501L, null, 99001L, "PUBLIC", "DEFAULT"),
                        new GuardDecisionRecorder.MatchedProblemRef(1002L, 5502L, 8802L, 99002L, "PRIVATE", "STRICT")),
                "contest_problem", null, false, 5);

        ArgumentCaptor<AiGuardDecisionEntity> captor = ArgumentCaptor.forClass(AiGuardDecisionEntity.class);
        verify(mapper).insert(captor.capture());
        assertThat(captor.getValue().getContestRunId()).isEqualTo(8802L);
    }

    @Test
    void persistenceFailureIsSwallowed() {
        doThrow(new RuntimeException("db down")).when(mapper).insert(any(AiGuardDecisionEntity.class));

        assertThatCode(() -> recorder.record("t-3", 7L, "c-1",
                GuardLayer.L2_POLICY_INJECT, GuardDecision.PASS, "reason", null, false, null))
                .doesNotThrowAnyException();
    }
}
