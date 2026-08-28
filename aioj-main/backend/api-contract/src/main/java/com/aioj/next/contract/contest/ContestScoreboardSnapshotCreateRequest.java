package com.aioj.next.contract.contest;

public record ContestScoreboardSnapshotCreateRequest(
        ContestScoreboardSnapshotKind snapshotKind,
        ContestScoreboardView view,
        Long atMillis
) {
}
