package com.aioj.next.contract.contest;

public record ContestScoreboardProblemResponse(
        Long contestProblemId,
        Long problemId,
        String label,
        String displayTitle,
        int score,
        int sortOrder
) {
}
