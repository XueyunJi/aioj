package com.aioj.next.ai.domain.memory;

import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Set;

@Component
public class MemoryQualityGate {
    private static final Set<String> EXPLICIT_EVIDENCE = Set.of(
            "EXPLICIT",
            "EXPLICIT_REMEMBER",
            "EXPLICIT_PREFERENCE",
            "EXPLICIT_USER_PREFERENCE",
            "USER_MANUAL"
    );
    private static final Set<String> AUTO_ACTIVE_CATEGORIES = Set.of("PREFERENCE", "RULE");
    private static final Set<String> HIGH_IMPACT_CATEGORIES = Set.of("PROFILE", "WEAKNESS", "GOAL");
    private static final Set<String> HARD_REJECT_QUALITY_FLAGS = Set.of(
            "empty_memory",
            "not_long_term",
            "problem_noise",
            "raw_sample",
            "code_noise",
            "temporary_preference",
            "problem_or_session_scoped",
            "privacy_sensitive"
    );
    private static final Set<String> HARD_REJECT_AMBIGUITY_FLAGS = Set.of(
            "hypothetical",
            "quoted_or_third_party"
    );

    public GateResult evaluate(MemoryCandidate candidate, MessageContext context) {
        String text = normalize(candidate.canonicalText());
        String lower = text.toLowerCase(Locale.ROOT);
        String source = normalize(context.userMessage());
        String evidenceType = normalize(candidate.evidenceType()).toUpperCase(Locale.ROOT);
        List<String> qualityFlags = new ArrayList<>();
        List<String> ambiguityFlags = new ArrayList<>();

        String category = normalizeCategory(candidate.category(), candidate.memoryKey());
        String memoryKey = normalizeKey(candidate.memoryKey());
        String scopeType = normalizeScope(candidate.scopeType());
        String scopeId = blankToNull(candidate.scopeId());

        if (text.isBlank()) {
            qualityFlags.add("empty_memory");
            return rejected(category, memoryKey, scopeType, scopeId, "empty_memory", qualityFlags, ambiguityFlags);
        }
        if (!candidate.longTerm()) {
            qualityFlags.add("not_long_term");
        }
        if (containsAny(text, "本题", "这道题", "该题", "当前题", "题目里", "题目描述", "样例", "输入格式", "输出格式", "数据范围")) {
            qualityFlags.add("problem_noise");
        }
        if (containsAny(lower, "stdout", "stderr", "std out", "std err") || containsAny(text, "标准输出", "标准错误", "原始输出", "运行输出")) {
            qualityFlags.add("raw_sample");
        }
        if (containsAny(text, "当前代码", "上述代码", "这段代码", "变量", "函数", "报错", "栈", "trace")
                || text.contains("```")
                || looksLikeCode(lower)) {
            qualityFlags.add("code_noise");
        }
        if (containsAny(text, "这次", "当前", "现在先", "暂时", "这个问题里")) {
            qualityFlags.add("temporary_preference");
        }
        if (candidate.problemSpecific() || "PROBLEM".equals(scopeType) || "CONVERSATION".equals(scopeType) || "TEMPORARY".equals(scopeType)) {
            qualityFlags.add("problem_or_session_scoped");
        }
        if (candidate.hypothetical() || containsAny(source, "假设我", "假如我", "如果我是", "比如我", "角色扮演")) {
            ambiguityFlags.add("hypothetical");
        }
        if (candidate.quoted() || containsAny(source, "题目里", "老师说", "别人说", "引用", "假设的")) {
            ambiguityFlags.add("quoted_or_third_party");
        }
        if ("PROFILE".equals(category) && !looksLikeFirstPersonActual(source)) {
            ambiguityFlags.add("vague_first_person_claim");
        }
        if (looksSensitive(lower)) {
            qualityFlags.add("privacy_sensitive");
        }

        if (containsHardReject(qualityFlags, ambiguityFlags)) {
            return rejected(category, memoryKey, scopeType, scopeId, firstReason(qualityFlags, ambiguityFlags), qualityFlags, ambiguityFlags);
        }

        double confidence = clamp(candidate.confidence());
        boolean explicitEvidence = EXPLICIT_EVIDENCE.contains(evidenceType);
        boolean repeatedEvidence = "REPEATED_BEHAVIOR".equals(evidenceType);
        if (confidence < 0.70) {
            qualityFlags.add("low_confidence");
        }
        if (HIGH_IMPACT_CATEGORIES.contains(category)) {
            ambiguityFlags.add(highImpactFlag(category));
        }
        double explicitness = explicitEvidence ? 1.0 : repeatedEvidence ? 0.68 : 0.35;
        double stability = ("GLOBAL".equals(scopeType) || "PROBLEM_TAG".equals(scopeType)) ? 1.0 : 0.55;
        double userControl = "USER_MANUAL".equals(evidenceType) ? 1.0 : 0.75;
        double riskPenalty = (qualityFlags.isEmpty() ? 0 : 0.10) + (ambiguityFlags.isEmpty() ? 0 : 0.16);
        double score = Math.max(0, 0.35 * confidence + 0.25 * explicitness + 0.20 * 0.65 + 0.10 * stability + 0.10 * userControl - riskPenalty);
        boolean needsConfirmation = candidate.needsConfirmation()
                || HIGH_IMPACT_CATEGORIES.contains(category)
                || ambiguityFlags.contains("vague_first_person_claim")
                || !qualityFlags.isEmpty()
                || !ambiguityFlags.isEmpty();
        String status;
        if (score >= 0.86 && explicitEvidence && !needsConfirmation && AUTO_ACTIVE_CATEGORIES.contains(category)) {
            status = "ACTIVE";
        } else if (score >= 0.70 || needsConfirmation) {
            status = needsConfirmation ? "NEEDS_CONFIRMATION" : "CANDIDATE";
        } else {
            status = "CANDIDATE";
        }
        return new GateResult(true, needsConfirmation, category, memoryKey, scopeType, scopeId,
                round(score), qualityFlags, ambiguityFlags, "", status);
    }

