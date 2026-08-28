package com.aioj.next.contract.contest;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotEmpty;

import java.util.List;

public record ContestProblemBatchUpdateRequest(
        @NotEmpty List<@Valid ContestProblemRequest> problems
) {
}
