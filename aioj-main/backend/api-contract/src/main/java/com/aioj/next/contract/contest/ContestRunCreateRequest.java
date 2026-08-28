package com.aioj.next.contract.contest;

import com.fasterxml.jackson.annotation.JsonInclude;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;

import java.time.Instant;
import java.util.List;

public record ContestRunCreateRequest(
        ContestRunKind runKind,
        @NotBlank String title,
        @NotNull Instant startAt,
        @NotNull Instant endAt,
        @JsonInclude(JsonInclude.Include.NON_NULL)
        Instant freezeAt,
        @JsonInclude(JsonInclude.Include.NON_NULL)
        Long sourceRunId,
        ContestRegistrationPolicy registrationPolicy,
        ContestRegistrationAccess registrationAccess,
        Boolean approvalRequired,
        @JsonInclude(JsonInclude.Include.NON_NULL)
        List<Long> allowedGroupIds,
        @JsonInclude(JsonInclude.Include.NON_NULL)
        Instant registrationStartAt,
        @JsonInclude(JsonInclude.Include.NON_NULL)
        Instant registrationEndAt,
        @JsonInclude(JsonInclude.Include.NON_NULL)
        Integer maxParticipants
) {
}
