package com.aioj.next.contract.learning;

import jakarta.validation.constraints.Size;

public record LearningGroupMemberAddRequest(
        Long userId,
        @Size(max = 64) String account,
        LearningGroupMemberRole role
) {
}
