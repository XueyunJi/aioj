package com.aioj.next.judge.domain;

import com.aioj.next.contract.submission.SubmissionStatus;
import com.aioj.next.contract.submission.JudgePhase;

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
        List<JudgeCaseResult> caseResults,
        JudgePhase phase,
        String sandboxStatus
) {
    public JudgeResult(SubmissionStatus status, String message, Long timeMillis, Long memoryKb, Instant judgedAt,
                       String stdout, String stderr, Integer exitStatus, Long runTimeMillis, BigDecimal score,
                       BigDecimal maxScore, List<JudgeCaseResult> caseResults) {
        this(status, message, timeMillis, memoryKb, judgedAt, stdout, stderr, exitStatus, runTimeMillis,
                score, maxScore, caseResults, null, null);
    }

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
                null, null, List.of(), null, null);
    }

    public JudgeResult withDiagnostics(JudgePhase phase, String sandboxStatus) {
        return new JudgeResult(status, message, timeMillis, memoryKb, judgedAt, stdout, stderr, exitStatus,
                runTimeMillis, score, maxScore, caseResults, phase, sandboxStatus);
    }

    public static JudgeResult systemError(String message) {
        return new JudgeResult(SubmissionStatus.SYSTEM_ERROR, message,
                0L, 0L, Instant.now(), null, null, null, null);
    }
}
