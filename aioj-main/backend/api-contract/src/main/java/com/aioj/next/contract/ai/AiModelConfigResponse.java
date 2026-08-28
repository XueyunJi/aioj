package com.aioj.next.contract.ai;

import java.time.Instant;

public record AiModelConfigResponse(
        String scope,
        boolean enabled,
        boolean inherited,
        String source,
        String provider,
        String baseUrl,
        String model,
        boolean jsonOutputEnabled,
        boolean thinkingEnabled,
        String reasoningEffort,
        Double temperature,
        Integer maxTokens,
        Integer embeddingDimension,
        boolean apiKeyConfigured,
        String apiKeyPreview,
        String apiKeySource,
        String apiKeyEnvName,
        Instant updatedAt,
        Long updatedBy
) {
}
