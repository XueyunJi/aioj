package com.aioj.next.contract.ai;

public record AiSubmissionContextRequest(
        Long requestUserId,
        Long submissionId,
        Long problemId,
        Long contestId,
        Long contestRunId,
        Long contestProblemId,
        String purpose
) {
}
