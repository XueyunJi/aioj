package com.aioj.next.contract.problem;

public record TestcasePackageCaseResponse(
        Long id,
        String name,
        String inputPath,
        String outputPath,
        boolean sample,
        String subtaskKey,
        Integer score,
        Long inputSizeBytes,
        Long outputSizeBytes,
        Integer sortOrder
) {
}
