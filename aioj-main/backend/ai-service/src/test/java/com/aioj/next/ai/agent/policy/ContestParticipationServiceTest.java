package com.aioj.next.ai.agent.policy;

import com.aioj.next.ai.domain.ProblemServiceClient;
import com.aioj.next.common.error.DomainException;
import com.aioj.next.contract.contest.RunningContestParticipation;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.argThat;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class ContestParticipationServiceTest {

    private final ProblemServiceClient problemServiceClient = mock(ProblemServiceClient.class);
    private final GuardDecisionRecorder recorder = mock(GuardDecisionRecorder.class);
    private final ContestParticipationService service = new ContestParticipationService(
            problemServiceClient, recorder, new ObjectMapper());

    @Test
    void noRunningRunsMeansNonParticipantPass() {
        when(problemServiceClient.runningParticipationsStrict(7L)).thenReturn(List.of());

        ContestParticipationService.ParticipationView view = service.evaluate(7L, "t-1", "c-1");

        assertThat(view.status()).isEqualTo(ParticipantStatus.NON_PARTICIPANT);
        assertThat(view.isParticipant()).isFalse();
        assertThat(view.attributed()).isNull();
        verify(recorder).record(eq("t-1"), eq(7L), eq("c-1"),
                eq(GuardLayer.L1_PARTICIPANT), eq(GuardDecision.PASS),
                eq(ContestParticipationService.REASON_NON_PARTICIPANT), any(), eq(false), any());
    }

    @Test
    void inProgressRunYieldsActiveParticipant() {
        RunningContestParticipation run = run(7701L, -3600, 3600);
        when(problemServiceClient.runningParticipationsStrict(7L)).thenReturn(List.of(run));

        ContestParticipationService.ParticipationView view = service.evaluate(7L, "t-1", "c-1");

        assertThat(view.status()).isEqualTo(ParticipantStatus.PARTICIPANT_ACTIVE);
        assertThat(view.attributed().contestRunId()).isEqualTo(7701L);
        verify(recorder).record(eq("t-1"), eq(7L), eq("c-1"),
                eq(GuardLayer.L1_PARTICIPANT), eq(GuardDecision.CONSTRAIN),
                eq(ContestParticipationService.REASON_PARTICIPANT), any(), eq(false), any());
    }

    @Test
    void graceTailRunYieldsGraceParticipant() {
        RunningContestParticipation grace = run(7702L, -7200, -600);
        when(problemServiceClient.runningParticipationsStrict(7L)).thenReturn(List.of(grace));

        ContestParticipationService.ParticipationView view = service.evaluate(7L, "t-1", "c-1");

        assertThat(view.status()).isEqualTo(ParticipantStatus.PARTICIPANT_GRACE);
        assertThat(view.attributed().contestRunId()).isEqualTo(7702L);
    }

    @Test
    void inProgressRunWinsAttributionOverGraceTail() {
        RunningContestParticipation grace = run(7702L, -7200, -600);
        RunningContestParticipation active = run(7701L, -3600, 3600);
        when(problemServiceClient.runningParticipationsStrict(7L)).thenReturn(List.of(grace, active));

        ContestParticipationService.ParticipationView view = service.evaluate(7L, "t-1", "c-1");

        assertThat(view.status()).isEqualTo(ParticipantStatus.PARTICIPANT_ACTIVE);
        assertThat(view.attributed().contestRunId()).isEqualTo(7701L);
    }

    @Test
    void lookupFailureIsFailClosedAndAuditedDegraded() {
        when(problemServiceClient.runningParticipationsStrict(7L)).thenThrow(new RuntimeException("connection refused"));

        assertThatThrownBy(() -> service.evaluate(7L, "t-1", "c-1"))
                .isInstanceOf(DomainException.class)
                .hasMessageContaining("比赛状态确认暂时不可用");

        verify(recorder).record(eq("t-1"), eq(7L), eq("c-1"),
                eq(GuardLayer.L1_PARTICIPANT), eq(GuardDecision.BLOCK),
                eq(ContestParticipationService.REASON_LOOKUP_FAILED), any(), eq(true), any());
    }

    @Test
    void evaluateFreshBypassesCachedClientAndMarksRecheckDetail() {
        RunningContestParticipation run = run(7701L, -3600, 3600);
        when(problemServiceClient.runningParticipationsFresh(7L)).thenReturn(List.of(run));

        ContestParticipationService.ParticipationView view = service.evaluateFresh(7L, "t-1", "c-1");

        // P3-6: the pre-return recheck reads current state, not the turn-start cache.
        assertThat(view.status()).isEqualTo(ParticipantStatus.PARTICIPANT_ACTIVE);
        verify(problemServiceClient).runningParticipationsFresh(7L);
        verify(problemServiceClient, never()).runningParticipationsStrict(any());
        verify(recorder).record(eq("t-1"), eq(7L), eq("c-1"),
                eq(GuardLayer.L1_PARTICIPANT), eq(GuardDecision.CONSTRAIN),
                eq(ContestParticipationService.REASON_PARTICIPANT),
                argThat(detail -> detail.path("recheck").asBoolean()), eq(false), any());
    }

    @Test
    void evaluateFreshFailureIsFailClosedLikeEvaluate() {
        when(problemServiceClient.runningParticipationsFresh(7L)).thenThrow(new RuntimeException("connection refused"));

        assertThatThrownBy(() -> service.evaluateFresh(7L, "t-1", "c-1"))
                .isInstanceOf(DomainException.class)
                .hasMessageContaining("比赛状态确认暂时不可用");

        verify(recorder).record(eq("t-1"), eq(7L), eq("c-1"),
                eq(GuardLayer.L1_PARTICIPANT), eq(GuardDecision.BLOCK),
                eq(ContestParticipationService.REASON_LOOKUP_FAILED),
                argThat(detail -> detail.path("recheck").asBoolean()), eq(true), any());
    }

    @Test
    void evaluateKeepsCachedClientWithoutRecheckMarker() {
        RunningContestParticipation run = run(7701L, -3600, 3600);
        when(problemServiceClient.runningParticipationsStrict(7L)).thenReturn(List.of(run));

        service.evaluate(7L, "t-1", "c-1");

        verify(problemServiceClient, never()).runningParticipationsFresh(any());
        verify(recorder).record(eq("t-1"), eq(7L), eq("c-1"),
                eq(GuardLayer.L1_PARTICIPANT), eq(GuardDecision.CONSTRAIN),
                eq(ContestParticipationService.REASON_PARTICIPANT),
                argThat(detail -> !detail.has("recheck")), eq(false), any());
    }

    private RunningContestParticipation run(long runId, long startOffsetSeconds, long endOffsetSeconds) {
        return new RunningContestParticipation(5501L, runId,
                Instant.now().plusSeconds(startOffsetSeconds), Instant.now().plusSeconds(endOffsetSeconds));
    }
}
