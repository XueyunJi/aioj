package com.aioj.next.ai.domain.response;

import com.fasterxml.jackson.databind.JsonNode;

import java.time.Instant;

/**
 * One row of the staff guard-decision audit feed (design doc §5.6, P3-7).
 * {@code id} is a snowflake and is exposed as a string; matchedProblemRefs/detail
 * are parsed JSON, never string-wrapped JSON.
 */
public record GuardDecisionAuditItem(
        String id,
        String turnId,
        Long userId,
        String conversationId,
        Long contestRunId,
        String layer,
        String decision,
        String reasonCode,
        JsonNode matchedProblemRefs,
        JsonNode detail,
        Boolean degraded,
        Integer latencyMs,
        Instant createdAt
) {
}
