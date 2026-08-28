package com.aioj.next.contract.contest;

import java.time.Instant;

public record ContestResponse(
        Long id,
        Long ownerUserId,
        Long scopeGroupId,
        String title,
        String description,
        ContestMode mode,
        ContestStatus status,
        ContestVisibility visibility,
        Instant startAt,
        Instant endAt,
        Instant freezeAt,
        int penaltyMinutes,
        boolean cePenalty,
        long problemCount,
        Instant createdAt,
        Instant updatedAt,
        Instant publishedAt,
        Instant archivedAt,
        Instant deletedAt,
        Long deletedBy,
        ContestAiPolicyMode aiPolicyMode,
        String aiPolicyNotes
) {
}
