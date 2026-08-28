package com.aioj.next.ai.domain.problem;

public record ComplexityBenchmarkRun(
        String name,
        String status,
        Long timeMillis,
        Long memoryKb,
        String message
) {
}
