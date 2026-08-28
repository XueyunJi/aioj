package com.aioj.next.ai.domain.problem;

public record OfficialTestcaseCaseReport(
        String name,
        String sourceInputPath,
        String inputPath,
        String outputPath,
        Long inputBytes,
        Long outputBytes,
        String inputSha256,
        String outputSha256,
        String status,
        Long timeMillis,
        Long memoryKb,
        String message
) {
}
