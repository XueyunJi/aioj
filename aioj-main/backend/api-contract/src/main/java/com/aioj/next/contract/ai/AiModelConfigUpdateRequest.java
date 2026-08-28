package com.aioj.next.contract.ai;

public record AiModelConfigUpdateRequest(
        Boolean enabled,
        String provider,
        String baseUrl,
        String model,
        String apiKeyAction,
        String apiKey,
        Boolean jsonOutputEnabled,
        Boolean thinkingEnabled,
        String reasoningEffort,
        Double temperature,
        Integer maxTokens,
        Integer embeddingDimension
) {
}
