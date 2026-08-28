package com.aioj.next.ai.domain.problem;

public record ProblemDraftStressGeneratorResult(
        String stressTestcaseGeneratorPython,
        String model,
        long promptTokens,
        long completionTokens
) {
    public ProblemDraftStressGeneratorResult {
        promptTokens = Math.max(0, promptTokens);
        completionTokens = Math.max(0, completionTokens);
    }

    public static ProblemDraftStressGeneratorResult empty(String model) {
        return new ProblemDraftStressGeneratorResult(null, model, 0, 0);
    }
}
