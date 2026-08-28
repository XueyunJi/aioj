package com.aioj.next.contract.contest;

public record ContestClarificationReplyRequest(
        String answer,
        ContestClarificationVisibility visibility
) {
}
