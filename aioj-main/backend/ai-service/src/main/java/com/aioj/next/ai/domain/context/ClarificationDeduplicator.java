package com.aioj.next.ai.domain.context;

import com.aioj.next.ai.domain.AiCompletion;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

@Component
public class ClarificationDeduplicator {
    private static final String STARPORT_CHECK_FOLLOW_UP = "对。那 check(d) 时为什么要选择最靠左的可行位置，而不是最远的位置？";

    public DedupDecision decide(AiCompletion.Clarification clarification, Map<String, Object> state) {
        String question = normalize(firstNonBlank(clarification == null ? null : clarification.prompt(), clarification == null ? null : clarification.title()));
        if (question.isBlank()) {
            return DedupDecision.keep();
        }
        Map<String, Object> flow = map(state.get("learningFlow"));
        Map<String, Object> clarifications = map(state.get("clarifications"));
        for (Object item : list(flow.get("doNotRepeatQuestions"))) {
            if (similarQuestion(question, String.valueOf(item))) {
                if (needsStarportCheckFollowUp(state, question)) {
                    return DedupDecision.followUp("answered_similar_but_incomplete", STARPORT_CHECK_FOLLOW_UP);
                }
                return DedupDecision.skip("similar_to_do_not_repeat_question");
            }
        }
        for (Object item : list(clarifications.get("answers"))) {
            Map<String, Object> answer = map(item);
            if (similarQuestion(question, String.valueOf(answer.get("question")))) {
                if (needsStarportCheckFollowUp(state, question)) {
                    return DedupDecision.followUp("answered_similar_but_incomplete", STARPORT_CHECK_FOLLOW_UP);
                }
                return DedupDecision.skip("similar_to_answered_clarification");
            }
        }
        for (Object item : list(clarifications.get("requests"))) {
            Map<String, Object> request = map(item);
            if (similarQuestion(question, String.valueOf(request.get("question")))) {
                return DedupDecision.skip("similar_to_pending_clarification");
            }
        }
        return DedupDecision.keep();
    }

    public boolean similarQuestion(String first, String second) {
        String a = normalizeForSimilarity(first);
        String b = normalizeForSimilarity(second);
        if (a.isBlank() || b.isBlank()) {
            return false;
        }
        if (a.equals(b) || a.contains(b) || b.contains(a)) {
            return true;
        }
        Set<String> firstTerms = importantTerms(a);
        Set<String> secondTerms = importantTerms(b);
        if (firstTerms.isEmpty() || secondTerms.isEmpty()) {
            return false;
        }
        Set<String> intersection = new LinkedHashSet<>(firstTerms);
        intersection.retainAll(secondTerms);
        Set<String> union = new LinkedHashSet<>(firstTerms);
        union.addAll(secondTerms);
        double jaccard = intersection.size() * 1.0 / Math.max(1, union.size());
        return jaccard >= 0.55
                || (intersection.contains("距离") && intersection.contains("检查"))
                || (intersection.contains("check") && intersection.contains("可行"))
                || (containsAny(a, "check", "检查", "判断") && containsAny(b, "check", "检查", "判断") && a.contains("可行") && b.contains("可行"));
    }

    private boolean needsStarportCheckFollowUp(Map<String, Object> state, String question) {
        String stateText = String.valueOf(state);
        String lower = normalize(question).toLowerCase(Locale.ROOT);
        return (stateText.contains("explain_greedy_check_d") || stateText.contains("最靠左可行位置"))
                && containsAny(lower, "检查", "check", "可行", "合理", "距离")
                && !containsAny(lower, "最靠左", "从左到右", "扫描", "最远");
    }

    private Set<String> importantTerms(String text) {
        Set<String> terms = new LinkedHashSet<>();
        addIfPresent(terms, text, "星港");
        addIfPresent(terms, text, "最小距离");
        addIfPresent(terms, text, "距离");
        addIfPresent(terms, text, "二分");
        addIfPresent(terms, text, "binary");
        addIfPresent(terms, text, "check");
        addIfPresent(terms, text, "检查");
        addIfPresent(terms, text, "可行");
        addIfPresent(terms, text, "合理");
        addIfPresent(terms, text, "选择");
        addIfPresent(terms, text, "选出");
        addIfPresent(terms, text, "m个");
        addIfPresent(terms, text, "贪心");
        addIfPresent(terms, text, "最靠左");
        addIfPresent(terms, text, "最远");
        addIfPresent(terms, text, "扫描");
        return terms;
    }

    private void addIfPresent(Set<String> terms, String text, String term) {
        if (text.contains(term)) {
            terms.add(term);
        }
    }

    @SuppressWarnings("unchecked")
    private Map<String, Object> map(Object value) {
        if (value instanceof Map<?, ?> existing) {
            return (Map<String, Object>) existing;
        }
        return Map.of();
    }

    @SuppressWarnings("unchecked")
    private List<Object> list(Object value) {
        if (value instanceof List<?> existing) {
            return (List<Object>) existing;
        }
        return List.of();
    }

    private boolean containsAny(String value, String... needles) {
        if (value == null) return false;
        for (String needle : needles) {
            if (needle != null && !needle.isBlank() && value.contains(needle)) return true;
        }
        return false;
    }

    private String normalizeForSimilarity(String value) {
        return normalize(value)
                .toLowerCase(Locale.ROOT)
                .replaceAll("[\\p{Punct}\\s，。？！、：；（）()【】《》“”\"']", "");
    }

    private String normalize(String value) {
        return value == null ? "" : value.replace("\r\n", "\n").replace('\r', '\n').trim();
    }

    private String firstNonBlank(String first, String second) {
        return first != null && !first.isBlank() ? first.trim() : normalize(second);
    }

    public record DedupDecision(
            boolean skip,
            boolean followUp,
            String reason,
            String followUpQuestion
    ) {
        public static DedupDecision keep() {
            return new DedupDecision(false, false, "keep", "");
        }

        public static DedupDecision skip(String reason) {
            return new DedupDecision(true, false, reason, "");
        }

        public static DedupDecision followUp(String reason, String question) {
            return new DedupDecision(false, true, reason, question);
        }
    }
}
