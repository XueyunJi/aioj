package com.aioj.next.ai.domain;

import java.time.Instant;

public record AiModelEffectiveConfig(
        AiModelScope scope,
        boolean enabled,
        boolean inherited,
        String source,
        String provider,
        String baseUrl,
        String apiKey,
        String apiKeyPreview,
        String apiKeySource,
        String apiKeyEnvName,
        String model,
        boolean jsonOutputEnabled,
        boolean thinkingEnabled,
        String reasoningEffort,
        Double temperature,
        Integer maxTokens,
        Integer embeddingDimension,
        Instant updatedAt,
        Long updatedBy
) {
    public boolean hasApiKey() {
        return apiKey != null && !apiKey.isBlank();
    }

    public double temperatureOr(double fallback) {
        return temperature == null ? fallback : temperature;
    }

    public int maxTokensOr(int fallback) {
        return maxTokens == null || maxTokens <= 0 ? fallback : maxTokens;
    }

    public AiModelEffectiveConfig withScope(AiModelScope nextScope, boolean nextInherited, String nextSource) {
        return new AiModelEffectiveConfig(
                nextScope,
                enabled,
                nextInherited,
                nextSource,
                provider,
                baseUrl,
                apiKey,
                apiKeyPreview,
                apiKeySource,
                apiKeyEnvName,
                model,
                jsonOutputEnabled,
                thinkingEnabled,
                reasoningEffort,
                temperature,
                maxTokens,
                embeddingDimension,
                updatedAt,
                updatedBy
        );
    }

    public AiModelEffectiveConfig withOverrides(
            Boolean nextEnabled,
            String nextProvider,
            String nextBaseUrl,
            String nextApiKey,
            String nextApiKeyPreview,
            String nextApiKeySource,
            String nextApiKeyEnvName,
            String nextModel,
            Boolean nextJsonOutputEnabled,
            Boolean nextThinkingEnabled,
            String nextReasoningEffort,
            Double nextTemperature,
            Integer nextMaxTokens,
            Integer nextEmbeddingDimension
    ) {
        return new AiModelEffectiveConfig(
                scope,
                nextEnabled == null ? enabled : nextEnabled,
                inherited,
                source,
                nonBlank(nextProvider, provider),
                nonBlank(nextBaseUrl, baseUrl),
                nextApiKey == null ? apiKey : nextApiKey,
                nextApiKeyPreview == null ? apiKeyPreview : nextApiKeyPreview,
                nextApiKeySource == null ? apiKeySource : nextApiKeySource,
                nextApiKeyEnvName == null ? apiKeyEnvName : nextApiKeyEnvName,
                nonBlank(nextModel, model),
                nextJsonOutputEnabled == null ? jsonOutputEnabled : nextJsonOutputEnabled,
                nextThinkingEnabled == null ? thinkingEnabled : nextThinkingEnabled,
                nonBlank(nextReasoningEffort, reasoningEffort),
                nextTemperature == null ? temperature : nextTemperature,
                nextMaxTokens == null ? maxTokens : nextMaxTokens,
                nextEmbeddingDimension == null ? embeddingDimension : nextEmbeddingDimension,
                updatedAt,
                updatedBy
        );
    }

    private static String nonBlank(String value, String fallback) {
        return value == null || value.isBlank() ? fallback : value.trim();
    }
}
