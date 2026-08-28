package com.aioj.next.contract.contest;

import java.math.BigDecimal;

public record ContestScoreboardCellResponse(
        Long contestProblemId,
        ContestScoreboardCellStatus status,
        int attempts,
        int wrongAttempts,
        int pendingAttempts,
        Long acceptedAtMillis,
        int penaltyMinutes,
        BigDecimal score,
        BigDecimal maxScore,
        Long bestSubmissionId,
        Long lastScoreImprovedAtMillis
) {
}
