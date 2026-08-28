package com.aioj.next.contract.contest;

import com.aioj.next.contract.operation.OperationJobStatus;

import java.time.Instant;

/**
 * The safe, student-facing progress view of that student's active personal
 * postmortem operation. It deliberately excludes task errors and artifacts.
 */
public record ContestStudentPostmortemOperationJobResponse(
        Long id,
        OperationJobStatus status,
        Instant createdAt,
        Instant startedAt,
        Instant updatedAt
) {
}
