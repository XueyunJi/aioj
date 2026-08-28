package com.aioj.next.contract.contest;

import com.fasterxml.jackson.annotation.JsonInclude;
import jakarta.validation.constraints.Size;

public record FairnessAlertUpdateRequest(
        @JsonInclude(JsonInclude.Include.NON_NULL)
        FairnessAlertStatus status,
        @JsonInclude(JsonInclude.Include.NON_NULL)
        @Size(max = 500)
        String teacherNote
) {
}
