package com.aioj.next.ai.domain.context;

import com.aioj.next.ai.persistence.entity.AiConversationProblemEntity;
import com.aioj.next.contract.ai.AiChatRequest;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * W1.7 rule-based reference resolver ("第N题" / "上一批第N题" / "最后一题" ...).
 * Pure component: no database access and no side effects, so the same resolution can be
 * recomputed deterministically for manifest persistence and for prompt injection.
 *
 * Resolution priority (design doc §3):
 *   clarification answer (a previously issued ambiguity question) >
 *   selectionContext explicit problem > explicit title/problem id in message >
 *   active problem set set_ordinal > last problem set set_ordinal >
 *   conversation_ordinal (global Nth) > keyword/title fuzzy.
 *
 * Only rows that carry ordinals (set_id / set_ordinal / conversation_ordinal not null)
 * are addressable; legacy all-NULL rows keep the old behavior (never resolved).
 * Ambiguity is never silently bound: when more than one candidate survives, the
 * resolution is left empty and flagged {@code clarificationIssued} so the caller can
 * reuse the existing clarification infrastructure to ask the user.
 */
@Component
public class ReferenceResolver {
    /** Request-key prefix of resolver-issued clarification requests. */
    public static final String CLARIFICATION_KEY_PREFIX = "ref_resolve_";

    private static final String NUM = "([0-9]{1,3}|[一二三四五六七八九十])";
    private static final String PREV_QUALIFIER = "上一批|前一批";
    private static final String CURRENT_QUALIFIER = "这一批|这批|最近一批|最新一批";

    /**
     * "上一批的第2题" / "这批第3题" — batch qualifier with an explicit ordinal.
     * Whitespace between tokens is tolerated ("第 2 题" / "上一批的第 2 题") because real
     * users routinely space CJK and digits; without it the mention silently never matched.
     */
    private static final Pattern QUALIFIED_ORDINAL = Pattern.compile(
            "(" + PREV_QUALIFIER + "|" + CURRENT_QUALIFIER + ")的?\\s*第\\s*" + NUM + "\\s*(?:道|个)?\\s*题(?:目)?(?!库)");
    /** Bare batch reference without an ordinal: "上一批的题" / "这一批那道题". */
    private static final Pattern BATCH_LATEST = Pattern.compile(
            "(" + PREV_QUALIFIER + "|" + CURRENT_QUALIFIER + ")的?(?:那|这)?一?道?题(?:目)?(?!库)");
    /** "第2题" / "第二道题" / "第3个题" / "第 2 题". */
    private static final Pattern PLAIN_ORDINAL = Pattern.compile(
            "第\\s*" + NUM + "\\s*(?:道|个)?\\s*题(?:目)?(?!库)");
    /** Bare "第2个" (without 题), excluding common non-problem usages like "第2个测试点/样例". */
    private static final Pattern BARE_ORDINAL = Pattern.compile(
            "第\\s*" + NUM + "\\s*个(?![的]?测试|样例|数据|case|CASE|空|参数)");
    /** "最后一题" / "最新那道题" / "最近的题". */
    private static final Pattern LATEST = Pattern.compile(
            "(?:最后|最新|最近)(?:那|的)?一?(?:道|个)?题(?:目)?(?!库)");

    private static final Map<Character, Integer> CHINESE_NUMERALS = chineseNumerals();

