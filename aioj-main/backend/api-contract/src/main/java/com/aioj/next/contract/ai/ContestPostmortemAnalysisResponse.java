package com.aioj.next.contract.ai;

public record ContestPostmortemAnalysisResponse(
        String markdown,
        String provider,
        String model,
        long promptTokens,
        long completionTokens,
        boolean success,
        String errorMessage
) {
}
