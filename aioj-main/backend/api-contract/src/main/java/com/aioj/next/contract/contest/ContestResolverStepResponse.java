package com.aioj.next.contract.contest;

import java.time.Instant;

public record ContestResolverStepResponse(
        Long id,
        Long resolverSessionId,
        Long contestId,
        Long contestRunId,
        Integer stepOrder,
        ContestResolverStepType stepType,
        Long participantId,
        Long contestProblemId,
        Long submissionId,
        String payloadJson,
        ContestScoreboardResponse scoreboard,
        Instant createdAt
) {
}
