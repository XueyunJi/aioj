package com.aioj.next.ai.agent.tool;

import com.aioj.next.ai.agent.context.DataClassification;
import com.fasterxml.jackson.databind.JsonNode;

import java.time.Duration;
import java.util.Set;

/**
 * Static description of a tool. Descriptions come from trusted code only —
 * never from database content or remote plugin text (design doc §6.8).
 *
 * @param name              internal dotted name, e.g. {@code context.search_exact};
 *                          mapped to provider-safe wire names by the model adapters
 * @param inputSchema       JSON Schema (MFJS-safe subset: explicit required,
 *                          additionalProperties:false, no oneOf/anyOf/$ref)
 */
public record ToolDescriptor(
        String name,
        String version,
        String description,
        JsonNode inputSchema,
        ToolRiskLevel riskLevel,
        boolean readOnly,
        boolean idempotent,
        Set<String> requiredScopes,
        Set<DataClassification> allowedDataClasses,
        int maxResultTokens,
        Duration timeout,
        ToolAuditLevel auditLevel
) {
    public ToolDescriptor {
        requiredScopes = requiredScopes == null ? Set.of() : Set.copyOf(requiredScopes);
        allowedDataClasses = allowedDataClasses == null ? Set.of() : Set.copyOf(allowedDataClasses);
    }
}
