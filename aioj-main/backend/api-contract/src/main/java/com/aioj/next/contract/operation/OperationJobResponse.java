package com.aioj.next.contract.operation;

import java.time.Instant;

public record OperationJobResponse(
        Long id,
        OperationJobType jobType,
        OperationJobStatus status,
        String resourceType,
        Long resourceId,
        Long contestId,
        Long contestRunId,
        Long requestedBy,
        String errorMessage,
        Integer attemptCount,
        Integer maxAttempts,
        Integer progressCurrent,
        Integer progressTotal,
        String progressMessage,
        String resultJson,
        OperationJobArtifactResponse artifact,
        Instant startedAt,
        Instant completedAt,
        Instant createdAt,
        Instant updatedAt
) {
}
