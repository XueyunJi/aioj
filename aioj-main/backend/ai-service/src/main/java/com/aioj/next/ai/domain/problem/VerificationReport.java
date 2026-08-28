package com.aioj.next.ai.domain.problem;

import java.util.List;

public record VerificationReport(
        String status,
        List<VerificationError> errors,
        List<VerificationWarning> warnings
) {
    public VerificationReport {
        errors = errors == null ? List.of() : List.copyOf(errors);
        warnings = warnings == null ? List.of() : List.copyOf(warnings);
    }

    public boolean passed() {
        return errors.isEmpty();
    }

    public List<String> errorMessages() {
        return errors.stream().map(VerificationError::message).toList();
    }

    public List<String> warningMessages() {
        return warnings.stream().map(VerificationWarning::message).toList();
    }

    public static VerificationReport of(List<VerificationError> errors, List<VerificationWarning> warnings) {
        List<VerificationError> safeErrors = errors == null ? List.of() : errors;
        return new VerificationReport(safeErrors.isEmpty() ? "PASSED" : "FAILED", safeErrors, warnings);
    }
}
