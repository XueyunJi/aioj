package com.aioj.next.contract.submission;

import com.fasterxml.jackson.annotation.JsonInclude;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;

public record SubmissionResponse(
        Long id,
        Long problemId,
        Long userId,
        @JsonInclude(JsonInclude.Include.NON_NULL)
        Long contestId,
        @JsonInclude(JsonInclude.Include.NON_NULL)
        Long contestRunId,
        @JsonInclude(JsonInclude.Include.NON_NULL)
        Long contestProblemId,
        @JsonInclude(JsonInclude.Include.NON_NULL)
        Long contestParticipantId,
        @JsonInclude(JsonInclude.Include.NON_NULL)
        Long submittedAtContestMillis,
        boolean visibleToParticipant,
        String language,
        @JsonInclude(JsonInclude.Include.NON_NULL)
        String code,
        SubmissionStatus status,
        String judgeMessage,
        Long timeMillis,
        Long memoryKb,
        @JsonInclude(JsonInclude.Include.NON_NULL)
        String stdoutExcerpt,
        @JsonInclude(JsonInclude.Include.NON_NULL)
        String stderrExcerpt,
        @JsonInclude(JsonInclude.Include.NON_NULL)
        Integer exitStatus,
        @JsonInclude(JsonInclude.Include.NON_NULL)
        Long runTimeMillis,
        @JsonInclude(JsonInclude.Include.NON_NULL)
        BigDecimal score,
        @JsonInclude(JsonInclude.Include.NON_NULL)
        BigDecimal maxScore,
        @JsonInclude(JsonInclude.Include.NON_NULL)
        List<SubmissionCaseResultResponse> caseResults,
        Instant createdAt,
        Instant judgedAt
) {
}
