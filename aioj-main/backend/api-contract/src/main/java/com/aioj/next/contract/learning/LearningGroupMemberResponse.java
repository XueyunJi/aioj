package com.aioj.next.contract.learning;

import java.time.Instant;

public record LearningGroupMemberResponse(
        Long userId,
        String account,
        String displayName,
        String email,
        Boolean enabled,
        LearningGroupMemberRole role,
        Instant joinedAt
) {
}
