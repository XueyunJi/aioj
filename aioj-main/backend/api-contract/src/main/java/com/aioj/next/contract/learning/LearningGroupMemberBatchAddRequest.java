package com.aioj.next.contract.learning;

import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.Size;

import java.util.List;

public record LearningGroupMemberBatchAddRequest(
        @NotEmpty @Size(max = 100) List<Long> userIds,
        LearningGroupMemberRole role
) {
}
