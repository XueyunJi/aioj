package com.aioj.next.contract.contest;

public record PlagiarismFragmentResponse(
        Long id,
        Long pairId,
        int sequenceNo,
        int leftStartToken,
        int rightStartToken,
        int tokenLength,
        String leftExcerpt,
        String rightExcerpt
) {
}
