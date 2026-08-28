package com.aioj.next.contract.contest;

import jakarta.validation.constraints.Size;

public record SubmissionCodeAccessRequest(
        @Size(max = 240) String reason
) {
}
