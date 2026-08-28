package com.aioj.next.contract.contest;

import com.aioj.next.contract.problem.Difficulty;
import com.aioj.next.contract.problem.ProblemVisibility;
import com.fasterxml.jackson.annotation.JsonInclude;

import java.time.Instant;
import java.util.List;

public record ContestRunProblemSnapshotResponse(
        Long id,
        Long contestId,
        Long contestRunId,
        Long contestProblemId,
        Long problemId,
        String label,
        String displayTitle,
        String statement,
        @JsonInclude(JsonInclude.Include.NON_NULL)
        String notes,
        @JsonInclude(JsonInclude.Include.NON_NULL)
        List<String> tags,
        @JsonInclude(JsonInclude.Include.NON_NULL)
        Difficulty difficulty,
        Integer timeLimitMillis,
        Integer memoryLimitKb,
        Integer score,
        ContestProblemScoringMode scoringMode,
        Integer sortOrder,
        @JsonInclude(JsonInclude.Include.NON_NULL)
        ProblemVisibility visibility,
        Instant createdAt
) {
}
