package com.aioj.next.contract.ai;

import java.time.Instant;
import java.util.List;

public record AiMemoryObservabilityResponse(
        Instant generatedAt,
        List<AiMemoryObservabilityMetricResponse> jobsByStatus,
        List<AiMemoryObservabilityMetricResponse> jobsByType,
        List<AiMemoryObservabilityMetricResponse> eventsByType,
        long dueJobCount,
        long expiredLeaseCount,
        List<AiMemoryObservabilityRecentJobResponse> recentFinalFailures,
        long totalJobCount,
        double jobFailureRate,
        long memoryExtractionFailureCount,
        long embeddingFailureCount,
        long embeddingCapacityRejectedCount
) {
}
