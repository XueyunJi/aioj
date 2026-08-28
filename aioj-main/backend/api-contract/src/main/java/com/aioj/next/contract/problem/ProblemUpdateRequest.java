package com.aioj.next.contract.problem;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

import java.util.List;

public record ProblemUpdateRequest(
        @NotBlank @Size(max = 120) String title,
        @NotNull Difficulty difficulty,
        @NotBlank String statement,
        List<String> tags,
        @NotEmpty List<@Valid TestCaseDto> testCases,
        int timeLimitMillis,
        int memoryLimitKb,
        @Size(max = 20000) String notes,
        @Size(max = 32) String standardSolutionLanguage,
        @Size(max = 40000) String standardSolutionCode,
        List<@Valid ProblemStandardSolutionPayload> standardSolutions,
        @Valid ProblemLanguageTimeLimitMultipliers languageTimeLimitMultipliers,
        @Size(max = 40000) String testcaseGeneratorPython,
        ProblemVisibility visibility
) {
}
