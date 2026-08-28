package com.aioj.next.ai.domain.context;

import com.aioj.next.ai.persistence.entity.AiMessageEntity;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

@Component
public class ConversationCompressionService {
    private static final int UNCOMPACTED_TURN_THRESHOLD = 10;

    private final ConversationStateMerger stateMerger;
    private final ObjectMapper objectMapper;

    public ConversationCompressionService(ConversationStateMerger stateMerger, ObjectMapper objectMapper) {
        this.stateMerger = stateMerger;
        this.objectMapper = objectMapper;
    }

    public boolean shouldCompact(List<AiMessageEntity> messages, String stateJson, boolean hasKeyClarificationAnswer) {
        return (messages != null && messages.size() >= UNCOMPACTED_TURN_THRESHOLD)
                || hasKeyClarificationAnswer;
    }

    public CompressionResult compact(String stateJson, List<AiMessageEntity> messages) {
        Map<String, Object> state = stateMerger.readState(stateJson);
        List<ScoredSegment> scored = scoreSegments(messages == null ? List.of() : messages);
        List<Map<String, Object>> kept = new ArrayList<>();
        collectStateSegments(state, kept);
        scored.stream()
                .filter(segment -> segment.score >= 0.35)
                .limit(12)
                .forEach(segment -> kept.add(keptSegment(segment.messageId, "recent_" + normalizeRole(segment.role), segment.score, segment.preview)));

        Map<String, Object> structured = new LinkedHashMap<>();
        structured.put("problem", state.get("problem"));
        structured.put("algorithmState", state.get("algorithmState"));
        structured.put("clarificationAnswers", map(state, "clarifications").get("answers"));
        structured.put("unresolvedQuestions", map(state, "learningFlow").get("userStuckPoints"));
        structured.put("userKnownPoints", map(state, "learningFlow").get("userKnownPoints"));
        structured.put("userStuckPoints", map(state, "learningFlow").get("userStuckPoints"));
        structured.put("codeState", state.get("codeState"));
        structured.put("keptSegments", kept);

        String narrative = buildNarrative(state, kept);
        double salienceScore = averageKeptScore(kept);
        int tokenEstimate = estimateTextTokens(narrative + "\n" + writeJson(structured));
        List<Warning> warnings = compressionWarnings(state, structured, kept, narrative, salienceScore, tokenEstimate);
        boolean valid = verifyCriticalSlots(state, structured) && warnings.stream().noneMatch(Warning::blocksWrite);
        Map<String, Object> meta = new LinkedHashMap<>();
        meta.put("salienceScore", salienceScore);
        meta.put("tokenEstimate", tokenEstimate);
        meta.put("valid", valid);
        meta.put("warnings", warnings.stream().map(Warning::asMap).toList());
        structured.put("_meta", meta);
        structured.put("salienceScore", salienceScore);
        structured.put("tokenEstimate", tokenEstimate);
        structured.put("valid", valid);
        structured.put("warnings", warnings.stream().map(Warning::asMap).toList());
        return new CompressionResult(narrative, writeJson(structured), salienceScore, tokenEstimate, valid, warnings);
    }

    public List<ScoredSegment> scoreSegments(List<AiMessageEntity> messages) {
        List<ScoredSegment> scored = new ArrayList<>();
        if (messages == null) {
            return scored;
        }
        int total = Math.max(1, messages.size());
        for (int i = 0; i < messages.size(); i++) {
            AiMessageEntity message = messages.get(i);
            String content = message.getContent() == null ? "" : message.getContent();
            String lower = content.toLowerCase(Locale.ROOT);
            double score = 0.07 * ((i + 1.0) / total);
            if (containsAny(lower, "题目", "输入", "输出", "约束", "数据范围", "n <=", "m <=", "星港", "最大化最小")) score += 0.22;
            if (containsAny(lower, "已补充", "回答", "二分", "检查", "可行")) score += 0.18;
            if (containsAny(lower, "不对", "纠正", "我说的是", "不是")) score += 0.15;
            if (containsAny(lower, "不知道", "不会", "卡住", "不理解")) score += 0.12;
            if (containsAny(lower, "#include", "public class", "def ", "wa", "tle", "re", "compile", "报错")) score += 0.10;
            if (containsAny(lower, "贪心", "check", "单调", "边界", "dp", "状态")) score += 0.08;
            if (containsAny(lower, "理解题目", "寻找规律", "仔细阅读", "初步思路")) score -= 0.15;
            if (content.length() < 12) score -= 0.08;
            scored.add(new ScoredSegment(message.getId(), message.getRole(), truncate(content, 360), Math.max(0, Math.min(1, score))));
        }
        return scored;
    }

