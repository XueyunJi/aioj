package com.aioj.next.ai.domain.problem;

import java.util.List;

public record ComplexityReport(
        String status,
        String claimedComplexity,
        String inferredComplexity,
        List<ComplexityBenchmarkRun> benchmarkRuns,
        List<VerificationError> errors,
        List<VerificationWarning> warnings
) {
    public ComplexityReport {
        benchmarkRuns = benchmarkRuns == null ? List.of() : List.copyOf(benchmarkRuns);
        errors = errors == null ? List.of() : List.copyOf(errors);
        warnings = warnings == null ? List.of() : List.copyOf(warnings);
    }

    public boolean passed() {
        return errors.isEmpty();
    }

    public static ComplexityReport of(String claimedComplexity, String inferredComplexity,
                                      List<ComplexityBenchmarkRun> benchmarkRuns,
                                      List<VerificationError> errors,
                                      List<VerificationWarning> warnings) {
        List<VerificationError> safeErrors = errors == null ? List.of() : errors;
        return new ComplexityReport(safeErrors.isEmpty() ? "PASSED" : "FAILED",
                claimedComplexity, inferredComplexity, benchmarkRuns, safeErrors, warnings);
    }
}