    public Outcome resolve(Input input) {
        if (input == null) {
            return Outcome.empty();
        }
        // 0. The user is answering a resolver-issued ambiguity clarification: bind from the
        // candidates stored with that clarification request.
        if (input.clarificationAnswer() != null
                && input.clarificationAnswer().requestId() != null
                && input.clarificationAnswer().requestId().trim().startsWith(CLARIFICATION_KEY_PREFIX)) {
            Resolution fromAnswer = resolveFromClarificationAnswer(input.clarificationAnswer(), input.storedCandidates());
            return fromAnswer == null ? Outcome.empty() : new Outcome(List.of(fromAnswer));
        }

        List<AiConversationProblemEntity> rows = addressableRows(input.problems());
        List<Resolution> resolutions = new ArrayList<>();

        // 1. Explicit selection context problem.
        if (input.selectionProblem() != null) {
            Resolution selection = resolveSelection(input.selectionProblem(), rows);
            if (selection != null) {
                resolutions.add(selection);
            }
        }

        String message = input.message() == null ? "" : input.message();

        // 2. Explicit title / problem id in the message.
        if (!message.isBlank()) {
            List<AiConversationProblemEntity> explicit = explicitMatches(message, rows);
            if (explicit.size() == 1) {
                resolutions.add(resolved("explicit_name_or_id", mentionFor(explicit.get(0), message), explicit.get(0), 0.95));
            } else if (explicit.size() > 1) {
                resolutions.add(ambiguous("explicit_name_or_id", mentionFor(explicit.get(0), message), explicit));
            }
        }

        // 3-5. Ordinal / batch / latest mentions.
        for (Mention mention : extractMentions(message)) {
            resolutions.add(resolveMention(mention, input, rows));
        }
        return new Outcome(dedup(resolutions));
    }

    /**
     * Extracts reference mentions from the message. Overlap-safe: batch-qualified patterns
     * are consumed first so their span is not matched again by the plain ordinal pattern.
     */
    List<Mention> extractMentions(String message) {
        List<Mention> mentions = new ArrayList<>();
        if (message == null || message.isBlank()) {
            return mentions;
        }
        boolean[] consumed = new boolean[message.length()];
        collectQualified(message, consumed, mentions);
        collect(message, consumed, mentions, PLAIN_ORDINAL, Qualifier.NONE, false, 1);
        collect(message, consumed, mentions, BARE_ORDINAL, Qualifier.NONE, false, 1);
        collectLatest(message, consumed, mentions);
        return mentions;
    }

    private void collectQualified(String message, boolean[] consumed, List<Mention> mentions) {
        Matcher ordinal = QUALIFIED_ORDINAL.matcher(message);
        while (ordinal.find()) {
            Qualifier qualifier = qualifierOf(ordinal.group(1));
            mentions.add(new Mention(ordinal.group(), qualifier, parseNumber(ordinal.group(2)), false));
            mark(consumed, ordinal.start(), ordinal.end());
        }
        Matcher batch = BATCH_LATEST.matcher(message);
        while (batch.find()) {
            if (spansConsumed(consumed, batch.start(), batch.end())) {
                continue;
            }
            mentions.add(new Mention(batch.group(), qualifierOf(batch.group(1)), null, false));
            mark(consumed, batch.start(), batch.end());
        }
    }

    private void collect(String message, boolean[] consumed, List<Mention> mentions,
                         Pattern pattern, Qualifier qualifier, boolean latest, int numberGroup) {
        Matcher matcher = pattern.matcher(message);
        while (matcher.find()) {
            if (spansConsumed(consumed, matcher.start(), matcher.end())) {
                continue;
            }
            mentions.add(new Mention(matcher.group(), qualifier, parseNumber(matcher.group(numberGroup)), latest));
            mark(consumed, matcher.start(), matcher.end());
        }
    }

    private void collectLatest(String message, boolean[] consumed, List<Mention> mentions) {
        Matcher matcher = LATEST.matcher(message);
        while (matcher.find()) {
            if (spansConsumed(consumed, matcher.start(), matcher.end())) {
                continue;
            }
            mentions.add(new Mention(matcher.group(), Qualifier.NONE, null, true));
            mark(consumed, matcher.start(), matcher.end());
        }
    }

