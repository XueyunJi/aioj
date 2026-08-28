package com.aioj.next.contract.contest;

import com.aioj.next.contract.submission.SubmissionStatus;
import com.aioj.next.contract.submission.SubmissionCaseResultResponse;
import com.fasterxml.jackson.annotation.JsonInclude;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;

public record ContestSubmissionResponse(
        Long id,
        Long contestId,
        @JsonInclude(JsonInclude.Include.NON_NULL)
        Long contestRunId,
        Long contestProblemId,
        Long problemId,
        @JsonInclude(JsonInclude.Include.NON_NULL)
        String problemLabel,
        @JsonInclude(JsonInclude.Include.NON_NULL)
        String problemTitle,
        Long contestParticipantId,
        Long userId,
        String accountSnapshot,
        String displayNameSnapshot,
        @JsonInclude(JsonInclude.Include.NON_NULL)
        String emailSnapshot,
        String language,
        SubmissionStatus status,
        String judgeMessage,
        Long timeMillis,
        Long memoryKb,
        @JsonInclude(JsonInclude.Include.NON_NULL)
        BigDecimal score,
        @JsonInclude(JsonInclude.Include.NON_NULL)
        BigDecimal maxScore,
        @JsonInclude(JsonInclude.Include.NON_NULL)
        List<SubmissionCaseResultResponse> caseResults,
        Long submittedAtContestMillis,
        Instant createdAt,
        Instant judgedAt,
        boolean codeIncluded,
        @JsonInclude(JsonInclude.Include.NON_NULL)
        String code,
        @JsonInclude(JsonInclude.Include.NON_NULL)
        String stdoutExcerpt,
        @JsonInclude(JsonInclude.Include.NON_NULL)
        String stderrExcerpt,
        @JsonInclude(JsonInclude.Include.NON_NULL)
        Integer exitStatus,
        @JsonInclude(JsonInclude.Include.NON_NULL)
        Long runTimeMillis
) {
}
