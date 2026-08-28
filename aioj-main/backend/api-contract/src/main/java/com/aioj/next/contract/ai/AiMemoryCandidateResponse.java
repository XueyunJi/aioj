package com.aioj.next.contract.ai;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;

public record AiMemoryCandidateResponse(
        Long id,
        String category,
        String memoryKey,
        String canonicalText,
        String scopeType,
        String scopeId,
        String evidenceType,
        BigDecimal extractionConfidence,
        BigDecimal writeScore,
        boolean longTerm,
        boolean problemSpecific,
        boolean hypothetical,
        boolean quoted,
        boolean needsConfirmation,
        List<String> qualityFlags,
        List<String> ambiguityFlags,
        String status,
        String rejectedReason,
        String sourceConversationId,
        Long sourceMessageId,
        Instant createdAt,
        Instant updatedAt,
        String candidateKind,
        String plannerAction,
        Long targetMemoryId,
        Long targetClaimId
) {
}
