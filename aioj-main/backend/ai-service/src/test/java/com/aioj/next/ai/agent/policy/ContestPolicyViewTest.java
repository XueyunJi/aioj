package com.aioj.next.ai.agent.policy;

import com.aioj.next.contract.contest.ContestAiPolicyMode;
import com.aioj.next.contract.contest.RunningContestProblemOccurrence;
import com.aioj.next.contract.contest.RunningContestProblemStatement;
import com.aioj.next.contract.problem.ProblemVisibility;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class ContestPolicyViewTest {

    @Test
    void nullOrBaselineSnapshotYieldsNonParticipantView() {
        assertThat(ContestPolicyView.from(null).isParticipant()).isFalse();
        assertThat(ContestPolicyView.from(null).contestProblems()).isEmpty();

        PolicySnapshotService.PolicySnapshot baseline =
                new PolicySnapshotService.PolicySnapshot("ps-1", ParticipantStatus.NON_PARTICIPANT,
                        List.of(), "{}", "", List.of());
        assertThat(ContestPolicyView.from(baseline).isParticipant()).isFalse();

        PolicySnapshotService.PolicySnapshot nullStatus =
                new PolicySnapshotService.PolicySnapshot("ps-2", null, List.of(), "{}", "", List.of());
        assertThat(ContestPolicyView.from(nullStatus).isParticipant()).isFalse();
    }

    @Test
    void participantSnapshotProjectsEveryContestProblemIncludingDisabled() {
        RunningContestProblemStatement publicDefault = new RunningContestProblemStatement(
                1001L, "公开题面", 5501L, 7701L, 99001L, ProblemVisibility.PUBLIC,
                ContestAiPolicyMode.DEFAULT, "notes",
                List.of(new RunningContestProblemOccurrence(5501L, 7701L, 99001L)));
        RunningContestProblemStatement privateDisabled = new RunningContestProblemStatement(
                1002L, "私有放开", 5501L, 7701L, 99002L, ProblemVisibility.PRIVATE,
                ContestAiPolicyMode.DISABLED, null,
                List.of(new RunningContestProblemOccurrence(5501L, 7701L, 99002L)));
        PolicySnapshotService.PolicySnapshot snapshot = new PolicySnapshotService.PolicySnapshot(
                "ps-3", ParticipantStatus.PARTICIPANT_ACTIVE, List.of(7701L), "{}", "prompt",
                List.of(publicDefault, privateDisabled));

        ContestPolicyView view = ContestPolicyView.from(snapshot);

        assertThat(view.isParticipant()).isTrue();
        assertThat(view.participantStatus()).isEqualTo(ParticipantStatus.PARTICIPANT_ACTIVE);
        assertThat(view.contestProblems()).containsOnlyKeys(1001L, 1002L);
        ContestPolicyView.ContestProblemPolicy projected = view.problem(1001L);
        assertThat(projected.visibility()).isEqualTo(ProblemVisibility.PUBLIC);
        assertThat(projected.aiPolicyMode()).isEqualTo(ContestAiPolicyMode.DEFAULT);
        assertThat(projected.aiPolicyNotes()).isEqualTo("notes");
        assertThat(projected.statement()).isEqualTo("公开题面");
        assertThat(projected.firstOccurrence().contestRunId()).isEqualTo(7701L);
        assertThat(view.problem(1002L).aiPolicyMode()).isEqualTo(ContestAiPolicyMode.DISABLED);
        assertThat(view.problem(9999L)).isNull();
        // P3-4: the guard candidate set excludes DISABLED, mirroring the snapshot semantics.
        assertThat(view.constrainedProblems())
                .extracting(ContestPolicyView.ContestProblemPolicy::problemId)
                .containsExactly(1001L);
    }

    @Test
    void snapshotWithNullProblemListProjectsEmptyMap() {
        PolicySnapshotService.PolicySnapshot snapshot = new PolicySnapshotService.PolicySnapshot(
                "ps-4", ParticipantStatus.PARTICIPANT_GRACE, List.of(7701L), "{}", "prompt", null);

        ContestPolicyView view = ContestPolicyView.from(snapshot);

        assertThat(view.isParticipant()).isTrue();
        assertThat(view.contestProblems()).isEmpty();
    }
}
