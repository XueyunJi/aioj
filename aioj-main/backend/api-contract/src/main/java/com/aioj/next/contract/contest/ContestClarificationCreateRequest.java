package com.aioj.next.contract.contest;

public record ContestClarificationCreateRequest(
        Long contestProblemId,
        String question
) {
}
