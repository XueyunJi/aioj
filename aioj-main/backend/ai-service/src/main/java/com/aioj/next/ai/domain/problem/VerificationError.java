package com.aioj.next.ai.domain.problem;

public record VerificationError(
        String code,
        String message,
        String field
) {
}
