package com.aioj.next.contract.problem;

import jakarta.validation.constraints.Size;

public record ProblemStandardSolutionPayload(
        @Size(max = 32) String language,
        @Size(max = 40000) String code
) {
}
