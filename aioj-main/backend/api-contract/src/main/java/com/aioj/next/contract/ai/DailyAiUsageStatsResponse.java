package com.aioj.next.contract.ai;

public record DailyAiUsageStatsResponse(
        String date,
        long calls,
        long successfulCalls,
        long promptTokens,
        long completionTokens
) {
}
