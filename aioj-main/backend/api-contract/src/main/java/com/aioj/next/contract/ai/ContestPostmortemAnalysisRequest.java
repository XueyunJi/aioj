package com.aioj.next.contract.ai;

public record ContestPostmortemAnalysisRequest(
        Long actorUserId,
        Long contestId,
        Long contestRunId,
        String contestTitle,
        String runTitle,
        String mode,
        String statisticsJson,
        String summaryText
) {
}
