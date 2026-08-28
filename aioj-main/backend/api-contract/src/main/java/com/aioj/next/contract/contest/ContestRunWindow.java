package com.aioj.next.contract.contest;

import java.time.Instant;

/**
 * Scheduled window of one contest run, used by ai-service to bound AI usage records
 * to the actual contest time frame (records are counted within start..end + 1 minute).
 */
public record ContestRunWindow(
        Long runId,
        Instant startAt,
        Instant endAt
) {
}
