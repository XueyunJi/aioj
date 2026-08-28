package com.aioj.next.contract.learning;

import java.time.Instant;

public record LearningGroupResponse(
        Long id,
        Long parentGroupId,
        LearningGroupType type,
        String name,
        String description,
        Long ownerUserId,
        String ownerDisplayName,
        LearningGroupStatus status,
        long memberCount,
        long studentCount,
        Instant createdAt,
        Instant updatedAt,
        Instant archivedAt,
        Instant deletedAt,
        Long deletedBy
) {
}
