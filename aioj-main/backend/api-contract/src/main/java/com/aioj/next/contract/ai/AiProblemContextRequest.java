package com.aioj.next.contract.ai;

public record AiProblemContextRequest(
        Long requestUserId,
        Long problemId,
        Long contestId,
        Long contestRunId,
        Long contestProblemId,
        String purpose
) {
}
