package com.aioj.next.ai.domain.problem;

public record CrossCheckMismatch(
        String name,
        String input,
        String standardOutput,
        String referenceOutput
) {
}
