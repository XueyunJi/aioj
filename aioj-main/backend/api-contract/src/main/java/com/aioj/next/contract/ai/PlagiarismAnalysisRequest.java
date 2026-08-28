package com.aioj.next.contract.ai;

import java.util.List;

public record PlagiarismAnalysisRequest(
        Long actorUserId,
        Long contestId,
        String contestTitle,
        Long jobId,
        Long pairId,
        String problemLabel,
        String problemTitle,
        String language,
        String riskLevel,
        double similarity,
        Participant left,
        Participant right,
        List<Fragment> fragments
) {
    public record Participant(
            Long userId,
            Long participantId,
            Long submissionId,
            String accountSnapshot,
            String displayNameSnapshot
    ) {
    }

    public record Fragment(
            int sequenceNo,
            int tokenLength,
            String leftExcerpt,
            String rightExcerpt
    ) {
    }
}
