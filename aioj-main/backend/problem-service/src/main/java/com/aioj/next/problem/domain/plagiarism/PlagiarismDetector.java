package com.aioj.next.problem.domain.plagiarism;

import java.util.List;

public interface PlagiarismDetector {
    List<DetectedPair> detect(DetectionGroup group, DetectionOptions options);

    record DetectionGroup(String language, List<DetectionSubmission> submissions) {
    }

    record DetectionSubmission(
            Long jobSubmissionId,
            Long submissionId,
            Long userId,
            Long participantId,
            String code
    ) {
    }

    record DetectionOptions(double minimumSimilarity, int maxExcerptChars) {
    }

    record DetectedPair(
            Long leftSubmissionId,
            Long rightSubmissionId,
            double similarity,
            double maximalSimilarity,
            double minimalSimilarity,
            int matchedTokens,
            List<DetectedFragment> fragments
    ) {
    }

    record DetectedFragment(
            int sequenceNo,
            int leftStartToken,
            int rightStartToken,
            int tokenLength,
            String leftExcerpt,
            String rightExcerpt
    ) {
    }
}
