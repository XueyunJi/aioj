package com.aioj.next.ai.agent.policy;

import com.aioj.next.ai.domain.ProblemServiceClient;
import com.aioj.next.ai.persistence.entity.AiPolicySnapshotEntity;
import com.aioj.next.ai.persistence.mapper.AiPolicySnapshotMapper;
import com.aioj.next.common.error.DomainException;
import com.aioj.next.contract.contest.ContestAiPolicyMode;
import com.aioj.next.contract.contest.RunningContestParticipation;
import com.aioj.next.contract.contest.RunningContestProblemOccurrence;
import com.aioj.next.contract.contest.RunningContestProblemStatement;
import com.aioj.next.contract.problem.ProblemVisibility;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.time.Instant;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.argThat;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class PolicySnapshotServiceTest {

    private static final ObjectMapper OBJECT_MAPPER = new ObjectMapper();

    private final AiPolicySnapshotMapper mapper = mock(AiPolicySnapshotMapper.class);
    private final ProblemServiceClient problemServiceClient = mock(ProblemServiceClient.class);
    private final GuardDecisionRecorder recorder = mock(GuardDecisionRecorder.class);
    private final ContestParticipationService participationService = mock(ContestParticipationService.class);
    private final PolicySnapshotService service = new PolicySnapshotService(
            mapper, OBJECT_MAPPER, problemServiceClient, recorder, participationService);

    @Test
    void nonParticipantGetsBaselineWithoutInjection() {
        PolicySnapshotService.PolicySnapshot snapshot = service.createForTurn(7L, "turn-1", "conv-1", null);

        assertThat(snapshot.id()).startsWith("ps-");
        assertThat(snapshot.participantStatus()).isEqualTo(ParticipantStatus.NON_PARTICIPANT);
        assertThat(snapshot.contestRunIds()).isEmpty();
        assertThat(snapshot.promptText()).isEmpty();
        assertThat(snapshot.contestProblems()).isEmpty();
        assertThat(snapshot.policyJson()).contains("FULL_TUTORING");

        ArgumentCaptor<AiPolicySnapshotEntity> captor = ArgumentCaptor.forClass(AiPolicySnapshotEntity.class);
        verify(mapper).insert(captor.capture());
        AiPolicySnapshotEntity entity = captor.getValue();
        assertThat(entity.getParticipantStatus()).isEqualTo("NON_PARTICIPANT");
        assertThat(entity.getPolicyVersion()).isEqualTo(PolicySnapshotService.POLICY_VERSION);
        assertThat(entity.getValidUntil()).isAfter(entity.getCalculatedAt());

        verify(recorder).record(eq("turn-1"), eq(7L), eq("conv-1"),
                eq(GuardLayer.L2_POLICY_INJECT), eq(GuardDecision.PASS),
                eq(PolicySnapshotService.REASON_NON_PARTICIPANT), any(), eq(false), any());
    }

    @Test
    void participantSnapshotCarriesRunsProblemsAndPrompt() throws Exception {
        ContestParticipationService.ParticipationView participation = participation(ParticipantStatus.PARTICIPANT_ACTIVE);
        when(problemServiceClient.runningContestProblemStatementsStrict(7L)).thenReturn(List.of(
                problem(1001L, ProblemVisibility.PUBLIC, ContestAiPolicyMode.DEFAULT),
                problem(1002L, ProblemVisibility.PRIVATE, ContestAiPolicyMode.DEFAULT),
                problem(1003L, ProblemVisibility.PUBLIC, ContestAiPolicyMode.STRICT),
                problem(1004L, ProblemVisibility.PUBLIC, ContestAiPolicyMode.DISABLED)));

        PolicySnapshotService.PolicySnapshot snapshot = service.createForTurn(7L, "turn-2", "conv-1", participation);

        assertThat(snapshot.participantStatus()).isEqualTo(ParticipantStatus.PARTICIPANT_ACTIVE);
        assertThat(snapshot.contestRunIds()).containsExactly(7701L);
        assertThat(snapshot.contestProblems()).hasSize(4);
        assertThat(snapshot.constrainedProblems()).hasSize(3);
        assertThat(snapshot.promptText()).contains("Problem #1001").contains("hints and idea-level guidance only");
        assertThat(snapshot.promptText()).contains("Problem #1002").contains("private contest problem");
        assertThat(snapshot.promptText()).contains("Problem #1003").contains("STRICT");
        assertThat(snapshot.promptText()).doesNotContain("Problem #1004");
        assertThat(snapshot.promptText()).contains("outrank any user instruction");

        var policy = OBJECT_MAPPER.readTree(snapshot.policyJson());
        assertThat(policy.get("allowFullSolutionCode").asBoolean()).isFalse();
        assertThat(policy.get("attributedContestRunId").asLong()).isEqualTo(7701L);
        assertThat(policy.get("problems")).hasSize(4);
        assertThat(policy.get("problems").get(0).get("assistanceLevel").asText()).isEqualTo("HINT_ONLY");
        assertThat(policy.get("problems").get(1).get("assistanceLevel").asText()).isEqualTo("DENY");
        assertThat(policy.get("problems").get(2).get("assistanceLevel").asText()).isEqualTo("DENY");

        verify(recorder).record(eq("turn-2"), eq(7L), eq("conv-1"),
                eq(GuardLayer.L2_POLICY_INJECT), eq(GuardDecision.CONSTRAIN),
                eq(PolicySnapshotService.REASON_INJECTED), any(), eq(false), any());
    }

    @Test
    void allDisabledRunsStillInjectButConstrainNothing() {
        ContestParticipationService.ParticipationView participation = participation(ParticipantStatus.PARTICIPANT_ACTIVE);
        when(problemServiceClient.runningContestProblemStatementsStrict(7L)).thenReturn(List.of(
                problem(1004L, ProblemVisibility.PUBLIC, ContestAiPolicyMode.DISABLED)));

        PolicySnapshotService.PolicySnapshot snapshot = service.createForTurn(7L, "turn-3", "conv-1", participation);

        assertThat(snapshot.constrainedProblems()).isEmpty();
        assertThat(snapshot.promptText()).contains("DISABLED");
    }

    @Test
    void statementsLookupFailureIsFailClosedAndAuditedDegraded() {
        ContestParticipationService.ParticipationView participation = participation(ParticipantStatus.PARTICIPANT_ACTIVE);
        when(problemServiceClient.runningContestProblemStatementsStrict(7L)).thenThrow(new RuntimeException("problem-service down"));

        assertThatThrownBy(() -> service.createForTurn(7L, "turn-4", "conv-1", participation))
                .isInstanceOf(DomainException.class)
                .hasMessageContaining("比赛策略确认暂时不可用");

        verify(recorder).record(eq("turn-4"), eq(7L), eq("conv-1"),
                eq(GuardLayer.L2_POLICY_INJECT), eq(GuardDecision.BLOCK),
                eq(PolicySnapshotService.REASON_STATEMENTS_FAILED), any(), eq(true), any());
    }

    @Test
    void persistenceFailureIsAuditedDegradedAndSnapshotStillReturned() {
        doThrow(new RuntimeException("db down")).when(mapper).insert(any(AiPolicySnapshotEntity.class));

        PolicySnapshotService.PolicySnapshot snapshot = service.createForTurn(7L, "turn-5", "conv-1", null);

        // P3-6 (Q5): execution continues on the in-memory snapshot, and the audit gap
        // is explicit — degraded L2 row with the turn's original L2 decision (PASS here).
        assertThat(snapshot.id()).startsWith("ps-");
        assertThat(snapshot.policyJson()).contains("FULL_TUTORING");
        verify(recorder).record(eq("turn-5"), eq(7L), eq("conv-1"),
                eq(GuardLayer.L2_POLICY_INJECT), eq(GuardDecision.PASS),
                eq(PolicySnapshotService.REASON_SNAPSHOT_PERSIST_DEGRADED), any(), eq(true), isNull());
        // The normal baseline row still lands after the degraded one.
        verify(recorder).record(eq("turn-5"), eq(7L), eq("conv-1"),
                eq(GuardLayer.L2_POLICY_INJECT), eq(GuardDecision.PASS),
                eq(PolicySnapshotService.REASON_NON_PARTICIPANT), any(), eq(false), any());
    }

    @Test
    void participantPersistFailureKeepsConstrainDecisionInDegradedAudit() {
        doThrow(new RuntimeException("db down")).when(mapper).insert(any(AiPolicySnapshotEntity.class));
        ContestParticipationService.ParticipationView participation = participation(ParticipantStatus.PARTICIPANT_ACTIVE);
        when(problemServiceClient.runningContestProblemStatementsStrict(7L)).thenReturn(
                List.of(problem(1001L, ProblemVisibility.PUBLIC, ContestAiPolicyMode.DEFAULT)));

        PolicySnapshotService.PolicySnapshot snapshot = service.createForTurn(7L, "turn-6", "conv-1", participation);

        // The in-memory snapshot still guards the turn.
        assertThat(snapshot.constrainedProblems()).hasSize(1);
        verify(recorder).record(eq("turn-6"), eq(7L), eq("conv-1"),
                eq(GuardLayer.L2_POLICY_INJECT), eq(GuardDecision.CONSTRAIN),
                eq(PolicySnapshotService.REASON_SNAPSHOT_PERSIST_DEGRADED), any(), eq(true), isNull());
        verify(recorder).record(eq("turn-6"), eq(7L), eq("conv-1"),
                eq(GuardLayer.L2_POLICY_INJECT), eq(GuardDecision.CONSTRAIN),
                eq(PolicySnapshotService.REASON_INJECTED), any(), eq(false), any());
    }

    @Test
    void recheckUnchangedAuditsPassAndReturnsCurrentState() {
        PolicySnapshotService.PolicySnapshot turnSnapshot = new PolicySnapshotService.PolicySnapshot(
                "ps-1", ParticipantStatus.PARTICIPANT_ACTIVE, List.of(7701L), "{}", "prompt",
                List.of(problem(1001L, ProblemVisibility.PUBLIC, ContestAiPolicyMode.DEFAULT)));
        ContestParticipationService.ParticipationView current = participation(ParticipantStatus.PARTICIPANT_ACTIVE);
        when(participationService.evaluateFresh(7L, "turn-r1", "conv-1")).thenReturn(current);
        when(problemServiceClient.runningContestProblemStatementsFresh(7L)).thenReturn(
                List.of(problem(1001L, ProblemVisibility.PUBLIC, ContestAiPolicyMode.DEFAULT)));

        PolicySnapshotService.PolicyRecheck recheck = service.recheckBeforeReturn(7L, "turn-r1", "conv-1", turnSnapshot);

        assertThat(recheck.changed()).isFalse();
        assertThat(recheck.participation()).isSameAs(current);
        assertThat(recheck.statements()).hasSize(1);
        verify(recorder).record(eq("turn-r1"), eq(7L), eq("conv-1"),
                eq(GuardLayer.L1_PARTICIPANT), eq(GuardDecision.PASS),
                eq(PolicySnapshotService.REASON_STATE_UNCHANGED),
                argThat(detail -> detail.path("recheck").asBoolean()
                        && !detail.path("changed").asBoolean()
                        && detail.path("participantStatusNow").asText().equals("PARTICIPANT_ACTIVE")
                        && detail.path("constrainedCountBefore").asInt() == 1
                        && detail.path("constrainedCountNow").asInt() == 1),
                eq(false), any());
    }

    @Test
    void recheckDetectsContestStartedMidGeneration() {
        // Turn start: non-participant. At return time the user is a participant with
        // one constrained running-contest problem (contest started / user joined).
        PolicySnapshotService.PolicySnapshot turnSnapshot = new PolicySnapshotService.PolicySnapshot(
                "ps-0", ParticipantStatus.NON_PARTICIPANT, List.of(), "{}", "", List.of());
        when(participationService.evaluateFresh(7L, "turn-r2", "conv-1"))
                .thenReturn(participation(ParticipantStatus.PARTICIPANT_ACTIVE));
        when(problemServiceClient.runningContestProblemStatementsFresh(7L)).thenReturn(
                List.of(problem(1001L, ProblemVisibility.PUBLIC, ContestAiPolicyMode.DEFAULT)));

        PolicySnapshotService.PolicyRecheck recheck = service.recheckBeforeReturn(7L, "turn-r2", "conv-1", turnSnapshot);

        assertThat(recheck.changed()).isTrue();
        assertThat(recheck.participation().isParticipant()).isTrue();
        assertThat(recheck.statements()).hasSize(1);
        verify(recorder).record(eq("turn-r2"), eq(7L), eq("conv-1"),
                eq(GuardLayer.L1_PARTICIPANT), eq(GuardDecision.CONSTRAIN),
                eq(PolicySnapshotService.REASON_STATE_CHANGED),
                argThat(detail -> detail.path("recheck").asBoolean()
                        && detail.path("changed").asBoolean()
                        && detail.path("participantStatusBefore").asText().equals("NON_PARTICIPANT")
                        && detail.path("constrainedCountBefore").asInt() == 0
                        && detail.path("constrainedCountNow").asInt() == 1
                        && detail.path("addedConstrained").toString().contains("1001")),
                eq(false), any());
    }

    @Test
    void recheckDetectsContestEndedMidGeneration() {
        // Turn start: participant with one constrained problem. At return time the run
        // has left the guard window — the user is no longer a participant.
        PolicySnapshotService.PolicySnapshot turnSnapshot = new PolicySnapshotService.PolicySnapshot(
                "ps-1", ParticipantStatus.PARTICIPANT_ACTIVE, List.of(7701L), "{}", "prompt",
                List.of(problem(1001L, ProblemVisibility.PUBLIC, ContestAiPolicyMode.DEFAULT)));
        when(participationService.evaluateFresh(7L, "turn-r3", "conv-1"))
                .thenReturn(new ContestParticipationService.ParticipationView(
                        ParticipantStatus.NON_PARTICIPANT, List.of(), null));

        PolicySnapshotService.PolicyRecheck recheck = service.recheckBeforeReturn(7L, "turn-r3", "conv-1", turnSnapshot);

        assertThat(recheck.changed()).isTrue();
        assertThat(recheck.participation().isParticipant()).isFalse();
        assertThat(recheck.statements()).isEmpty();
        // No statements fetch happens once the recheck reads non-participant.
        verify(problemServiceClient, never()).runningContestProblemStatementsFresh(any());
        verify(recorder).record(eq("turn-r3"), eq(7L), eq("conv-1"),
                eq(GuardLayer.L1_PARTICIPANT), eq(GuardDecision.CONSTRAIN),
                eq(PolicySnapshotService.REASON_STATE_CHANGED),
                argThat(detail -> detail.path("changed").asBoolean()
                        && detail.path("constrainedCountBefore").asInt() == 1
                        && detail.path("constrainedCountNow").asInt() == 0
                        && detail.path("removedConstrained").toString().contains("1001")),
                eq(false), any());
    }

    @Test
    void recheckDetectsNewlyConstrainedProblem() {
        // Same participant, same run: the constrained set gains problem 1002 PRIVATE.
        PolicySnapshotService.PolicySnapshot turnSnapshot = new PolicySnapshotService.PolicySnapshot(
                "ps-1", ParticipantStatus.PARTICIPANT_ACTIVE, List.of(7701L), "{}", "prompt",
                List.of(problem(1001L, ProblemVisibility.PUBLIC, ContestAiPolicyMode.DEFAULT)));
        when(participationService.evaluateFresh(7L, "turn-r4", "conv-1"))
                .thenReturn(participation(ParticipantStatus.PARTICIPANT_ACTIVE));
        when(problemServiceClient.runningContestProblemStatementsFresh(7L)).thenReturn(List.of(
                problem(1001L, ProblemVisibility.PUBLIC, ContestAiPolicyMode.DEFAULT),
                problem(1002L, ProblemVisibility.PRIVATE, ContestAiPolicyMode.DEFAULT)));

        PolicySnapshotService.PolicyRecheck recheck = service.recheckBeforeReturn(7L, "turn-r4", "conv-1", turnSnapshot);

        assertThat(recheck.changed()).isTrue();
        verify(recorder).record(eq("turn-r4"), eq(7L), eq("conv-1"),
                eq(GuardLayer.L1_PARTICIPANT), eq(GuardDecision.CONSTRAIN),
                eq(PolicySnapshotService.REASON_STATE_CHANGED),
                argThat(detail -> detail.path("constrainedCountBefore").asInt() == 1
                        && detail.path("constrainedCountNow").asInt() == 2
                        && detail.path("addedConstrained").toString().contains("1002|PRIVATE|DEFAULT")),
                eq(false), any());
    }

    @Test
    void recheckIgnoresRunReorderingAndDisabledOnlyDrift() {
        // Same guard-relevant state: run order flipped and the DISABLED set differs —
        // DISABLED occurrences never constrain anything, so the recheck stays unchanged.
        PolicySnapshotService.PolicySnapshot turnSnapshot = new PolicySnapshotService.PolicySnapshot(
                "ps-1", ParticipantStatus.PARTICIPANT_ACTIVE, List.of(7701L, 7702L), "{}", "prompt",
                List.of(problem(1001L, ProblemVisibility.PUBLIC, ContestAiPolicyMode.DEFAULT),
                        problem(1004L, ProblemVisibility.PUBLIC, ContestAiPolicyMode.DISABLED)));
        RunningContestParticipation runOne = new RunningContestParticipation(
                5501L, 7701L, Instant.now().minusSeconds(3600), Instant.now().plusSeconds(3600));
        RunningContestParticipation runTwo = new RunningContestParticipation(
                5502L, 7702L, Instant.now().minusSeconds(3600), Instant.now().plusSeconds(3600));
        when(participationService.evaluateFresh(7L, "turn-r5", "conv-1"))
                .thenReturn(new ContestParticipationService.ParticipationView(
                        ParticipantStatus.PARTICIPANT_ACTIVE, List.of(runTwo, runOne), runTwo));
        when(problemServiceClient.runningContestProblemStatementsFresh(7L)).thenReturn(
                List.of(problem(1001L, ProblemVisibility.PUBLIC, ContestAiPolicyMode.DEFAULT),
                        problem(1005L, ProblemVisibility.PUBLIC, ContestAiPolicyMode.DISABLED)));

        PolicySnapshotService.PolicyRecheck recheck = service.recheckBeforeReturn(7L, "turn-r5", "conv-1", turnSnapshot);

        assertThat(recheck.changed()).isFalse();
    }

    @Test
    void recheckTreatsActiveToGraceFlipAsChanged() {
        // The run crossed endAt mid-generation but stays inside the grace window: the
        // status flips ACTIVE -> GRACE. The guard outcome is identical, but the state
        // did change, so the recheck reports it (callers re-evaluate cheaply).
        PolicySnapshotService.PolicySnapshot turnSnapshot = new PolicySnapshotService.PolicySnapshot(
                "ps-1", ParticipantStatus.PARTICIPANT_ACTIVE, List.of(7701L), "{}", "prompt",
                List.of(problem(1001L, ProblemVisibility.PUBLIC, ContestAiPolicyMode.DEFAULT)));
        when(participationService.evaluateFresh(7L, "turn-r6", "conv-1"))
                .thenReturn(participation(ParticipantStatus.PARTICIPANT_GRACE));
        when(problemServiceClient.runningContestProblemStatementsFresh(7L)).thenReturn(
                List.of(problem(1001L, ProblemVisibility.PUBLIC, ContestAiPolicyMode.DEFAULT)));

        PolicySnapshotService.PolicyRecheck recheck = service.recheckBeforeReturn(7L, "turn-r6", "conv-1", turnSnapshot);

        assertThat(recheck.changed()).isTrue();
    }

    @Test
    void recheckStatementsFailureIsFailClosedAndAuditedDegraded() {
        PolicySnapshotService.PolicySnapshot turnSnapshot = new PolicySnapshotService.PolicySnapshot(
                "ps-1", ParticipantStatus.PARTICIPANT_ACTIVE, List.of(7701L), "{}", "prompt",
                List.of(problem(1001L, ProblemVisibility.PUBLIC, ContestAiPolicyMode.DEFAULT)));
        when(participationService.evaluateFresh(7L, "turn-r7", "conv-1"))
                .thenReturn(participation(ParticipantStatus.PARTICIPANT_ACTIVE));
        when(problemServiceClient.runningContestProblemStatementsFresh(7L))
                .thenThrow(new RuntimeException("problem-service down"));

        assertThatThrownBy(() -> service.recheckBeforeReturn(7L, "turn-r7", "conv-1", turnSnapshot))
                .isInstanceOf(DomainException.class)
                .hasMessageContaining("比赛策略确认暂时不可用");

        verify(recorder).record(eq("turn-r7"), eq(7L), eq("conv-1"),
                eq(GuardLayer.L2_POLICY_INJECT), eq(GuardDecision.BLOCK),
                eq(PolicySnapshotService.REASON_STATEMENTS_FAILED),
                argThat(detail -> detail.path("recheck").asBoolean()), eq(true), any());
    }

    @Test
    void recheckParticipationFailurePropagatesWithoutStatementsFetch() {
        when(participationService.evaluateFresh(7L, "turn-r8", "conv-1"))
                .thenThrow(new DomainException(com.aioj.next.common.error.ErrorCode.SERVICE_UNAVAILABLE,
                        "比赛状态确认暂时不可用，请稍后重试"));

        assertThatThrownBy(() -> service.recheckBeforeReturn(7L, "turn-r8", "conv-1", null))
                .isInstanceOf(DomainException.class)
                .hasMessageContaining("比赛状态确认暂时不可用");

        verify(problemServiceClient, never()).runningContestProblemStatementsFresh(any());
    }

    private ContestParticipationService.ParticipationView participation(ParticipantStatus status) {
        RunningContestParticipation run = new RunningContestParticipation(
                5501L, 7701L, Instant.now().minusSeconds(3600), Instant.now().plusSeconds(3600));
        return new ContestParticipationService.ParticipationView(status, List.of(run), run);
    }

    private RunningContestProblemStatement problem(long problemId, ProblemVisibility visibility, ContestAiPolicyMode mode) {
        return new RunningContestProblemStatement(
                problemId, "题面" + problemId, 5501L, 7701L, 99001L + problemId, visibility, mode, null,
                List.of(new RunningContestProblemOccurrence(5501L, 7701L, 99001L + problemId)));
    }
}