    private Resolution resolveMention(Mention mention, Input input, List<AiConversationProblemEntity> rows) {
        if (mention.latest()) {
            AiConversationProblemEntity latest = latestRow(rows);
            return latest == null
                    ? unresolved("latest_problem", mention.rawText())
                    : resolved("latest_problem", mention.rawText(), latest, 0.85);
        }
        if (mention.qualifier() == Qualifier.PREVIOUS) {
            AiConversationProblemEntity row = mention.ordinal() == null
                    ? latestInSet(input.lastProblemSetId(), rows)
                    : findInSet(input.lastProblemSetId(), mention.ordinal(), rows);
            return row == null
                    ? unresolved("last_set_ordinal", mention.rawText())
                    : resolved("last_set_ordinal", mention.rawText(), row, 0.9);
        }
        if (mention.qualifier() == Qualifier.CURRENT) {
            AiConversationProblemEntity row = mention.ordinal() == null
                    ? latestInSet(input.activeProblemSetId(), rows)
                    : findInSet(input.activeProblemSetId(), mention.ordinal(), rows);
            return row == null
                    ? unresolved("active_set_ordinal", mention.rawText())
                    : resolved("active_set_ordinal", mention.rawText(), row, 0.9);
        }
        // Unqualified "第N题": active set first, then the previous set; both -> ambiguity.
        Integer ordinal = mention.ordinal();
        AiConversationProblemEntity activeRow = findInSet(input.activeProblemSetId(), ordinal, rows);
        AiConversationProblemEntity lastRow = findInSet(input.lastProblemSetId(), ordinal, rows);
        boolean differentSets = input.activeProblemSetId() != null && input.lastProblemSetId() != null
                && !input.activeProblemSetId().equals(input.lastProblemSetId());
        if (activeRow != null && lastRow != null && differentSets) {
            return ambiguous("ambiguous_set_ordinal", mention.rawText(), List.of(activeRow, lastRow));
        }
        if (activeRow != null) {
            return resolved("active_set_ordinal", mention.rawText(), activeRow, 0.9);
        }
        if (lastRow != null) {
            return resolved("last_set_ordinal", mention.rawText(), lastRow, 0.85);
        }
        List<AiConversationProblemEntity> global = findByConversationOrdinal(ordinal, rows);
        if (global.size() == 1) {
            return resolved("conversation_ordinal", mention.rawText(), global.get(0), 0.8);
        }
        if (global.size() > 1) {
            return ambiguous("conversation_ordinal", mention.rawText(), global);
        }
        List<AiConversationProblemEntity> fuzzy = fuzzyMatches(mention.rawText(), rows);
        if (fuzzy.size() == 1) {
            return resolved("keyword_fuzzy", mention.rawText(), fuzzy.get(0), 0.6);
        }
        if (fuzzy.size() > 1) {
            return ambiguous("keyword_fuzzy", mention.rawText(), fuzzy);
        }
        return unresolved("unresolved", mention.rawText());
    }

    private Resolution resolveSelection(AiChatRequest.SelectedProblemContext selection, List<AiConversationProblemEntity> rows) {
        Long problemId = parseLong(selection.problemId());
        if (problemId != null) {
            for (AiConversationProblemEntity row : rows) {
                if (problemId.equals(row.getProblemId())) {
                    return resolved("selection_context", "selection:" + selection.problemId(), row, 1.0);
                }
            }
        }
        String title = selection.title();
        if (title != null && !title.isBlank()) {
            for (AiConversationProblemEntity row : rows) {
                if (title.trim().equals(row.getTitle())) {
                    return resolved("selection_context", "selection:" + title.trim(), row, 1.0);
                }
            }
        }
        return null;
    }

    private Resolution resolveFromClarificationAnswer(AiChatRequest.ClarificationAnswer answer, List<StoredCandidate> candidates) {
        if (candidates == null || candidates.isEmpty()) {
            return null;
        }
        StoredCandidate picked = null;
        List<String> selected = answer.selectedOptionIds() == null ? List.of() : answer.selectedOptionIds();
        for (String optionId : selected) {
            if (optionId == null) {
                continue;
            }
            for (StoredCandidate candidate : candidates) {
                if (optionId.trim().equals(candidate.optionLabel())) {
                    picked = candidate;
                    break;
                }
            }
            if (picked != null) {
                break;
            }
        }
        if (picked == null) {
            String text = answer.answerText() != null && !answer.answerText().isBlank()
                    ? answer.answerText()
                    : answer.customText();
            picked = matchCandidateByText(text, candidates);
        }
        if (picked == null) {
            return null;
        }
        AiConversationProblemEntity row = picked.entity();
        return new Resolution(
                "clarification_answer:" + answer.requestId().trim(),
                "clarification_answer",
                row == null ? picked.setId() : row.getSetId(),
                row == null ? picked.setOrdinal() : row.getSetOrdinal(),
                row == null ? picked.conversationOrdinal() : row.getConversationOrdinal(),
                row == null ? picked.problemId() : row.getProblemId(),
                picked.title(),
                0.95,
                List.of(),
                false,
                row
        );
    }

