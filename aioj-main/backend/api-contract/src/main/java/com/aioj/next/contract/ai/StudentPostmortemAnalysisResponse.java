package com.aioj.next.contract.ai;

import java.util.List;

public record StudentPostmortemAnalysisResponse(
        String markdown,
        List<StudentPostmortemWeaknessSuggestion> weaknessCandidates,
        List<StudentPostmortemPracticeSuggestion> practiceSuggestions,
        String provider,
        String model,
        long promptTokens,
        long completionTokens,
        boolean success,
        String errorMessage
) {
}
