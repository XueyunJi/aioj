package com.aioj.next.contract.contest;

import java.time.Instant;

public record ContestPostmortemReportResponse(
        Long id,
        Long contestId,
        Long contestRunId,
        ContestPostmortemReportStatus status,
        ContestPostmortemAiStatus aiStatus,
        Long generatedBy,
        String statisticsJson,
        String aiMarkdown,
        String aiProvider,
        String aiModel,
        Long promptTokens,
        Long completionTokens,
        String errorMessage,
        Instant createdAt,
        Instant updatedAt,
        Instant completedAt
) {
}
