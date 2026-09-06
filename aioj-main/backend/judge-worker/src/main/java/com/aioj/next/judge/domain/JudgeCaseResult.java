package com.aioj.next.judge.domain;

import com.aioj.next.contract.submission.SubmissionStatus;
import com.aioj.next.contract.submission.JudgePhase;

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
        String message,
        JudgePhase phase,
        String sandboxStatus,
        boolean sample
) {
    public JudgeCaseResult(Long testcasePackageId, Long caseId, Integer caseIndex, String caseName,
                           String subtaskKey, SubmissionStatus status, BigDecimal score, BigDecimal maxScore,
                           Long timeMillis, Long memoryKb, String message) {
        this(testcasePackageId, caseId, caseIndex, caseName, subtaskKey, status, score, maxScore,
                timeMillis, memoryKb, message, null, null, false);
    }
}
