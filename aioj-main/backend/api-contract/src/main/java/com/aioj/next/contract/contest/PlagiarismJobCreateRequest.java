package com.aioj.next.contract.contest;

import java.util.List;

public record PlagiarismJobCreateRequest(
        List<Long> contestProblemIds,
        List<String> languages,
        Double minimumSimilarity,
        Boolean includeAiAnalysis
) {
}
