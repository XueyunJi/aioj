package com.aioj.next.contract.ai;

import java.time.Instant;

public record AdminContestAiConversationSummary(
        String conversationId,
        String title,
        String mode,
        Long contestRunId,
        Long contestProblemId,
        Long problemId,
        String problemTitle,
        long messageCount,
        Instant lastMessageAt
) {
}
