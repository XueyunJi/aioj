package com.aioj.next.ai.domain.context;

import java.util.List;

public record AiIntentAnalysis(
        UserIntent primaryIntent,
        List<UserIntent> secondaryIntents,
        AnswerStyle answerStyle,
        ClarificationMode clarificationMode,
        boolean wantsCode,
        boolean wantsCodeFirst,
        boolean wantsStepByStepCodeExplanation,
        boolean isClarificationAnswer,
        boolean hasNewDemandAfterClarification,
        boolean shouldUseLongTermMemory,
        boolean shouldWriteLongTermMemory,
        String latestUserDemand,
        String memoryUsagePolicy,
        String reason,
        double confidence
) {
    public AiIntentAnalysis {
        primaryIntent = primaryIntent == null ? UserIntent.UNKNOWN : primaryIntent;
        secondaryIntents = secondaryIntents == null ? List.of() : List.copyOf(secondaryIntents);
        answerStyle = answerStyle == null ? AnswerStyle.ADAPTIVE_ASSISTANCE : answerStyle;
        clarificationMode = clarificationMode == null ? ClarificationMode.ALLOW_BLOCKING_ONLY : clarificationMode;
        latestUserDemand = latestUserDemand == null ? "" : latestUserDemand.trim();
        memoryUsagePolicy = memoryUsagePolicy == null ? "" : memoryUsagePolicy.trim();
        reason = reason == null ? "" : reason.trim();
        confidence = Math.max(0, Math.min(1, confidence));
    }

    public static AiIntentAnalysis fallback() {
        return new AiIntentAnalysis(
                UserIntent.UNKNOWN,
                List.of(),
                AnswerStyle.ADAPTIVE_ASSISTANCE,
                ClarificationMode.ALLOW_BLOCKING_ONLY,
                false,
                false,
                false,
                false,
                false,
                true,
                false,
                "",
                "Use recalled memory only as hidden tutoring preference, not as visible user text.",
                "fallback",
                0.3
        );
    }

    public boolean blocksHelpfulClarification() {
        return clarificationMode == ClarificationMode.NONE
                || clarificationMode == ClarificationMode.ALLOW_BLOCKING_ONLY
                || wantsCodeFirst
                || primaryIntent == UserIntent.DIRECT_SOLUTION_WITH_CODE;
    }

    public enum UserIntent {
        DIRECT_SOLUTION_WITH_CODE,
        SOLUTION_EXPLANATION,
        SOCRATIC_HINT,
        DEBUG_CODE,
        DEBUG_SELECTED_CONTENT,
        APPLY_KNOWN_ALGORITHM_TO_PROBLEM,
        BOUNDARY_CASE_ANALYSIS,
        CODE_OPTIMIZATION,
        CONCEPT_EXPLANATION,
        CLARIFY_PROBLEM_INFO,
        CLARIFICATION_ANSWER,
        GENERAL_LEARNING_QA,
        SMALL_TALK,
        UNKNOWN
    }

    public enum AnswerStyle {
        CODE_FIRST_THEN_EXPLAIN,
        EXPLAIN_THEN_CODE,
        STEP_BY_STEP_HINT,
        DIRECT_EXPLANATION,
        DEBUG_GUIDED,
        ADAPTIVE_ASSISTANCE
    }

    public enum ClarificationMode {
        NONE,
        ALLOW_BLOCKING_ONLY,
        ALLOW_HELPFUL,
        REQUIRE_CLARIFICATION
    }
}
