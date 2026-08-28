package com.aioj.next.contract.problem;

import java.time.Instant;
import java.util.List;

public record ProblemResponse(
        Long id,
        String title,
        Difficulty difficulty,
        String statement,
        String notes,
        List<String> tags,
        List<TestCaseDto> samples,
        int timeLimitMillis,
        ProblemLanguageTimeLimitMultipliers languageTimeLimitMultipliers,
        int memoryLimitKb,
        boolean aiGenerated,
        ProblemVisibility visibility,
        Instant createdAt,
        Instant archivedAt,
        Instant deletedAt,
        Long deletedBy
) {
}
