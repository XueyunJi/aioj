package com.aioj.next.ai.domain.problem;

import java.util.List;

public record CrossCheckReport(
        String status,
        int caseCount,
        List<CrossCheckMismatch> mismatches,
        List<VerificationError> errors,
        List<VerificationWarning> warnings
) {
    public CrossCheckReport {
        mismatches = mismatches == null ? List.of() : List.copyOf(mismatches);
        errors = errors == null ? List.of() : List.copyOf(errors);
        warnings = warnings == null ? List.of() : List.copyOf(warnings);
    }

    public boolean passed() {
        return errors.isEmpty() && mismatches.isEmpty();
    }

    public static CrossCheckReport passed(int caseCount) {
        return new CrossCheckReport("PASSED", Math.max(0, caseCount), List.of(), List.of(), List.of());
    }

    public static CrossCheckReport failed(int caseCount, List<CrossCheckMismatch> mismatches,
                                          List<VerificationError> errors, List<VerificationWarning> warnings) {
        return new CrossCheckReport("FAILED", Math.max(0, caseCount), mismatches, errors, warnings);
    }
}
