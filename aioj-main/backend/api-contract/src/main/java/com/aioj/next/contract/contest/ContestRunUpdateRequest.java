package com.aioj.next.contract.contest;

import com.fasterxml.jackson.annotation.JsonInclude;

import java.time.Instant;
import java.util.List;

public record ContestRunUpdateRequest(
        String title,
        Instant startAt,
        Instant endAt,
        @JsonInclude(JsonInclude.Include.NON_NULL)
        Instant freezeAt,
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
