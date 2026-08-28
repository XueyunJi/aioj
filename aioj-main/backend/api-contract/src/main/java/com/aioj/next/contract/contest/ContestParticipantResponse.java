package com.aioj.next.contract.contest;

import java.time.Instant;

public record ContestParticipantResponse(
        Long id,
        Long contestId,
        Long contestRunId,
        Long userId,
        ContestParticipantType participantType,
        ContestParticipantStatus status,
        String accountSnapshot,
        String displayNameSnapshot,
        String emailSnapshot,
        Long scopeGroupId,
        String groupNameSnapshot,
        Instant registeredAt,
        Instant createdAt,
        Instant updatedAt
) {
}
