package com.aioj.next.contract.ai;

public record StudentPostmortemCodeReference(
        Long submissionId,
        Long contestProblemId,
        String problemLabel,
        String problemTitle,
        String language,
        String status,
        Long submittedAtContestMillis,
        Integer codeChars,
        String codeExcerpt
) {
}
