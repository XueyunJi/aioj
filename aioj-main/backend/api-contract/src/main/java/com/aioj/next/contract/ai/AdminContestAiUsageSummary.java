package com.aioj.next.contract.ai;

import java.time.Instant;

public record AdminContestAiUsageSummary(
        Long userId,
        String account,
        String displayName,
        long callCount,
        long promptTokens,
        long completionTokens,
        long conversationCount,
        long blockedCount,
        long evaluatedCount,
        long constrainCount,
        long refuseCount,
        long degradedCount,
        Instant lastUsedAt
) {
}
