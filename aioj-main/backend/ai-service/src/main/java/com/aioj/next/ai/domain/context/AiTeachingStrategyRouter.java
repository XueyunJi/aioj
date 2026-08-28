package com.aioj.next.ai.domain.context;

import com.aioj.next.contract.ai.AiChatRequest;
import org.springframework.stereotype.Component;

import java.util.Locale;
import java.util.Map;

@Component
public class AiTeachingStrategyRouter {
    public StrategyDecision route(AiChatRequest request, Map<String, Object> state) {
        String message = normalize(request == null ? "" : request.message());
        String lower = message.toLowerCase(Locale.ROOT);
        boolean hasProblem = hasProblemInfo(request, state);
        boolean hasSelection = request != null && request.selectionContext() != null;
        String sourceType = hasSelection ? normalize(request.selectionContext().sourceType()).toLowerCase(Locale.ROOT) : "";
        String intent = hasSelection ? normalize(request.selectionContext().uiIntent()).toLowerCase(Locale.ROOT) : "";

        if (containsAny(lower, "只提示", "不要完整答案", "不要直接给完整答案", "先不要给代码", "先别给代码")) {
            return decision(TeachingStrategy.SOCRATIC_HINT, "explicit_hint_preference");
        }
        if (hasSelection && (sourceType.contains("code") || intent.contains("debug") || containsAny(lower, "哪里错", "为什么错", "是不是", "编译错误", "wa", "wrong answer"))) {
            return decision(TeachingStrategy.DEBUG_SELECTED_CODE, "selected_code_focus");
        }
        if (containsAny(lower,
                "这些算法我都知道",
                "算法我都知道",
                "我已经知道这些算法",
                "不会在这道题里应用",
                "不会应用到这题",
                "怎么应用到这题")) {
            return decision(TeachingStrategy.APPLY_KNOWN_ALGORITHM_TO_PROBLEM, "bridge_known_algorithm_to_problem");
        }
        if (containsAny(lower,
                "先给代码",
                "直接给代码",
                "完整正确代码",
                "完整代码",
                "给我代码",
                "给出代码",
                "先代码",
                "按照代码讲",
                "先给出代码")) {
            return hasProblem
                    ? decision(TeachingStrategy.DIRECT_CODE_THEN_EXPLAIN, "explicit_code_request")
                    : decision(TeachingStrategy.ASK_FOR_PROBLEM_INFO, "code_requested_without_problem");
        }
        if (looksLikeCodeFirstExplanationRequest(lower)) {
            return hasProblem
                    ? decision(TeachingStrategy.DIRECT_CODE_THEN_EXPLAIN, "composite_code_and_explanation_request")
                    : decision(TeachingStrategy.ASK_FOR_PROBLEM_INFO, "code_requested_without_problem");
        }
        if (containsAny(lower, "优化这段", "优化代码", "时间复杂度太高", "能不能更快")) {
            return decision(TeachingStrategy.CODE_OPTIMIZATION, "optimization_request");
        }
        if (containsAny(lower, "边界", "单调性", "取左边界", "取右边界")) {
            return decision(TeachingStrategy.BOUNDARY_CASE_ANALYSIS, "boundary_case_request");
        }
        if (containsAny(lower, "解释", "为什么", "原理", "概念")) {
            return decision(TeachingStrategy.CONCEPT_EXPLANATION, "concept_explanation_request");
        }
        if (!hasProblem && containsAny(lower, "怎么入手", "这题", "题目")) {
            return decision(TeachingStrategy.ASK_FOR_PROBLEM_INFO, "problem_missing");
        }
        return decision(TeachingStrategy.SOCRATIC_HINT, "default_hint");
    }

    public boolean blocksHelpfulClarification(StrategyDecision decision, AiChatRequest request) {
        String message = normalize(request == null ? "" : request.message()).toLowerCase(Locale.ROOT);
        return decision.strategy() == TeachingStrategy.DIRECT_CODE_THEN_EXPLAIN
                || containsAny(message, "不要反问", "别再问我", "别问我", "不要问我", "先别问");
    }

