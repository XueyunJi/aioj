package com.aioj.next.contract.contest;

import com.fasterxml.jackson.annotation.JsonInclude;

import java.time.Instant;

public record SubmissionCodeAccessLogResponse(
        Long id,
        Long contestId,
        @JsonInclude(JsonInclude.Include.NON_NULL)
        Long contestRunId,
        Long submissionId,
        Long viewerUserId,
        Long targetUserId,
        @JsonInclude(JsonInclude.Include.NON_NULL)
        Long contestParticipantId,
        @JsonInclude(JsonInclude.Include.NON_NULL)
        String viewerAccount,
        @JsonInclude(JsonInclude.Include.NON_NULL)
        String viewerDisplayName,
        @JsonInclude(JsonInclude.Include.NON_NULL)
        String targetAccountSnapshot,
        @JsonInclude(JsonInclude.Include.NON_NULL)
        String targetDisplayNameSnapshot,
        @JsonInclude(JsonInclude.Include.NON_NULL)
        String problemLabel,
        @JsonInclude(JsonInclude.Include.NON_NULL)
        String problemTitle,
        @JsonInclude(JsonInclude.Include.NON_NULL)
        String reason,
        @JsonInclude(JsonInclude.Include.NON_NULL)
        String traceId,
        Instant createdAt
) {
}
