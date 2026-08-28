package com.aioj.next.contract.contest;

import com.fasterxml.jackson.annotation.JsonInclude;

import java.time.Instant;

public record ContestRegistrationResponse(
        Long id,
        Long contestId,
        Long contestRunId,
        Long userId,
        ContestRegistrationStatus status,
        Instant requestedAt,
        @JsonInclude(JsonInclude.Include.NON_NULL)
        Long reviewedBy,
        @JsonInclude(JsonInclude.Include.NON_NULL)
        Instant approvedAt,
        @JsonInclude(JsonInclude.Include.NON_NULL)
        Instant rejectedAt,
        @JsonInclude(JsonInclude.Include.NON_NULL)
        Instant cancelledAt,
        @JsonInclude(JsonInclude.Include.NON_NULL)
        String rejectReason,
        @JsonInclude(JsonInclude.Include.NON_NULL)
        String account,
        @JsonInclude(JsonInclude.Include.NON_NULL)
        String displayName,
        @JsonInclude(JsonInclude.Include.NON_NULL)
        String email,
        Instant createdAt,
        Instant updatedAt
) {
}
