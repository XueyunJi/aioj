package com.aioj.next.problem.domain;

import com.aioj.next.contract.contest.ContestAiPolicyRequest;
import com.aioj.next.contract.contest.ContestRunStatus;
import com.aioj.next.contract.contest.ContestStatus;
import com.aioj.next.contract.problem.ProblemVisibility;
import com.aioj.next.problem.persistence.entity.ContestEntity;
import com.aioj.next.problem.persistence.entity.ContestRunEntity;
import com.aioj.next.problem.persistence.entity.ContestRunProblemSnapshotEntity;
import com.aioj.next.problem.persistence.mapper.ContestMapper;
import com.aioj.next.problem.persistence.mapper.ContestParticipantMapper;
import com.aioj.next.problem.persistence.mapper.ContestRunMapper;
import com.aioj.next.problem.persistence.mapper.ContestRunProblemSnapshotMapper;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class ContestAiPolicyServiceTest {
    private final ContestMapper contestMapper = mock(ContestMapper.class);
    private final ContestRunMapper contestRunMapper = mock(ContestRunMapper.class);
    private final ContestRunProblemSnapshotMapper runProblemSnapshotMapper = mock(ContestRunProblemSnapshotMapper.class);
    private final ContestParticipantMapper participantMapper = mock(ContestParticipantMapper.class);
    private final ContestAiPolicyService service = new ContestAiPolicyService(
            contestMapper,
            contestRunMapper,
            runProblemSnapshotMapper,
            participantMapper
    );

    @Test
    void activePolicyRequiresPublishedRunningRunAndActiveParticipant() {
        stubSnapshot();
        when(contestRunMapper.selectById(302L)).thenReturn(run(ContestRunStatus.SCHEDULED, -3600, 3600));
        when(contestMapper.selectById(301L)).thenReturn(contest(ContestStatus.PUBLISHED, false));
        when(participantMapper.selectCount(any())).thenReturn(1L);

        var policy = service.check(new ContestAiPolicyRequest(91L, 1001L, 301L, 302L, 401L));

        assertThat(policy.activeContestProblem()).isTrue();
        assertThat(policy.contestId()).isEqualTo(301L);
        assertThat(policy.contestRunId()).isEqualTo(302L);
        assertThat(policy.contestProblemId()).isEqualTo(401L);
        assertThat(policy.problemId()).isEqualTo(1001L);
        assertThat(policy.allowIdeaGuidance()).isTrue();
        assertThat(policy.allowDebugGuidance()).isTrue();
        assertThat(policy.allowSubmissionMetadataToAi()).isTrue();
        assertThat(policy.allowOwnSubmissionCodeToAi()).isFalse();
        assertThat(policy.allowFullCodeInResponse()).isFalse();
        assertThat(policy.allowPseudocode()).isTrue();
        assertThat(policy.maxPseudocodeLines()).isEqualTo(12);
        assertThat(policy.policyMessage()).contains("不能提供完整可提交代码");
    }

    @Test
    void futureRunIsInactive() {
        assertInactiveForRun(run(ContestRunStatus.SCHEDULED, 3600, 7200));
    }

    @Test
    void endedRunIsInactive() {
        assertInactiveForRun(run(ContestRunStatus.SCHEDULED, -7200, -3600));
    }

    @Test
    void draftRunIsInactiveEvenInsideTimeWindow() {
        assertInactiveForRun(run(ContestRunStatus.DRAFT, -3600, 3600));
    }

    @Test
    void archivedRunIsInactiveEvenInsideTimeWindow() {
        assertInactiveForRun(run(ContestRunStatus.ARCHIVED, -3600, 3600));
    }

    @Test
    void deletedRunIsInactiveEvenInsideTimeWindow() {
        ContestRunEntity run = run(ContestRunStatus.SCHEDULED, -3600, 3600);
        run.setDeletedAt(Instant.now());

        assertInactiveForRun(run);
    }

    @Test
    void unpublishedContestIsInactive() {
        stubSnapshot();
        when(contestRunMapper.selectById(302L)).thenReturn(run(ContestRunStatus.SCHEDULED, -3600, 3600));
        when(contestMapper.selectById(301L)).thenReturn(contest(ContestStatus.DRAFT, false));

        var policy = service.check(new ContestAiPolicyRequest(91L, 1001L, 301L, 302L, 401L));

        assertThat(policy.activeContestProblem()).isFalse();
    }

    @Test
    void deletedContestIsInactive() {
        stubSnapshot();
        when(contestRunMapper.selectById(302L)).thenReturn(run(ContestRunStatus.SCHEDULED, -3600, 3600));
        when(contestMapper.selectById(301L)).thenReturn(contest(ContestStatus.PUBLISHED, true));

        var policy = service.check(new ContestAiPolicyRequest(91L, 1001L, 301L, 302L, 401L));

        assertThat(policy.activeContestProblem()).isFalse();
    }

    @Test
    void inactiveParticipantIsInactive() {
        stubSnapshot();
        when(contestRunMapper.selectById(302L)).thenReturn(run(ContestRunStatus.SCHEDULED, -3600, 3600));
        when(contestMapper.selectById(301L)).thenReturn(contest(ContestStatus.PUBLISHED, false));
        when(participantMapper.selectCount(any())).thenReturn(0L);

        var policy = service.check(new ContestAiPolicyRequest(91L, 1001L, 301L, 302L, 401L));

        assertThat(policy.activeContestProblem()).isFalse();
    }

    @Test
    void privateSnapshotVisibilityIsRefused() {
        ContestRunProblemSnapshotEntity snapshot = snapshot();
        snapshot.setVisibility(ProblemVisibility.PRIVATE);
        when(runProblemSnapshotMapper.selectList(any())).thenReturn(List.of(snapshot));
        when(contestRunMapper.selectById(302L)).thenReturn(run(ContestRunStatus.SCHEDULED, -3600, 3600));
        when(contestMapper.selectById(301L)).thenReturn(contest(ContestStatus.PUBLISHED, false));
        when(participantMapper.selectCount(any())).thenReturn(1L);

        var error = org.junit.jupiter.api.Assertions.assertThrows(
                com.aioj.next.common.error.DomainException.class,
                () -> service.check(new ContestAiPolicyRequest(91L, 1001L, 301L, 302L, 401L)));

        assertThat(error.errorCode()).isEqualTo(com.aioj.next.common.error.ErrorCode.FORBIDDEN);
    }

    private void assertInactiveForRun(ContestRunEntity run) {
        stubSnapshot();
        when(contestRunMapper.selectById(302L)).thenReturn(run);

        var policy = service.check(new ContestAiPolicyRequest(91L, 1001L, 301L, 302L, 401L));

        assertThat(policy.activeContestProblem()).isFalse();
    }

    private void stubSnapshot() {
        when(runProblemSnapshotMapper.selectList(any())).thenReturn(List.of(snapshot()));
    }

    private ContestRunProblemSnapshotEntity snapshot() {
        ContestRunProblemSnapshotEntity snapshot = new ContestRunProblemSnapshotEntity();
        snapshot.setContestId(301L);
        snapshot.setContestRunId(302L);
        snapshot.setContestProblemId(401L);
        snapshot.setProblemId(1001L);
        snapshot.setDisplayTitle("Problem");
        snapshot.setCreatedAt(Instant.now());
        return snapshot;
    }

    private ContestRunEntity run(ContestRunStatus status, long startOffsetSeconds, long endOffsetSeconds) {
        Instant now = Instant.now();
        ContestRunEntity run = new ContestRunEntity();
        run.setId(302L);
        run.setContestId(301L);
        run.setStatus(status);
        run.setTitle("Run");
        run.setStartAt(now.plusSeconds(startOffsetSeconds));
        run.setEndAt(now.plusSeconds(endOffsetSeconds));
        return run;
    }

    private ContestEntity contest(ContestStatus status, boolean deleted) {
        ContestEntity contest = new ContestEntity();
        contest.setId(301L);
        contest.setTitle("Contest");
        contest.setStatus(status);
        if (deleted) {
            contest.setDeletedAt(Instant.now());
        }
        return contest;
    }
}
