package com.aioj.next.contract.contest;

import java.time.Instant;

public record ContestResolverSessionResponse(
        Long id,
        Long contestId,
        Long contestRunId,
        ContestResolverSessionStatus status,
        String title,
        ContestScoreboardView view,
        Long freezeSnapshotId,
        Long finalSnapshotId,
        Integer stepCount,
        String checksum,
        Long createdBy,
        Instant publishedAt,
        Instant archivedAt,
        ContestResolverSessionStatus statusBeforeArchive,
        Instant deletedAt,
        Long deletedBy,
        Instant createdAt,
        Instant updatedAt
) {
}
