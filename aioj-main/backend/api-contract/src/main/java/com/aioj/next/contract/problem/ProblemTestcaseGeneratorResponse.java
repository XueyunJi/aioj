package com.aioj.next.contract.problem;

import java.time.Instant;

public record ProblemTestcaseGeneratorResponse(
        Long id,
        Long problemId,
        String content,
        Instant createdAt,
        Instant updatedAt
) {
}
