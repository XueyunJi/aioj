package com.aioj.next.contract.ai;

import java.util.List;

public record AiModelListResponse(
        String scope,
        String provider,
        String baseUrl,
        boolean apiKeyConfigured,
        String apiKeyEnvName,
        boolean manualAllowed,
        String fetchStatus,
        String errorMessage,
        List<ModelOption> models
) {
    public record ModelOption(
            String id,
            String ownedBy,
            boolean supportsJsonOutput,
            boolean supportsThinking,
            List<String> thinkingEffortModes,
            Double fixedTemperature,
            Double recommendedTemperature,
            Integer contextLength,
            boolean deprecated
    ) {
    }
}
