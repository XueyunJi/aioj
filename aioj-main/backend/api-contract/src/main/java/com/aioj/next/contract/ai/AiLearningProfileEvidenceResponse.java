package com.aioj.next.contract.ai;

import java.time.Instant;

public record AiLearningProfileEvidenceResponse(
        String id,
        String profileId,
        String evidenceType,
        String sourceType,
        String sourceId,
        String summary,
        Double confidence,
        Instant createdAt
) {
}
