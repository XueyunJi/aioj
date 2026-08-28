package com.aioj.next.ai.domain.context;

import com.aioj.next.ai.domain.AiCompletion;
import com.aioj.next.contract.ai.AiChatRequest;
import org.springframework.stereotype.Component;

import java.util.Locale;
import java.util.Map;

@Component
public class ClarificationPolicy {
    public AiCompletion apply(
            AiCompletion completion,
            AiChatRequest request,
            Map<String, Object> state,
            AiTeachingStrategyRouter.StrategyDecision strategy
    ) {
        if (completion == null || !completion.hasClarification()) {
            return completion;
        }
        AiCompletion.Clarification clarification = completion.clarification();
        if (isBlocking(clarification)) {
            return completion;
        }
        if (strategy != null && shouldBlockHelpful(strategy, request)) {
            return withoutClarification(completion);
        }
        String question = normalize(firstNonBlank(clarification.prompt(), clarification.title())).toLowerCase(Locale.ROOT);
        if (asksForKnownProblemInfo(question, request, state) || asksForKnownCode(question, request, state)) {
            return withoutClarification(completion);
        }
        return completion;
    }

    private boolean shouldBlockHelpful(AiTeachingStrategyRouter.StrategyDecision strategy, AiChatRequest request) {
        String message = normalize(request == null ? "" : request.message()).toLowerCase(Locale.ROOT);
        return strategy.strategy() == AiTeachingStrategyRouter.TeachingStrategy.DIRECT_CODE_THEN_EXPLAIN
                || containsAny(message, "不要反问", "别再问我", "别问我", "不要问我", "先别问");
    }

    private boolean asksForKnownProblemInfo(String question, AiChatRequest request, Map<String, Object> state) {
        if (!hasProblemInfo(request, state)) {
            return false;
        }
        return containsAny(question, "题目", "题面", "输入输出", "数据范围", "约束", "完整描述", "problem statement", "constraints");
    }

    private boolean asksForKnownCode(String question, AiChatRequest request, Map<String, Object> state) {
        if (!hasCode(request, state)) {
            return false;
        }
        return containsAny(question, "代码", "源码", "完整代码", "current code", "source code");
    }

    @SuppressWarnings("unchecked")
    private boolean hasProblemInfo(AiChatRequest request, Map<String, Object> state) {
        if (request != null && (request.problemId() != null || request.problemContext() != null)) {
            return true;
        }
        Object value = state == null ? null : state.get("problem");
        if (value instanceof Map<?, ?> problem) {
            return hasText(problem.get("title")) || hasText(problem.get("statementSummary")) || hasText(problem.get("constraints"));
        }
        return false;
    }

    private boolean hasCode(AiChatRequest request, Map<String, Object> state) {
        if (request != null && request.codeContext() != null && hasText(request.codeContext().code())) {
            return true;
        }
        Object value = state == null ? null : state.get("codeState");
        if (value instanceof Map<?, ?> code) {
            return hasText(code.get("latestCodeSnapshotId")) || hasText(code.get("latestCodeMessageId")) || hasText(code.get("language"));
        }
        return false;
    }

    private AiCompletion withoutClarification(AiCompletion completion) {
        return new AiCompletion(
                completion.content(),
                completion.provider(),
                completion.model(),
                completion.promptTokens(),
                completion.completionTokens(),
                completion.teachingDecision(),
                completion.stuckLayer(),
                completion.studentLevel(),
                AiCompletion.Clarification.empty()
        );
    }

    private boolean isBlocking(AiCompletion.Clarification clarification) {
        return "blocking".equalsIgnoreCase(normalize(clarification == null ? null : clarification.priority()));
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

    private String firstNonBlank(String first, String second) {
        String firstText = normalize(first);
        return firstText.isBlank() ? normalize(second) : firstText;
    }

    private String normalize(String value) {
        return value == null ? "" : value.replace("\r\n", "\n").replace('\r', '\n').trim();
    }
}
