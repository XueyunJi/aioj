package com.aioj.next.contract.ai;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.Size;

import java.util.List;

public record ProblemDraftRequest(
        @NotBlank String topic,
        String difficulty,
        @Min(800) @Max(3500) Integer cfRating,
        @Size(max = 500) String teachingGoal,
        @Size(max = 200) String algorithm,
        List<String> tags,
        @Size(max = 500) String scenario,
        @Size(max = 1000) String inputOutputSpec,
        @Size(max = 1000) String dataConstraints,
        @Size(max = 1000) String qualityRequirements,
        @Size(max = 32) String standardSolutionLanguage,
        @Size(max = 1000) String problemInfoRequirement,
        @Size(max = 1000) String statementRequirement,
        @Size(max = 1000) String testcaseRequirement,
        Integer targetHiddenCaseCount,
        @Size(max = 1000) String solutionRequirement,
        @Size(max = 1000) String explanationRequirement,
        Boolean enableAutoRepair,
        Boolean enableReferenceCheck
) {
}
