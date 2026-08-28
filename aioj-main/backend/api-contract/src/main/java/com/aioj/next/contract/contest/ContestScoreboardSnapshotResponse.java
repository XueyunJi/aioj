package com.aioj.next.contract.contest;

import java.time.Instant;

public record ContestScoreboardSnapshotResponse(
        Long id,
        Long contestId,
        Long contestRunId,
        ContestScoreboardSnapshotKind snapshotKind,
        ContestScoreboardView view,
        Instant snapshotAt,
        long contestTimeMillis,
        int scoringVersion,
        boolean frozen,
        String checksum,
        Long createdBy,
        Instant createdAt
) {
}
