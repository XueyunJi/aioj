package com.aioj.next.contract.contest;

import java.time.Instant;
import java.util.List;

public record ContestScoreboardResponse(
        Long contestId,
        Long contestRunId,
        ContestMode mode,
        ContestScoreboardView view,
        Long snapshotId,
        ContestScoreboardSnapshotKind snapshotKind,
        long atContestMillis,
        Instant generatedAt,
        boolean frozen,
        Long freezeAtContestMillis,
        int penaltyMinutes,
        boolean cePenalty,
        List<ContestScoreboardProblemResponse> problems,
        List<ContestScoreboardRowResponse> rows
) {
}
