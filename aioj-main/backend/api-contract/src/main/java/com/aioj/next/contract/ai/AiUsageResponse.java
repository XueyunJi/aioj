package com.aioj.next.contract.ai;

public record AiUsageResponse(
        long usedRecent,
        long rollingLimit,
        int recentWindowHours,
        long usedThisMonth,
        long monthlyLimit
) {
}
