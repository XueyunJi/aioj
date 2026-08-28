package com.aioj.next.contract.ai;

public record PlagiarismAnalysisResponse(
        String analysis,
        String provider,
        String model,
        long promptTokens,
        long completionTokens,
        boolean success,
        String errorMessage
) {
}
