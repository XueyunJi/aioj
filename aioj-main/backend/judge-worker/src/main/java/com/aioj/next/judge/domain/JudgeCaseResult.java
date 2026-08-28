package com.aioj.next.judge.domain;

import com.aioj.next.contract.submission.SubmissionStatus;

import java.math.BigDecimal;

public record JudgeCaseResult(
        Long testcasePackageId,
        Long caseId,
        Integer caseIndex,
        String caseName,
        String subtaskKey,
        SubmissionStatus status,
        BigDecimal score,
        BigDecimal maxScore,
        Long timeMillis,
        Long memoryKb,
        String message
) {
}
