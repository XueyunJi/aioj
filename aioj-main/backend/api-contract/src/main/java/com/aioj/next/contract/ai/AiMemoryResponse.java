package com.aioj.next.contract.ai;

import java.math.BigDecimal;
import java.time.Instant;

public record AiMemoryResponse(
        Long id,
        String category,
        String title,
        String memoryType,
        String content,
        BigDecimal confidence,
        String source,
        String status,
        Instant createdAt,
        Instant updatedAt,
        Instant lastUsedAt
) {
}
