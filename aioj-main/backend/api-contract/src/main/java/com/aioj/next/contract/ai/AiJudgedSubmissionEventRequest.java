package com.aioj.next.contract.ai;

import com.aioj.next.contract.submission.SubmissionStatus;

import java.time.Instant;

public record AiJudgedSubmissionEventRequest(
        Long submissionId,
        Long problemId,
        Long userId,
        SubmissionStatus status,
        String language,
        Long contestId,
        Long contestRunId,
        Long contestProblemId,
        Instant judgedAt
) {
}