    public String policyBlock(StrategyDecision decision) {
        return switch (decision.strategy()) {
            case DIRECT_CODE_THEN_EXPLAIN -> """
                    - Strategy: DIRECT_CODE_THEN_EXPLAIN.
                    - The user explicitly wants code. If problem information is sufficient, give complete code first, then explain the code by sections.
                    - Do not ask helpful clarification questions before answering.
                    """;
            case APPLY_KNOWN_ALGORITHM_TO_PROBLEM -> """
                    - Strategy: APPLY_KNOWN_ALGORITHM_TO_PROBLEM.
                    - The user says they know the algorithms but cannot apply them here. Do not re-teach generic definitions.
                    - Map the algorithm to this problem's variables, state/check function, boundaries, and complexity.
                    """;
            case DEBUG_SELECTED_CODE -> """
                    - Strategy: DEBUG_SELECTED_CODE.
                    - Prioritize the selected code or selected assistant explanation. Explain the concrete mismatch or bug.
                    - Do not give a generic algorithm overview unless it is needed for this selected span.
                    """;
            case CODE_OPTIMIZATION -> "- Strategy: CODE_OPTIMIZATION. Focus on bottlenecks, complexity, and concrete changes.\n";
            case BOUNDARY_CASE_ANALYSIS -> "- Strategy: BOUNDARY_CASE_ANALYSIS. Focus on edge cases, monotonicity, and binary-search boundaries.\n";
            case CONCEPT_EXPLANATION -> "- Strategy: CONCEPT_EXPLANATION. Explain the selected/current concept briefly, then apply it to the current problem.\n";
            case ASK_FOR_PROBLEM_INFO -> "- Strategy: ASK_FOR_PROBLEM_INFO. Ask for the minimum blocking problem information only.\n";
            case SOCRATIC_HINT -> "- Strategy: SOCRATIC_HINT. Give one useful hint step, unless the user explicitly asks for more detail.\n";
        };
    }

    private StrategyDecision decision(TeachingStrategy strategy, String reason) {
        return new StrategyDecision(strategy, reason);
    }

    @SuppressWarnings("unchecked")
    private boolean hasProblemInfo(AiChatRequest request, Map<String, Object> state) {
        if (request != null && (request.problemId() != null || request.problemContext() != null)) {
            return true;
        }
        Object problemValue = state == null ? null : state.get("problem");
        if (problemValue instanceof Map<?, ?> problem) {
            Object title = problem.get("title");
            Object statement = problem.get("statementSummary");
            Object constraints = problem.get("constraints");
            return hasText(title) || hasText(statement) || (constraints instanceof Iterable<?> iterable && iterable.iterator().hasNext());
        }
        return false;
    }

    private boolean hasText(Object value) {
        return value != null && !String.valueOf(value).isBlank();
    }

    private boolean containsAny(String value, String... needles) {
        for (String needle : needles) {
            if (needle != null && !needle.isBlank() && value.contains(needle)) {
                return true;
            }
        }
        return false;
    }

    private boolean looksLikeCodeFirstExplanationRequest(String value) {
        if (value == null || value.isBlank()) {
            return false;
        }
        boolean mentionsCode = value.contains("代码") || value.contains("实现") || value.contains("程序");
        boolean asksForAnswer = containsAny(value, "给我", "给出", "写", "提供", "能不能", "可以", "需要你", "帮我");
        boolean asksForExplanation = containsAny(value, "思路", "讲解", "解释", "按照", "详细", "逐步");
        boolean asksCodeQuestion = value.contains("代码吗") || value.contains("代码？") || value.contains("代码?");
        return mentionsCode && (asksCodeQuestion || (asksForAnswer && asksForExplanation));
    }

    private String normalize(String value) {
        return value == null ? "" : value.replace("\r\n", "\n").replace('\r', '\n').trim();
    }

    public enum TeachingStrategy {
        SOCRATIC_HINT,
        DIRECT_CODE_THEN_EXPLAIN,
        DEBUG_SELECTED_CODE,
        APPLY_KNOWN_ALGORITHM_TO_PROBLEM,
        CONCEPT_EXPLANATION,
        BOUNDARY_CASE_ANALYSIS,
        CODE_OPTIMIZATION,
        ASK_FOR_PROBLEM_INFO
    }

    public record StrategyDecision(TeachingStrategy strategy, String reason) {
    }
}
