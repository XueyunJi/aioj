package com.aioj.next.contract.contest;

import java.util.List;

public record ContestResolverSessionDetailResponse(
        ContestResolverSessionResponse session,
        List<ContestResolverStepResponse> steps
) {
}