    private GateResult rejected(
            String category,
            String memoryKey,
            String scopeType,
            String scopeId,
            String reason,
            List<String> qualityFlags,
            List<String> ambiguityFlags
    ) {
        return new GateResult(false, false, category, memoryKey, scopeType, scopeId,
                0, List.copyOf(qualityFlags), List.copyOf(ambiguityFlags), reason, "REJECTED");
    }

    private boolean containsHardReject(List<String> qualityFlags, List<String> ambiguityFlags) {
        return qualityFlags.stream().anyMatch(HARD_REJECT_QUALITY_FLAGS::contains)
                || ambiguityFlags.stream().anyMatch(HARD_REJECT_AMBIGUITY_FLAGS::contains);
    }

    private String highImpactFlag(String category) {
        return switch (category) {
            case "PROFILE" -> "profile_needs_confirmation";
            case "WEAKNESS" -> "high_impact_weakness";
            case "GOAL" -> "high_impact_goal";
            default -> "needs_confirmation";
        };
    }

    private String normalizeCategory(String category, String key) {
        String value = normalize(category).toUpperCase(Locale.ROOT);
        if (Set.of("PROFILE", "RULE", "PREFERENCE", "HABIT", "WEAKNESS", "GOAL", "MANUAL_NOTE", "SYSTEM_LEARNING").contains(value)) {
            return value;
        }
        return switch (normalize(key).toLowerCase(Locale.ROOT)) {
            case "preferred_language", "guidance_preference", "pace_preference", "teaching_style" -> "PREFERENCE";
            case "name_preference", "skill_level" -> "PROFILE";
            case "weakness" -> "WEAKNESS";
            case "learning_direction" -> "GOAL";
            case "code_style", "debugging_preference" -> "HABIT";
            case "rule" -> "RULE";
            default -> "MANUAL_NOTE";
        };
    }

    private String normalizeScope(String value) {
        String normalized = normalize(value).toUpperCase(Locale.ROOT);
        if (Set.of("GLOBAL", "COURSE", "CLASS", "PROBLEM_TAG", "PROBLEM", "CONVERSATION", "TEMPORARY").contains(normalized)) {
            return normalized;
        }
        return "GLOBAL";
    }

    private String normalizeKey(String value) {
        String normalized = normalize(value).toLowerCase(Locale.ROOT)
                .replaceAll("[^a-z0-9_\\u4e00-\\u9fa5]+", "_")
                .replaceAll("_+", "_")
                .replaceAll("^_|_$", "");
        return normalized.isBlank() ? "manual_note" : truncate(normalized, 96);
    }

    private boolean looksLikeFirstPersonActual(String text) {
        if (text.isBlank()) {
            return false;
        }
        return containsAny(text, "我叫", "叫我", "我是", "我正在", "我准备", "我的目标", "以后叫我")
                && !containsAny(text, "假设", "假如", "如果", "比如", "开玩笑", "题目里");
    }

    private boolean looksLikeCode(String lower) {
        return containsAny(lower, "#include", "using namespace", "public class", "def ", "console.log", "system.out")
                || lower.matches("(?s).*\\b(int|long|double|string|vector|map|set)\\s+[a-zA-Z_][a-zA-Z0-9_]*.*");
    }

    private boolean looksSensitive(String lower) {
        return containsAny(lower, "password", "token", "secret", "api key", "apikey", "cookie", "private key",
                "密码", "令牌", "密钥", "手机号", "身份证", "验证码");
    }

    private String firstReason(List<String> qualityFlags, List<String> ambiguityFlags) {
        if (!qualityFlags.isEmpty()) {
            return qualityFlags.get(0);
        }
        if (!ambiguityFlags.isEmpty()) {
            return ambiguityFlags.get(0);
        }
        return "rejected";
    }

    private boolean containsAny(String value, String... needles) {
        for (String needle : needles) {
            if (value.contains(needle)) {
                return true;
            }
        }
        return false;
    }

    private String normalize(String value) {
        return value == null ? "" : value.replace("\r\n", "\n").replace('\r', '\n').trim();
    }

    private String blankToNull(String value) {
        String normalized = normalize(value);
        return normalized.isBlank() ? null : truncate(normalized, 128);
    }

    private String truncate(String value, int max) {
        return value.length() <= max ? value : value.substring(0, max);
    }

    private double clamp(double value) {
        return Math.min(1, Math.max(0, value));
    }

    private double round(double value) {
        return Math.round(value * 10_000.0) / 10_000.0;
    }

    public record MemoryCandidate(
            String category,
            String memoryKey,
            String canonicalText,
            String valueJson,
            String scopeType,
            String scopeId,
            String evidenceType,
            double confidence,
            boolean longTerm,
            boolean problemSpecific,
            boolean hypothetical,
            boolean quoted,
            boolean needsConfirmation
    ) {
    }

    public record MessageContext(String userMessage, String assistantMessage) {
    }

    public record GateResult(
            boolean accepted,
            boolean needsConfirmation,
            String normalizedCategory,
            String normalizedKey,
            String scopeType,
            String scopeId,
            double writeScore,
            List<String> qualityFlags,
            List<String> ambiguityFlags,
            String rejectedReason,
            String status
    ) {
    }
}
