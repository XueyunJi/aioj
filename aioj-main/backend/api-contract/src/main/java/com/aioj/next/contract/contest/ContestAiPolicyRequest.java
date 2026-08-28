package com.aioj.next.contract.contest;

public record ContestAiPolicyRequest(
        Long userId,
        Long problemId,
        Long contestId,
        Long contestRunId,
        Long contestProblemId
) {
}
