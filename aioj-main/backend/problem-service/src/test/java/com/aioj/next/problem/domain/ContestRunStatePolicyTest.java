package com.aioj.next.problem.domain;

import com.aioj.next.contract.contest.ContestRunStatus;
import com.aioj.next.problem.persistence.entity.ContestRunEntity;
import org.junit.jupiter.api.Test;

import java.time.Instant;

import static org.assertj.core.api.Assertions.assertThat;

class ContestRunStatePolicyTest {
    @Test
    void staleArchivedAtDoesNotOverrideRestoredLifecycleStatus() {
        Instant now = Instant.parse("2026-06-06T14:30:00Z");
        ContestRunEntity run = run(ContestRunStatus.SCHEDULED,
                Instant.parse("2026-06-06T07:02:00Z"),
                Instant.parse("2026-06-06T07:17:00Z"));
        run.setArchivedAt(Instant.parse("2026-06-06T12:28:36.568Z"));
        run.setStatusBeforeArchive(ContestRunStatus.SCHEDULED);

        assertThat(ContestRunStatePolicy.effectiveStatus(run, now)).isEqualTo(ContestRunStatus.ENDED);
        assertThat(ContestRunStatePolicy.isAiOperationsEligible(run, now)).isTrue();
    }

    @Test
    void archivedLifecycleStatusBlocksAiOperationsEvenIfTimeEnded() {
        Instant now = Instant.parse("2026-06-06T14:30:00Z");
        ContestRunEntity run = run(ContestRunStatus.ARCHIVED,
                Instant.parse("2026-06-06T07:02:00Z"),
                Instant.parse("2026-06-06T07:17:00Z"));

        assertThat(ContestRunStatePolicy.effectiveStatus(run, now)).isEqualTo(ContestRunStatus.ARCHIVED);
        assertThat(ContestRunStatePolicy.isAiOperationsEligible(run, now)).isFalse();
    }

    @Test
    void deletedRunBlocksAiOperations() {
        Instant now = Instant.parse("2026-06-06T14:30:00Z");
        ContestRunEntity run = run(ContestRunStatus.SCHEDULED,
                Instant.parse("2026-06-06T07:02:00Z"),
                Instant.parse("2026-06-06T07:17:00Z"));
        run.setDeletedAt(Instant.parse("2026-06-06T12:00:00Z"));

        assertThat(ContestRunStatePolicy.effectiveStatus(run, now)).isEqualTo(ContestRunStatus.ENDED);
        assertThat(ContestRunStatePolicy.isAiOperationsEligible(run, now)).isFalse();
    }

    @Test
    void draftRunWhoseConfiguredEndHasElapsedIsExpiredButNeverAiOperationsEligible() {
        Instant now = Instant.parse("2026-06-06T14:30:00Z");
        ContestRunEntity run = run(ContestRunStatus.DRAFT,
                Instant.parse("2026-06-06T07:02:00Z"),
                Instant.parse("2026-06-06T07:17:00Z"));

        assertThat(ContestRunStatePolicy.effectiveStatus(run, now)).isEqualTo(ContestRunStatus.EXPIRED);
        assertThat(ContestRunStatePolicy.isAiOperationsEligible(run, now)).isFalse();
    }

    private ContestRunEntity run(ContestRunStatus status, Instant startAt, Instant endAt) {
        ContestRunEntity run = new ContestRunEntity();
        run.setStatus(status);
        run.setStartAt(startAt);
        run.setEndAt(endAt);
        return run;
    }
}
