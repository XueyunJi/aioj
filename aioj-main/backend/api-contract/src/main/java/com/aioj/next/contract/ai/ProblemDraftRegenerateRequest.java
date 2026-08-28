package com.aioj.next.contract.ai;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

public record ProblemDraftRegenerateRequest(
        @NotBlank @Size(max = 500) String feedback
) {
}
