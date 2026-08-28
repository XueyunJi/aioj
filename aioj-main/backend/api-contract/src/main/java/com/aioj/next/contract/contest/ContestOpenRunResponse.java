package com.aioj.next.contract.contest;

import com.fasterxml.jackson.annotation.JsonInclude;

public record ContestOpenRunResponse(
        ContestResponse contest,
        ContestRunResponse run,
        boolean canRegister,
        boolean canSubmit,
        boolean canViewProblems,
        boolean canViewScoreboard,
        boolean full,
        @JsonInclude(JsonInclude.Include.NON_NULL)
        ContestRegistrationResponse registration,
        @JsonInclude(JsonInclude.Include.NON_NULL)
        ContestParticipantResponse participant
) {
}