    private StoredCandidate matchCandidateByText(String text, List<StoredCandidate> candidates) {
        if (text == null || text.isBlank()) {
            return null;
        }
        String normalized = text.trim();
        for (int i = 0; i < candidates.size(); i++) {
            StoredCandidate candidate = candidates.get(i);
            if (candidate.title() != null && !candidate.title().isBlank() && normalized.contains(candidate.title())) {
                return candidate;
            }
        }
        Matcher index = Pattern.compile("第?\\s*([0-9]{1,2}|[一二三四五六七八九十])\\s*个?").matcher(normalized);
        if (index.find()) {
            Integer ordinalIndex = parseNumber(index.group(1));
            if (ordinalIndex != null && ordinalIndex >= 1 && ordinalIndex <= candidates.size()) {
                return candidates.get(ordinalIndex - 1);
            }
        }
        return null;
    }

    /** Only rows with ordinals assigned are addressable by the resolver (W1.7 null-compat). */
    private List<AiConversationProblemEntity> addressableRows(List<AiConversationProblemEntity> problems) {
        if (problems == null || problems.isEmpty()) {
            return List.of();
        }
        List<AiConversationProblemEntity> rows = new ArrayList<>();
        for (AiConversationProblemEntity row : problems) {
            if (row != null && row.getConversationOrdinal() != null) {
                rows.add(row);
            }
        }
        return rows;
    }

    private List<AiConversationProblemEntity> explicitMatches(String message, List<AiConversationProblemEntity> rows) {
        Map<Long, AiConversationProblemEntity> matches = new LinkedHashMap<>();
        for (AiConversationProblemEntity row : rows) {
            if (row.getProblemId() != null && containsToken(message, String.valueOf(row.getProblemId()))) {
                matches.put(rowKey(row), row);
                continue;
            }
            String title = row.getTitle();
            if (title != null && title.trim().length() >= 2 && message.contains(title.trim())) {
                matches.put(rowKey(row), row);
            }
        }
        return new ArrayList<>(matches.values());
    }

    private List<AiConversationProblemEntity> fuzzyMatches(String rawMention, List<AiConversationProblemEntity> rows) {
        // Rule-level fuzzy: the mention (or its core fragment) is contained in the title, or
        // a long enough title fragment appears in the mention.
        String needle = rawMention.replaceAll("[第道个题目这那的了,，。？?！!\\s]", "");
        List<AiConversationProblemEntity> matches = new ArrayList<>();
        for (AiConversationProblemEntity row : rows) {
            String title = row.getTitle();
            if (title == null || title.isBlank()) {
                continue;
            }
            String normalizedTitle = title.trim();
            if (!needle.isBlank() && needle.length() >= 2 && normalizedTitle.contains(needle)) {
                matches.add(row);
                continue;
            }
            for (int length = Math.min(6, normalizedTitle.length()); length >= 4; length--) {
                boolean hit = false;
                for (int start = 0; start + length <= normalizedTitle.length(); start++) {
                    String fragment = normalizedTitle.substring(start, start + length);
                    if (rawMention.contains(fragment)) {
                        matches.add(row);
                        hit = true;
                        break;
                    }
                }
                if (hit) {
                    break;
                }
            }
        }
        return matches;
    }

