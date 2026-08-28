package com.aioj.next.ai.domain.problem;

public record VerificationWarning(
        String code,
        String message,
        String field
) {
}
