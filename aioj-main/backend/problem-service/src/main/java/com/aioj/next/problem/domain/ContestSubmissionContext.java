package com.aioj.next.problem.domain;

import com.aioj.next.problem.persistence.entity.ContestEntity;
import com.aioj.next.problem.persistence.entity.ContestParticipantEntity;
import com.aioj.next.problem.persistence.entity.ContestProblemEntity;
import com.aioj.next.problem.persistence.entity.ContestRunEntity;

public record ContestSubmissionContext(
        ContestEntity contest,
        ContestRunEntity contestRun,
        ContestProblemEntity contestProblem,
        ContestParticipantEntity participant,
        long submittedAtContestMillis
) {
}
