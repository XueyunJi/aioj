package com.aioj.next.contract.problem;

import java.time.Instant;

public record ProblemSolutionResponse(
        Long id,
        Long problemId,
        String language,
        String content,
        Instant createdAt
) {
}
