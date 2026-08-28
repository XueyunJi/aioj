package com.aioj.next.contract.ai;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;

public record AiMemoryReviewListItemResponse(
        Long id,
        Long userId,
        String category,
        String memoryKey,
        String canonicalText,
        String scopeType,
        String scopeId,
        String evidenceType,
        BigDecimal extractionConfidence,
        BigDecimal writeScore,
        boolean needsConfirmation,
        List<String> qualityFlags,
        List<String> ambiguityFlags,
        String status,
        String rejectedReason,
        String sourceConversationId,
        Long sourceMessageId,
        Instant createdAt,
        Instant updatedAt
) {
}
