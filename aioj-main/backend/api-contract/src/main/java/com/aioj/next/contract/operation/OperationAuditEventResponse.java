package com.aioj.next.contract.operation;

import java.time.Instant;

public record OperationAuditEventResponse(
        Long id,
        Long actorUserId,
        String action,
        String actionDisplayName,
        String resourceType,
        Long resourceId,
        Long contestId,
        Long contestRunId,
        Long targetUserId,
        String status,
        String traceId,
        String summaryJson,
        Instant createdAt
) {
}
