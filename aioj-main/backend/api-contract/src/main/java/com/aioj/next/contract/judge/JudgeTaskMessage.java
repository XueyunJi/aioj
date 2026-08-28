package com.aioj.next.contract.judge;

import com.aioj.next.contract.contest.ContestMode;

public record JudgeTaskMessage(
        Long submissionId,
        Long problemId,
        Long userId,
        Long contestId,
        Long contestRunId,
        Long contestProblemId,
        Long contestParticipantId,
        ContestMode contestMode,
        String language,
        String traceId,
        Integer timeLimitMillis,
        Long memoryLimitKb
) {
}
