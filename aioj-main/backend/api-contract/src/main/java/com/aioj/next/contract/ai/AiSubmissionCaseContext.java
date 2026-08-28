package com.aioj.next.contract.ai;

public record AiSubmissionCaseContext(
        Integer caseIndex,
        String caseName,
        String status,
        Double score,
        Double maxScore,
        Integer timeMillis,
        Integer memoryKb,
        String message
) {
}
