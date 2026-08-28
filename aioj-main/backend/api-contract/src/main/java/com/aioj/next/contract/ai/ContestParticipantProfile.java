package com.aioj.next.contract.ai;

public record ContestParticipantProfile(
        Long userId,
        Long contestRunId,
        String account,
        String displayName
) {
}
