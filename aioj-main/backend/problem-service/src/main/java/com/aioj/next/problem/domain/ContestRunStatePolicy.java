package com.aioj.next.problem.domain;

import com.aioj.next.contract.contest.ContestRunStatus;
import com.aioj.next.problem.persistence.entity.ContestRunEntity;

import java.time.Instant;

final class ContestRunStatePolicy {
    private ContestRunStatePolicy() {
    }

    static ContestRunStatus effectiveStatus(ContestRunEntity run, Instant now) {
        if (run.getStatus() == ContestRunStatus.DRAFT) {
            return isExpiredDraft(run, now) ? ContestRunStatus.EXPIRED : ContestRunStatus.DRAFT;
        }
        if (run.getStatus() == ContestRunStatus.EXPIRED || run.getStatus() == ContestRunStatus.ARCHIVED
                || run.getStartAt() == null || run.getEndAt() == null) {
            return run.getStatus();
        }
        return inferredTimeStatus(run, now);
    }

    static ContestRunStatus restoredStatus(ContestRunEntity run, Instant now) {
        if (run.getStatusBeforeArchive() == ContestRunStatus.DRAFT) {
            return ContestRunStatus.DRAFT;
        }
        if (run.getStartAt() == null || run.getEndAt() == null) {
            return ContestRunStatus.SCHEDULED;
        }
        return inferredTimeStatus(run, now);
    }

    static boolean isArchived(ContestRunEntity run) {
        return run.getStatus() == ContestRunStatus.ARCHIVED;
    }

    static boolean isExpiredDraft(ContestRunEntity run, Instant now) {
        return run.getStatus() == ContestRunStatus.DRAFT && hasElapsedEndAt(run, now);
    }

    static boolean hasElapsedEndAt(ContestRunEntity run, Instant now) {
        return run.getEndAt() != null && !now.isBefore(run.getEndAt());
    }

    static boolean isDeleted(ContestRunEntity run) {
        return run.getDeletedAt() != null;
    }

    static boolean isAiOperationsEligible(ContestRunEntity run, Instant now) {
        return !isDeleted(run) && !isArchived(run) && effectiveStatus(run, now) == ContestRunStatus.ENDED;
    }

    private static ContestRunStatus inferredTimeStatus(ContestRunEntity run, Instant now) {
        if (now.isBefore(run.getStartAt())) {
            return ContestRunStatus.SCHEDULED;
        }
        if (now.isBefore(run.getEndAt())) {
            return ContestRunStatus.RUNNING;
        }
        return ContestRunStatus.ENDED;
    }
}
