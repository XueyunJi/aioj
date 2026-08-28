package com.aioj.next.contract.ai;

import java.math.BigDecimal;
import java.time.Instant;

public record AiMemoryReviewEvidenceResponse(
        Long id,
        Long claimId,
        Long candidateId,
        String evidenceType,
        String evidenceText,
        BigDecimal confidence,
        String reason,
        Instant createdAt
) {
}
