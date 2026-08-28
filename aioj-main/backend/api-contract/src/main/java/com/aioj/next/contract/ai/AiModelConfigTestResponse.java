package com.aioj.next.contract.ai;

public record AiModelConfigTestResponse(
        boolean success,
        String provider,
        String model,
        long latencyMillis,
        long promptTokens,
        long completionTokens,
        String contentPreview,
        String errorMessage
) {
}
