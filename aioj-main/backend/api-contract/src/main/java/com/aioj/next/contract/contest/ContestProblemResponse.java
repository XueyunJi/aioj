package com.aioj.next.contract.contest;

import java.time.Instant;

public record ContestProblemResponse(
        Long id,
        Long contestId,
        Long problemId,
        String label,
        String displayTitle,
        int score,
        int sortOrder,
        ContestProblemScoringMode scoringMode,
        Instant createdAt,
        Instant updatedAt
) {
}