    private AiConversationProblemEntity findInSet(String setId, Integer ordinal, List<AiConversationProblemEntity> rows) {
        if (setId == null || setId.isBlank() || ordinal == null) {
            return null;
        }
        for (AiConversationProblemEntity row : rows) {
            if (setId.equals(row.getSetId()) && ordinal.equals(row.getSetOrdinal())) {
                return row;
            }
        }
        return null;
    }

    private AiConversationProblemEntity latestInSet(String setId, List<AiConversationProblemEntity> rows) {
        if (setId == null || setId.isBlank()) {
            return null;
        }
        AiConversationProblemEntity latest = null;
        for (AiConversationProblemEntity row : rows) {
            if (!setId.equals(row.getSetId()) || row.getSetOrdinal() == null) {
                continue;
            }
            if (latest == null || row.getSetOrdinal() > latest.getSetOrdinal()) {
                latest = row;
            }
        }
        return latest;
    }

    private List<AiConversationProblemEntity> findByConversationOrdinal(Integer ordinal, List<AiConversationProblemEntity> rows) {
        if (ordinal == null) {
            return List.of();
        }
        List<AiConversationProblemEntity> matches = new ArrayList<>();
        for (AiConversationProblemEntity row : rows) {
            if (ordinal.equals(row.getConversationOrdinal())) {
                matches.add(row);
            }
        }
        return matches;
    }

    private AiConversationProblemEntity latestRow(List<AiConversationProblemEntity> rows) {
        AiConversationProblemEntity latest = null;
        for (AiConversationProblemEntity row : rows) {
            if (row.getConversationOrdinal() == null) {
                continue;
            }
            if (latest == null || row.getConversationOrdinal() > latest.getConversationOrdinal()) {
                latest = row;
            }
        }
        return latest;
    }

    private Resolution resolved(String resolver, String rawText, AiConversationProblemEntity row, double confidence) {
        return new Resolution(
                rawText,
                resolver,
                row.getSetId(),
                row.getSetOrdinal(),
                row.getConversationOrdinal(),
                row.getProblemId(),
                row.getTitle(),
                confidence,
                List.of(),
                false,
                row
        );
    }

    private Resolution ambiguous(String resolver, String rawText, List<AiConversationProblemEntity> candidates) {
        List<Candidate> alternatives = candidates.stream().map(ReferenceResolver::candidate).toList();
        return new Resolution(rawText, resolver, null, null, null, null, null, 0.0, alternatives, true, null);
    }

    private Resolution unresolved(String resolver, String rawText) {
        return new Resolution(rawText, resolver, null, null, null, null, null, 0.0, List.of(), false, null);
    }

    static Candidate candidate(AiConversationProblemEntity row) {
        return new Candidate(row.getId(), row.getProblemId(), row.getTitle(), row.getSetId(), row.getSetOrdinal(), row.getConversationOrdinal());
    }

    private List<Resolution> dedup(List<Resolution> resolutions) {
        Map<String, Resolution> byKey = new LinkedHashMap<>();
        for (Resolution resolution : resolutions) {
            String key = resolution.resolvedProblemId() != null
                    ? "row:" + resolution.resolvedProblemId()
                    : "text:" + resolution.rawText() + ":" + resolution.resolver();
            Resolution existing = byKey.get(key);
            if (existing == null || resolution.confidence() > existing.confidence()) {
                byKey.put(key, resolution);
            }
        }
        return new ArrayList<>(byKey.values());
    }

    private String mentionFor(AiConversationProblemEntity row, String message) {
        if (row.getProblemId() != null && containsToken(message, String.valueOf(row.getProblemId()))) {
            return String.valueOf(row.getProblemId());
        }
        return row.getTitle() == null ? "" : row.getTitle().trim();
    }

    private boolean containsToken(String message, String token) {
        if (token == null || token.isBlank()) {
            return false;
        }
        int index = message.indexOf(token);
        while (index >= 0) {
            int end = index + token.length();
            boolean startOk = index == 0 || !Character.isDigit(message.charAt(index - 1));
            boolean endOk = end >= message.length() || !Character.isDigit(message.charAt(end));
            if (startOk && endOk) {
                return true;
            }
            index = message.indexOf(token, index + 1);
        }
        return false;
    }

