package com.aioj.next.ai.domain.problem;

import java.util.List;

public record ProblemDesignFitCheck(
        Boolean matched,
        Boolean algorithmMatched,
        Boolean ratingMatched,
        Boolean constraintsMatched,
        List<String> violations,
        List<String> suggestedFixes
) {
    public ProblemDesignFitCheck {
        violations = violations == null ? List.of() : List.copyOf(violations);
        suggestedFixes = suggestedFixes == null ? List.of() : List.copyOf(suggestedFixes);
    }

    public boolean explicitlyRejected() {
        return Boolean.FALSE.equals(matched)
                || Boolean.FALSE.equals(algorithmMatched)
                || Boolean.FALSE.equals(ratingMatched)
                || Boolean.FALSE.equals(constraintsMatched)
                || !violations.isEmpty();
    }
}
