package com.aioj.next.contract.ai;

import java.math.BigDecimal;
import java.time.Instant;

public record AiMemoryReviewRelatedProfileResponse(
        Long id,
        String category,
        String profileKey,
        String label,
        String state,
        BigDecimal confidence,
        Integer evidenceCount,
        Instant updatedAt
) {
}
