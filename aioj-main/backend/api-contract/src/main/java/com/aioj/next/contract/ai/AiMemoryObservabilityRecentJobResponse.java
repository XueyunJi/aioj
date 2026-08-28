package com.aioj.next.contract.ai;

import java.time.Instant;

public record AiMemoryObservabilityRecentJobResponse(
        Long jobId,
        String jobType,
        String status,
        Integer attemptCount,
        Integer maxAttempts,
        Instant nextRunAt,
        Instant updatedAt,
        String lastErrorSummary
) {
}
