package com.aioj.next.contract.contest;

import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

public record ContestProblemRequest(
        @NotNull Long problemId,
        @NotBlank @Size(max = 16) String label,
        @Size(max = 160) String displayTitle,
        @Min(0) int score,
        @Min(0) int sortOrder,
        ContestProblemScoringMode scoringMode
) {
}
