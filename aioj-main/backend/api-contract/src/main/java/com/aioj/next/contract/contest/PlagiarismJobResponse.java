package com.aioj.next.contract.contest;

import com.fasterxml.jackson.annotation.JsonInclude;

import java.time.Instant;

public record PlagiarismJobResponse(
        Long id,
        Long contestId,
        @JsonInclude(JsonInclude.Include.NON_NULL)
        Long contestRunId,
        PlagiarismJobStatus status,
        PlagiarismDetectorType detector,
        double minimumSimilarity,
        boolean includeAiAnalysis,
        int totalSubmissions,
        int totalPairs,
        int highRiskPairs,
        @JsonInclude(JsonInclude.Include.NON_NULL)
        String errorMessage,
        Long createdBy,
        @JsonInclude(JsonInclude.Include.NON_NULL)
        Instant startedAt,
        @JsonInclude(JsonInclude.Include.NON_NULL)
        Instant completedAt,
        Instant createdAt,
        Instant updatedAt
) {
}
