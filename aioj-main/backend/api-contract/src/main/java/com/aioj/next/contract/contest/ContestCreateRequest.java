package com.aioj.next.contract.contest;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

public record ContestCreateRequest(
        @NotBlank @Size(max = 160) String title,
        @Size(max = 2000) String description,
        @NotNull ContestMode mode,
        Integer penaltyMinutes,
        Boolean cePenalty,
        ContestAiPolicyMode aiPolicyMode,
        @Size(max = 2000) String aiPolicyNotes
) {
}
