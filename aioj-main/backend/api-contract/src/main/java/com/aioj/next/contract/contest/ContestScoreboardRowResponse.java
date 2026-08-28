package com.aioj.next.contract.contest;

import java.math.BigDecimal;
import java.util.List;

public record ContestScoreboardRowResponse(
        int rank,
        Long participantId,
        Long userId,
        String accountSnapshot,
        String displayNameSnapshot,
        int solvedCount,
        int penaltyMinutes,
        Long lastAcceptedAtMillis,
        BigDecimal totalScore,
        Long lastScoreImprovedAtMillis,
        List<ContestScoreboardCellResponse> cells
) {
}
