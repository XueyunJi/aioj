package com.aioj.next.ai.domain.problem;

import java.util.List;

public record AlgorithmFitCheckResult(
        boolean passed,
        List<String> errors,
        List<String> warnings
) {
    public AlgorithmFitCheckResult {
        errors = errors == null ? List.of() : List.copyOf(errors);
        warnings = warnings == null ? List.of() : List.copyOf(warnings);
    }

    public static AlgorithmFitCheckResult passed(List<String> warnings) {
        return new AlgorithmFitCheckResult(true, List.of(), warnings);
    }

    public static AlgorithmFitCheckResult failed(List<String> errors, List<String> warnings) {
        return new AlgorithmFitCheckResult(false, errors, warnings);
    }
}