    private Qualifier qualifierOf(String text) {
        if (text != null && text.matches("(?:" + PREV_QUALIFIER + ")")) {
            return Qualifier.PREVIOUS;
        }
        return Qualifier.CURRENT;
    }

    static Integer parseNumber(String value) {
        if (value == null || value.isBlank()) {
            return null;
        }
        String normalized = value.trim();
        if (normalized.chars().allMatch(Character::isDigit)) {
            try {
                return Integer.parseInt(normalized);
            } catch (NumberFormatException ex) {
                return null;
            }
        }
        if (normalized.length() == 1) {
            return CHINESE_NUMERALS.get(normalized.charAt(0));
        }
        return null;
    }

    private Long parseLong(String value) {
        if (value == null || value.isBlank()) {
            return null;
        }
        try {
            return Long.parseLong(value.trim());
        } catch (NumberFormatException ex) {
            return null;
        }
    }

    private long rowKey(AiConversationProblemEntity row) {
        return row.getId() == null ? -System.identityHashCode(row) : row.getId();
    }

    private void mark(boolean[] consumed, int start, int end) {
        for (int i = Math.max(0, start); i < Math.min(consumed.length, end); i++) {
            consumed[i] = true;
        }
    }

    private boolean spansConsumed(boolean[] consumed, int start, int end) {
        for (int i = Math.max(0, start); i < Math.min(consumed.length, end); i++) {
            if (consumed[i]) {
                return true;
            }
        }
        return false;
    }

    private static Map<Character, Integer> chineseNumerals() {
        Map<Character, Integer> map = new LinkedHashMap<>();
        map.put('一', 1);
        map.put('二', 2);
        map.put('三', 3);
        map.put('四', 4);
        map.put('五', 5);
        map.put('六', 6);
        map.put('七', 7);
        map.put('八', 8);
        map.put('九', 9);
        map.put('十', 10);
        return Map.copyOf(map);
    }

    enum Qualifier {
        NONE,
        PREVIOUS,
        CURRENT
    }

    record Mention(String rawText, Qualifier qualifier, Integer ordinal, boolean latest) {
    }

    /** One ambiguity alternative / stored clarification candidate. */
    public record Candidate(Long rowId, Long problemId, String title, String setId, Integer setOrdinal, Integer conversationOrdinal) {
    }

    /** Candidate reloaded from a resolver-issued clarification request on the answer turn. */
    public record StoredCandidate(
            String optionLabel,
            Long rowId,
            Long problemId,
            String title,
            String setId,
            Integer setOrdinal,
            Integer conversationOrdinal,
            AiConversationProblemEntity entity
    ) {
    }

    public record Resolution(
            String rawText,
            String resolver,
            String setId,
            Integer setOrdinal,
            Integer conversationOrdinal,
            Long resolvedProblemId,
            String title,
            double confidence,
            List<Candidate> alternatives,
            boolean clarificationIssued,
            AiConversationProblemEntity entity
    ) {
        public boolean resolved() {
            return resolvedProblemId != null || entity != null;
        }
    }

    public record Outcome(List<Resolution> resolutions) {
        static Outcome empty() {
            return new Outcome(List.of());
        }

        public boolean isEmpty() {
            return resolutions == null || resolutions.isEmpty();
        }

        /** The first resolution that needs a user clarification, if any. */
        public Resolution firstAmbiguous() {
            if (resolutions == null) {
                return null;
            }
            for (Resolution resolution : resolutions) {
                if (resolution.clarificationIssued()) {
                    return resolution;
                }
            }
            return null;
        }
    }

    public record Input(
            String message,
            AiChatRequest.ClarificationAnswer clarificationAnswer,
            AiChatRequest.SelectedProblemContext selectionProblem,
            List<AiConversationProblemEntity> problems,
            String activeProblemSetId,
            String lastProblemSetId,
            List<StoredCandidate> storedCandidates
    ) {
    }
}
