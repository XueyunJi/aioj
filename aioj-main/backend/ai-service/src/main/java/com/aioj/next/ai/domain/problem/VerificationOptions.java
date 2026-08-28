package com.aioj.next.ai.domain.problem;

import com.aioj.next.contract.ai.ProblemDraftRequest;

import java.util.List;

public record VerificationOptions(
        List<String> allowedTags,
        String requestedDifficulty,
        Integer cfRating,
        Integer targetHiddenCaseCount,
        boolean enableReferenceCheck
) {
    public VerificationOptions {
        allowedTags = allowedTags == null ? List.of() : List.copyOf(allowedTags);
    }

    public VerificationOptions(List<String> allowedTags, String requestedDifficulty, Integer cfRating,
                               Integer targetHiddenCaseCount) {
        this(allowedTags, requestedDifficulty, cfRating, targetHiddenCaseCount, false);
    }

    public static VerificationOptions defaults(List<String> allowedTags) {
        return new VerificationOptions(allowedTags, null, null, null, false);
    }

    public static VerificationOptions fromRequest(ProblemDraftRequest request, List<String> allowedTags) {
        if (request == null) {
            return defaults(allowedTags);
        }
        boolean referenceCheck = ReferenceCheckPolicy.enabled(request);
        return new VerificationOptions(allowedTags, request.difficulty(), request.cfRating(),
                request.targetHiddenCaseCount(), referenceCheck);
    }
}
