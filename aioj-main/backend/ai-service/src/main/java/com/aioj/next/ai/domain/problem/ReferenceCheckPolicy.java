package com.aioj.next.ai.domain.problem;

import com.aioj.next.contract.ai.ProblemDraftRequest;

public final class ReferenceCheckPolicy {
    public static final int DEFAULT_CF_RATING_THRESHOLD = 1600;
    public static final String HIGH_RATING_DISABLED_PROGRESS_MESSAGE = "REFERENCE_CHECK_DISABLED_FOR_HIGH_RATING";

    private ReferenceCheckPolicy() {
    }

    public static boolean enabled(ProblemDraftRequest request) {
        if (request == null) {
            return false;
        }
        if (request.enableReferenceCheck() != null) {
            return Boolean.TRUE.equals(request.enableReferenceCheck());
        }
        return request.cfRating() != null && request.cfRating() >= DEFAULT_CF_RATING_THRESHOLD;
    }

    public static boolean highRatingExplicitlyDisabled(ProblemDraftRequest request) {
        return request != null
                && Boolean.FALSE.equals(request.enableReferenceCheck())
                && request.cfRating() != null
                && request.cfRating() >= DEFAULT_CF_RATING_THRESHOLD;
    }
}
