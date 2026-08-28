package com.aioj.next.contract.ai;

import java.time.Instant;
import java.util.List;

public record AiLearningProfileResponse(
        String id,
        String category,
        String key,
        String label,
        String state,
        Double confidence,
        Integer evidenceCount,
        Instant lastEvidenceAt,
        Instant createdAt,
        Instant updatedAt,
        List<AiLearningProfileEvidenceResponse> evidence
) {
}
