package com.aioj.next.contract.contest;

import java.time.Instant;

public record ContestClarificationResponse(
        Long id,
        Long contestId,
        Long contestRunId,
        Long contestProblemId,
        Long participantId,
        Long userId,
        String question,
        ContestClarificationStatus status,
        String answer,
        ContestClarificationVisibility answerVisibility,
        Long answeredBy,
        Instant answeredAt,
        Instant closedAt,
        Boolean mine,
        Boolean publicAnswer,
        Instant createdAt,
        Instant updatedAt
) {
}
