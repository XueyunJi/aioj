package com.aioj.next.ai.domain.problem;

import com.aioj.next.contract.problem.TestCaseDto;

import java.util.List;

public record ProblemDraftRepairPatch(
        List<String> changedFields,
        String title,
        String difficulty,
        String statement,
        String notes,
        String standardSolutionLanguage,
        String standardSolutionCode,
        String referenceSolutionLanguage,
        String referenceSolutionCode,
        String testcaseGeneratorPython,
        String stressTestcaseGeneratorPython,
        String generationPlan,
        List<String> tags,
        List<TestCaseDto> testCases,
        Integer timeLimitMillis,
        Integer memoryLimitKb,
        String repairReason,
        String model,
        long promptTokens,
        long completionTokens
) {
    public ProblemDraftRepairPatch {
        changedFields = changedFields == null ? List.of() : List.copyOf(changedFields);
        tags = tags == null ? null : List.copyOf(tags);
        testCases = testCases == null ? null : List.copyOf(testCases);
        promptTokens = Math.max(0, promptTokens);
        completionTokens = Math.max(0, completionTokens);
    }

    public static ProblemDraftRepairPatch empty(String repairReason) {
        return new ProblemDraftRepairPatch(
                List.of(),
                null,
                null,
                null,
                null,
                null,
                null,
                null,
                null,
                null,
                null,
                null,
                null,
                null,
                null,
                null,
                repairReason,
                null,
                0,
                0
        );
    }
}
