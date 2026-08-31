package com.aioj.next.contract.problem;

import java.math.BigDecimal;

/** A public problem recommendation for the currently authenticated AIOJ user. */
public record TutorRecommendationResponse(
        TutorProblemResponse problem,
        BigDecimal score,
        String reason
) {
}
