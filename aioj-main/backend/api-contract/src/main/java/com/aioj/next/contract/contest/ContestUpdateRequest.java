package com.aioj.next.contract.contest;

import jakarta.validation.constraints.Size;

public record ContestUpdateRequest(
        @Size(max = 160) String title,
        @Size(max = 2000) String description,
        ContestMode mode,
        Integer penaltyMinutes,
        Boolean cePenalty,
        ContestAiPolicyMode aiPolicyMode,
        @Size(max = 2000) String aiPolicyNotes
) {
}
