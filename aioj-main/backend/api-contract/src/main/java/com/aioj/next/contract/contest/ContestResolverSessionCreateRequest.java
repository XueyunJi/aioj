package com.aioj.next.contract.contest;

import jakarta.validation.constraints.Size;

public record ContestResolverSessionCreateRequest(
        @Size(max = 200)
        String title
) {
}
