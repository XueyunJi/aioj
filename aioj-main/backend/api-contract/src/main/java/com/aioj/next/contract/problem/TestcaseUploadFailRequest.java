package com.aioj.next.contract.problem;

import jakarta.validation.constraints.Size;

public record TestcaseUploadFailRequest(
        @Size(max = 500) String message
) {
}
