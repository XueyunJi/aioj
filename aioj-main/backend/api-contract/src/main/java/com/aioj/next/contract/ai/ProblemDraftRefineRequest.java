package com.aioj.next.contract.ai;

import com.aioj.next.contract.problem.TestCaseDto;
import jakarta.validation.constraints.Size;

import java.util.List;

public record ProblemDraftRefineRequest(
        @Size(max = 120) String title,
        @Size(max = 32) String difficulty,
        @Size(max = 20000) String statement,
        @Size(max = 20000) String notes,
        @Size(max = 32) String standardSolutionLanguage,
        @Size(max = 40000) String standardSolutionCode,
        @Size(max = 32) String referenceSolutionLanguage,
        @Size(max = 40000) String referenceSolutionCode,
        @Size(max = 40000) String testcaseGeneratorPython,
        @Size(max = 40000) String stressTestcaseGeneratorPython,
        @Size(max = 5000) String generationPlan,
        List<@Size(max = 40) String> tags,
        List<TestCaseDto> testCases,
        Integer timeLimitMillis,
        Integer memoryLimitKb,
        @Size(max = 500) String refineNote
) {
    public ProblemDraftRefineRequest(
            String title,
            String difficulty,
            String statement,
            String notes,
            String standardSolutionLanguage,
            String standardSolutionCode,
            String testcaseGeneratorPython,
            String generationPlan,
            List<@Size(max = 40) String> tags,
            List<TestCaseDto> testCases,
            Integer timeLimitMillis,
            Integer memoryLimitKb,
            @Size(max = 500) String refineNote
    ) {
        this(title, difficulty, statement, notes, standardSolutionLanguage, standardSolutionCode,
                null, null, testcaseGeneratorPython, null, generationPlan, tags, testCases, timeLimitMillis,
                memoryLimitKb, refineNote);
    }

    public ProblemDraftRefineRequest(
            String title,
            String difficulty,
            String statement,
            String notes,
            String standardSolutionLanguage,
            String standardSolutionCode,
            String referenceSolutionLanguage,
            String referenceSolutionCode,
            String testcaseGeneratorPython,
            String generationPlan,
            List<@Size(max = 40) String> tags,
            List<TestCaseDto> testCases,
            Integer timeLimitMillis,
            Integer memoryLimitKb,
            @Size(max = 500) String refineNote
    ) {
        this(title, difficulty, statement, notes, standardSolutionLanguage, standardSolutionCode,
                referenceSolutionLanguage, referenceSolutionCode, testcaseGeneratorPython, null, generationPlan, tags,
                testCases, timeLimitMillis, memoryLimitKb, refineNote);
    }
}
