package com.aioj.next.ai.domain.problem;

public record ProblemDraftPlanningResult(
        String rawJson,
        ProblemDesignPlan problemDesignPlan,
        ProblemDesignFitCheck fitCheck
) {
}