    public boolean verifyCriticalSlots(Map<String, Object> originalState, Map<String, Object> structuredSummary) {
        Map<String, Object> problem = map(originalState, "problem");
        if (hasText(problem.get("statementSummary")) && !hasText(map(structuredSummary.get("problem")).get("statementSummary"))) {
            return false;
        }
        if (!list(problem.get("constraints")).isEmpty() && list(map(structuredSummary.get("problem")).get("constraints")).isEmpty()) {
            return false;
        }
        Map<String, Object> code = map(originalState, "codeState");
        if (code.get("latestCodeSnapshotId") != null && map(structuredSummary.get("codeState")).get("latestCodeSnapshotId") == null) {
            return false;
        }
        Map<String, Object> flow = map(originalState, "learningFlow");
        return !list(flow.get("doNotRepeatQuestions")).isEmpty()
                || list(map(originalState, "clarifications").get("answers")).isEmpty()
                || !list(structuredSummary.get("clarificationAnswers")).isEmpty();
    }

    private void collectStateSegments(Map<String, Object> state, List<Map<String, Object>> kept) {
        Map<String, Object> problem = map(state, "problem");
        Map<String, Object> flow = map(state, "learningFlow");
        Map<String, Object> algorithm = map(state, "algorithmState");
        Map<String, Object> code = map(state, "codeState");
        if (hasText(problem.get("statementSummary"))) {
            kept.add(keptSegment(null, "problem_statement", 0.92, String.valueOf(problem.get("statementSummary"))));
        }
        if (!list(problem.get("constraints")).isEmpty()) {
            kept.add(keptSegment(null, "problem_constraints", 0.90, String.valueOf(problem.get("constraints"))));
        }
        if (hasText(algorithm.get("candidateApproach"))) {
            kept.add(keptSegment(null, "algorithm_decision", 0.82, String.valueOf(algorithm.get("candidateApproach"))));
        }
        if (!list(map(state, "clarifications").get("answers")).isEmpty()) {
            kept.add(keptSegment(null, "clarification_answer", 0.88, String.valueOf(map(state, "clarifications").get("answers"))));
        }
        if (!list(flow.get("userStuckPoints")).isEmpty()) {
            kept.add(keptSegment(null, "unresolved_stuck_point", 0.78, String.valueOf(flow.get("userStuckPoints"))));
        }
        if (code.get("latestCodeSnapshotId") != null) {
            kept.add(keptSegment(null, "code_snapshot_ref", 0.80, "latestCodeSnapshotId=" + code.get("latestCodeSnapshotId")));
        }
    }

    private Map<String, Object> keptSegment(Long messageId, String reason, double salience, String preview) {
        Map<String, Object> segment = new LinkedHashMap<>();
        segment.put("messageId", messageId);
        segment.put("reason", reason);
        segment.put("salience", Math.max(0, Math.min(1, salience)));
        segment.put("preview", truncate(preview, 320));
        return segment;
    }

    private String buildNarrative(Map<String, Object> state, List<Map<String, Object>> kept) {
        Map<String, Object> problem = map(state, "problem");
        Map<String, Object> flow = map(state, "learningFlow");
        Map<String, Object> algorithm = map(state, "algorithmState");
        StringBuilder narrative = new StringBuilder();
        if (hasText(problem.get("statementSummary"))) {
            narrative.append("当前题目：").append(problem.get("statementSummary")).append('\n');
        }
        if (hasText(algorithm.get("candidateApproach"))) {
            narrative.append("算法方向：").append(algorithm.get("candidateApproach")).append('\n');
        }
        if (!list(flow.get("userKnownPoints")).isEmpty()) {
            narrative.append("用户已理解：").append(flow.get("userKnownPoints")).append('\n');
        }
        if (!list(flow.get("userStuckPoints")).isEmpty()) {
            narrative.append("用户仍卡住：").append(flow.get("userStuckPoints")).append('\n');
        }
        if (!kept.isEmpty()) {
            narrative.append("高价值片段：").append(kept.stream()
                    .limit(8)
                    .map(segment -> segment.get("reason") + "=" + segment.get("preview"))
                    .toList()).append('\n');
        }
        return narrative.toString().trim();
    }

