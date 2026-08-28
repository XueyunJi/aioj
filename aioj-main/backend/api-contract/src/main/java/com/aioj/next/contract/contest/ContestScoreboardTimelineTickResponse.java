package com.aioj.next.contract.contest;

import java.time.Instant;

public record ContestScoreboardTimelineTickResponse(
        Long id,
        Long contestId,
        Long contestRunId,
        ContestScoreboardView view,
        Long bucketMillis,
        Long snapshotId,
        String checksum,
        Instant createdAt
) {
}
