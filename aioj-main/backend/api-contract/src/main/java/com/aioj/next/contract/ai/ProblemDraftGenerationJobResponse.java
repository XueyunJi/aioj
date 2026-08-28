package com.aioj.next.contract.ai;

import java.time.Instant;

public record ProblemDraftGenerationJobResponse(
        Long id,
        Long creatorUserId,
        String jobType,
        Long sourceDraftId,
        String status,
        String stage,
        String topicSnapshot,
        Integer progressCurrent,
        Integer progressTotal,
        String progressMessage,
        Long draftId,
        Integer errorCode,
        String errorKey,
        String errorMessage,
        Instant startedAt,
        Instant completedAt,
        Instant createdAt,
        Instant updatedAt
) {
}
