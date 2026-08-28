package com.aioj.next.contract.submission;

import com.fasterxml.jackson.annotation.JsonInclude;

import java.math.BigDecimal;
import java.time.Instant;

public record SubmissionCaseResultResponse(
        Long id,
        Long submissionId,
        @JsonInclude(JsonInclude.Include.NON_NULL)
        Long contestId,
        @JsonInclude(JsonInclude.Include.NON_NULL)
        Long contestProblemId,
        @JsonInclude(JsonInclude.Include.NON_NULL)
        Long contestParticipantId,
        @JsonInclude(JsonInclude.Include.NON_NULL)
        Long testcasePackageId,
        @JsonInclude(JsonInclude.Include.NON_NULL)
        Long caseId,
        int caseIndex,
        @JsonInclude(JsonInclude.Include.NON_NULL)
        String caseName,
        @JsonInclude(JsonInclude.Include.NON_NULL)
        String subtaskKey,
        SubmissionStatus status,
        BigDecimal score,
        BigDecimal maxScore,
        @JsonInclude(JsonInclude.Include.NON_NULL)
        Long timeMillis,
        @JsonInclude(JsonInclude.Include.NON_NULL)
        Long memoryKb,
        @JsonInclude(JsonInclude.Include.NON_NULL)
        String message,
        Instant createdAt
) {
}
