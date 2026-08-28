package com.aioj.next.contract.contest;

import com.fasterxml.jackson.annotation.JsonInclude;

import java.time.Instant;

public record PlagiarismPairResponse(
        Long id,
        Long jobId,
        Long contestId,
        Long contestProblemId,
        Long problemId,
        String problemLabel,
        String problemTitle,
        String language,
        Long leftSubmissionId,
        Long rightSubmissionId,
        Long leftParticipantId,
        Long rightParticipantId,
        Long leftUserId,
        Long rightUserId,
        String leftAccountSnapshot,
        String leftDisplayNameSnapshot,
        String rightAccountSnapshot,
        String rightDisplayNameSnapshot,
        double similarity,
        double maximalSimilarity,
        double minimalSimilarity,
        int matchedTokens,
        PlagiarismRiskLevel riskLevel,
        PlagiarismReviewStatus reviewStatus,
        @JsonInclude(JsonInclude.Include.NON_NULL)
        String teacherNote,
        PlagiarismAiStatus aiStatus,
        @JsonInclude(JsonInclude.Include.NON_NULL)
        String aiSummary,
        Instant createdAt,
        Instant updatedAt
) {
}