    private int estimateTextTokens(String text) {
        return Math.max(1, (text == null ? 0 : text.length()) / 4);
    }

    private double averageKeptScore(List<Map<String, Object>> kept) {
        if (kept == null || kept.isEmpty()) {
            return 0;
        }
        return kept.stream()
                .map(segment -> segment.get("salience"))
                .filter(Number.class::isInstance)
                .map(Number.class::cast)
                .mapToDouble(Number::doubleValue)
                .average()
                .orElse(0);
    }

    private List<Warning> compressionWarnings(
            Map<String, Object> state,
            Map<String, Object> structured,
            List<Map<String, Object>> kept,
            String narrative,
            double salienceScore,
            int tokenEstimate
    ) {
        List<Warning> warnings = new ArrayList<>();
        Map<String, Object> problem = map(state, "problem");
        if (hasText(problem.get("statementSummary")) && list(problem.get("constraints")).isEmpty()) {
            warnings.add(new Warning("WARN", "MISSING_CONSTRAINTS", "Problem statement exists but constraints are missing.", true));
        }
        if (kept == null || kept.isEmpty()) {
            warnings.add(new Warning("WARN", "EMPTY_KEPT_SEGMENTS", "No high-value segment survived compression.", true));
        }
        if (!hasText(narrative)) {
            warnings.add(new Warning("WARN", "EMPTY_NARRATIVE_SUMMARY", "Narrative summary is empty.", true));
        }
        if (salienceScore <= 0) {
            warnings.add(new Warning("WARN", "ZERO_SALIENCE_SCORE", "Salience score is zero.", true));
        }
        if (tokenEstimate <= 0) {
            warnings.add(new Warning("WARN", "ZERO_TOKEN_ESTIMATE", "Token estimate is zero.", true));
        }
        if (!verifyCriticalSlots(state, structured)) {
            warnings.add(new Warning("WARN", "CRITICAL_SLOT_VERIFICATION_FAILED", "Compression would drop P0 conversation slots.", true));
        }
        return warnings;
    }

    private String normalizeRole(String role) {
        return role == null || role.isBlank() ? "message" : role.toLowerCase(Locale.ROOT).replaceAll("[^a-z0-9_]+", "_");
    }

    @SuppressWarnings("unchecked")
    private Map<String, Object> map(Object value) {
        if (value instanceof Map<?, ?> existing) {
            return (Map<String, Object>) existing;
        }
        return new LinkedHashMap<>();
    }

    private Map<String, Object> map(Map<String, Object> parent, String key) {
        return map(parent.get(key));
    }

    @SuppressWarnings("unchecked")
    private List<Object> list(Object value) {
        if (value instanceof List<?> existing) {
            return (List<Object>) existing;
        }
        return List.of();
    }

    private boolean hasText(Object value) {
        return value != null && !String.valueOf(value).isBlank();
    }

    private boolean containsAny(String value, String... needles) {
        if (value == null) return false;
        for (String needle : needles) {
            if (needle != null && !needle.isBlank() && value.contains(needle)) return true;
        }
        return false;
    }

    private String writeJson(Object value) {
        try {
            return objectMapper.writeValueAsString(value);
        } catch (Exception ignored) {
            return "{}";
        }
    }

    private String truncate(String value, int max) {
        String normalized = value == null ? "" : value.replace("\r\n", "\n").replace('\r', '\n').trim();
        return normalized.length() <= max ? normalized : normalized.substring(0, max) + "…";
    }

    public record ScoredSegment(Long messageId, String role, String preview, double score) {
    }

    public record CompressionResult(
            String narrativeSummary,
            String structuredSummaryJson,
            double salienceScore,
            int tokenEstimate,
            boolean valid,
            List<Warning> warnings
    ) {
    }

    public record Warning(String level, String code, String message, boolean blocksWrite) {
        public Map<String, Object> asMap() {
            Map<String, Object> map = new LinkedHashMap<>();
            map.put("level", level);
            map.put("code", code);
            map.put("message", message);
            map.put("blocksWrite", blocksWrite);
            return map;
        }
    }
}
