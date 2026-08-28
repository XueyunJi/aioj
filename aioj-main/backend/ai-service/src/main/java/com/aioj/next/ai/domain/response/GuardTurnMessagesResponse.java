package com.aioj.next.ai.domain.response;

import java.time.Instant;

/**
 * Staff view of one turn plus its user/assistant messages (design doc §5.6, P3-7):
 * the audit API links a guard decision turn back to the conversation content in
 * ai_messages. Message ids are snowflakes and are exposed as strings.
 */
public record GuardTurnMessagesResponse(
        String turnId,
        String conversationId,
        Long userId,
        String status,
        Instant createdAt,
        GuardTurnMessage userMessage,
        GuardTurnMessage assistantMessage
) {
    public record GuardTurnMessage(
            String id,
            String role,
            String content,
            String model,
            Instant createdAt
    ) {
    }
}
