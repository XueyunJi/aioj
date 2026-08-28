package com.aioj.next.contract.ai;

import java.time.Instant;

public record AiConversationResponse(
        String conversationId,
        Long problemId,
        String title,
        String source,
        String sourceRefType,
        String sourceRefId,
        String mode,
        String summary,
        Long recentProblemId,
        long messageCount,
        String latestMessagePreview,
        Instant deletedAt,
        Instant createdAt,
        Instant updatedAt
) {
}
