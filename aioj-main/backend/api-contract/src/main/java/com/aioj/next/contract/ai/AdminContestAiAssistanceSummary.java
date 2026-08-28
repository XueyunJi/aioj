package com.aioj.next.contract.ai;

import java.time.Instant;

/**
 * Authoritative, run-scoped contest AI-assistance statistics for one student.
 *
 * <p>Live values are derived from the V3 turn ledger. Historical values are
 * clearly marked as legacy snapshots because the pre-V3 data cannot reconstruct
 * every turn and guard decision exactly.</p>
 */
public record AdminContestAiAssistanceSummary(
        Long userId,
        String account,
        String displayName,
        long turnCount,
        long promptTokens,
        long completionTokens,
        long conversationCount,
        long interceptedCount,
        String dataSource,
        String tokenAccountingStatus,
        Instant lastUsedAt
) {
}
