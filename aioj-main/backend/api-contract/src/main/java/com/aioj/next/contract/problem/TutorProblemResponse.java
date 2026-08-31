package com.aioj.next.contract.problem;

import java.time.Instant;
import java.util.List;

/** Read-only public problem projection for the Tutor integration. */
public record TutorProblemResponse(
        Long problemId,
        String version,
        Instant updatedAt,
        String solveUrl,
        String title,
        Difficulty difficulty,
        String statement,
        String notes,
        List<String> tags,
        List<TestCaseDto> samples,
        int timeLimitMillis,
        int memoryLimitKb
) {
}
