package com.aioj.next.judge.domain;

import com.aioj.next.contract.submission.SubmissionStatus;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;

public record JudgeResult(
        SubmissionStatus status,
        String message,
        Long timeMillis,
        Long memoryKb,
        Instant judgedAt,
        String stdout,
        String stderr,
        Integer exitStatus,
        Long runTimeMillis,
        BigDecimal score,
        BigDecimal maxScore,
        List<JudgeCaseResult> caseResults
) {
    public JudgeResult(SubmissionStatus status,
                       String message,
                       Long timeMillis,
                       Long memoryKb,
                       Instant judgedAt,
                       String stdout,
                       String stderr,
                       Integer exitStatus,
                       Long runTimeMillis) {
        this(status, message, timeMillis, memoryKb, judgedAt, stdout, stderr, exitStatus, runTimeMillis,
                null, null, List.of());
    }

    public static JudgeResult systemError(String message) {
        return new JudgeResult(SubmissionStatus.SYSTEM_ERROR, message,
                0L, 0L, Instant.now(), null, null, null, null);
    }
}
