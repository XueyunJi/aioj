package com.aioj.next.contract.ai;

import java.time.Instant;

public record AiChatMessageResponse(
        Long id,
        String conversationId,
        Long problemId,
        String clientMessageId,
        String role,
        String content,
        String model,
        String status,
        String errorMessage,
        Instant createdAt,
        Instant completedAt
) {
    public AiChatMessageResponse(
            Long id,
            String conversationId,
            Long problemId,
            String clientMessageId,
            String role,
            String content,
            String model,
            Instant createdAt
    ) {
        this(id, conversationId, problemId, clientMessageId, role, content, model, "COMPLETED", null, createdAt, createdAt);
    }

    public AiChatMessageResponse(
            Long id,
            String conversationId,
            Long problemId,
            String role,
            String content,
            String model,
            Instant createdAt
    ) {
        this(id, conversationId, problemId, null, role, content, model, "COMPLETED", null, createdAt, createdAt);
    }
}
