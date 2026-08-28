package com.aioj.next.ai.domain.problem;

import java.util.List;

public record ProblemDesignPlan(
        String title,
        String difficulty,
        String coreAlgorithm,
        List<String> secondaryAlgorithms,
        String coreObservation,
        String constraints,
        String expectedTimeComplexity,
        String expectedMemoryComplexity,
        List<String> boundaryCases,
        List<String> commonWrongApproaches,
        List<String> proofObligations,
        Integer estimatedCfRating,
        List<String> tags,
        Integer timeLimitMillis,
        Integer memoryLimitKb
) {
    public ProblemDesignPlan {
        secondaryAlgorithms = secondaryAlgorithms == null ? List.of() : List.copyOf(secondaryAlgorithms);
        boundaryCases = boundaryCases == null ? List.of() : List.copyOf(boundaryCases);
        commonWrongApproaches = commonWrongApproaches == null ? List.of() : List.copyOf(commonWrongApproaches);
        proofObligations = proofObligations == null ? List.of() : List.copyOf(proofObligations);
        tags = tags == null ? List.of() : List.copyOf(tags);
    }
}
