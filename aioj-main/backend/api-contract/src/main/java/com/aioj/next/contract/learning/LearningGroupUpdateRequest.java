package com.aioj.next.contract.learning;

import jakarta.validation.constraints.Size;

public record LearningGroupUpdateRequest(
        @Size(max = 160) String name,
        @Size(max = 1000) String description,
        LearningGroupStatus status
) {
}
