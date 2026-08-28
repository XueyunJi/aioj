package com.aioj.next.ai.agent.digest;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Rule-based synchronous TurnDigest (blueprint §七 "每轮都有 Digest，但不必同步调用模型").
 * Runs the moment a turn completes, before the async Curator, so the next turn always has
 * something searchable. Contains only facts derivable without a model: message ids, time,
 * code blocks, explicit problem references, explicit selection, exact keywords, entry
 * point and the source hash. Semantic fields (dialogueAct/topicPath/claims) stay empty
 * until the Curator fills them.
 */
@Component
public class StubDigestFactory {

    public static final int SCHEMA_VERSION = 3;
    public static final int STUB_DIGEST_VERSION = 1;
    public static final String STATUS_STUB = "STUB";
    public static final String STATUS_READY = "READY";

    private static final Logger log = LoggerFactory.getLogger(StubDigestFactory.class);

    private static final Pattern CODE_BLOCK = Pattern.compile("```([A-Za-z0-9_+-]*)\\s*\n(.*?)```", Pattern.DOTALL);
    private static final Pattern BACKTICK_TERM = Pattern.compile("`([^`\\n]{2,40})`");
    private static final Pattern QUOTED_TERM = Pattern.compile("[\"“『「]([^\"”』」\\n]{2,30})[\"”』」]");
    private static final Pattern IDENTIFIER_TERM = Pattern.compile("[A-Za-z_][A-Za-z0-9_]*[._][A-Za-z0-9_.]+|[A-Za-z]+[0-9]+[A-Za-z0-9_]*");
    private static final Pattern NUMBER_TERM = Pattern.compile("\\b\\d+(?:\\.\\d+)?(?:\\s?(?:ms|s|MB|KB|GB|bytes|行|次|个|道题)|e\\d+|\\^\\d+)\\b", Pattern.CASE_INSENSITIVE);
    private static final Pattern WHITESPACE = Pattern.compile("\\s+");

    private static final int MAX_CODE_BLOCKS = 8;
    private static final int MAX_KEYWORDS = 24;
    private static final int MAX_SEARCH_TEXT_CHARS = 1600;

    private final ObjectMapper objectMapper;

    public StubDigestFactory(ObjectMapper objectMapper) {
        this.objectMapper = objectMapper;
    }

    public BuiltStubDigest build(TurnDigestInput input) {
        String user = safe(input.userContent());
        String assistant = safe(input.assistantContent());

        List<Map<String, Object>> codeRefs = extractCodeRefs(user, assistant);
        Set<String> keywords = extractKeywords(user + "\n" + assistant);
        String sourceHash = sha256(user + "" + assistant);

        Map<String, Object> structured = new LinkedHashMap<>();
        structured.put("schemaVersion", SCHEMA_VERSION);
        structured.put("turnId", input.turnId());
        structured.put("conversationId", input.conversationId());
        structured.put("dialogueAct", null);
        structured.put("userIntents", List.of());
        structured.put("topicPath", List.of());
        structured.put("summary", stubSummary(user, assistant));
        structured.put("searchKeywords", new ArrayList<>(keywords));
        structured.put("entities", explicitEntities(input));
        structured.put("references", List.of());
        structured.put("userAssertions", List.of());
        structured.put("assistantClaims", List.of());
        structured.put("decisions", List.of());
        structured.put("unresolvedQuestions", List.of());
        structured.put("openTasks", List.of());
        structured.put("problemRefs", explicitProblemRefs(input));
        structured.put("codeRefs", codeRefs);
        structured.put("submissionRefs", input.submissionId() == null ? List.of() : List.of(input.submissionId()));
        structured.put("explicitSelection", explicitSelection(input));
        structured.put("memoryCandidates", List.of());
        structured.put("profileSignals", List.of());
        structured.put("safetyTags", List.of());
        structured.put("entryPoint", input.entryPoint() == null ? "CHAT" : input.entryPoint());
        Map<String, Object> source = new LinkedHashMap<>();
        source.put("userMessageId", input.userMessageId());
        source.put("assistantMessageId", input.assistantMessageId());
        source.put("sourceHash", sourceHash);
        structured.put("source", source);

        String searchText = buildSearchText(user, assistant, keywords, codeRefs);
        int tokenEstimate = Math.max(1, (user.length() + assistant.length()) / 4);
        return new BuiltStubDigest(
                stubSummary(user, assistant),
                toJson(structured),
                searchText,
                sourceHash,
                STUB_DIGEST_VERSION,
                tokenEstimate
        );
    }

    private List<Map<String, Object>> explicitEntities(TurnDigestInput input) {        List<Map<String, Object>> entities = new ArrayList<>();
        Set<Long> seen = new LinkedHashSet<>();
        if (input.explicitProblemId() != null) {
            seen.add(input.explicitProblemId());
        }
        if (input.referencedProblemIds() != null) {
            seen.addAll(input.referencedProblemIds());
        }
        for (Long problemId : seen) {
            Map<String, Object> entity = new LinkedHashMap<>();
            entity.put("type", "PROBLEM");
            entity.put("canonicalName", "problem:" + problemId);
            entity.put("externalProblemId", problemId);
            entity.put("explicit", true);
            entities.add(entity);
        }
        return entities;
    }

    private List<Long> explicitProblemRefs(TurnDigestInput input) {
        Set<Long> refs = new LinkedHashSet<>();
        if (input.explicitProblemId() != null) {
            refs.add(input.explicitProblemId());
        }
        if (input.referencedProblemIds() != null) {
            refs.addAll(input.referencedProblemIds());
        }
        return new ArrayList<>(refs);
    }

    private Map<String, Object> explicitSelection(TurnDigestInput input) {
        if (input.selectionText() == null || input.selectionText().isBlank()) {
            return null;
        }
        Map<String, Object> selection = new LinkedHashMap<>();
        String text = input.selectionText().strip();
        selection.put("text", text.length() > 200 ? text.substring(0, 200) : text);
        selection.put("sourceMessageId", input.selectionSourceMessageId());
        return selection;
    }

    private List<Map<String, Object>> extractCodeRefs(String user, String assistant) {
        List<Map<String, Object>> refs = new ArrayList<>();
        collectCodeRefs(user, "USER_MESSAGE", refs);
        collectCodeRefs(assistant, "ASSISTANT_MESSAGE", refs);
        return refs;
    }

    private void collectCodeRefs(String content, String role, List<Map<String, Object>> refs) {
        if (content.isBlank() || refs.size() >= MAX_CODE_BLOCKS) {
            return;
        }
        Matcher matcher = CODE_BLOCK.matcher(content);
        while (matcher.find() && refs.size() < MAX_CODE_BLOCKS) {
            String code = matcher.group(2) == null ? "" : matcher.group(2).strip();
            if (code.isEmpty()) {
                continue;
            }
            Map<String, Object> ref = new LinkedHashMap<>();
            ref.put("role", role);
            String language = matcher.group(1);
            ref.put("language", language == null || language.isBlank() ? "text" : language.toLowerCase());
            ref.put("lineCount", code.split("\n").length);
            String firstLine = code.lines().findFirst().orElse("");
            ref.put("firstLine", firstLine.length() > 120 ? firstLine.substring(0, 120) : firstLine);
            ref.put("hash", sha256(code).substring(0, 16));
            refs.add(ref);
        }
    }

    private Set<String> extractKeywords(String content) {
        Set<String> keywords = new LinkedHashSet<>();
        collectMatches(BACKTICK_TERM, content, keywords);
        collectMatches(QUOTED_TERM, content, keywords);
        collectMatches(IDENTIFIER_TERM, content, keywords);
        collectMatches(NUMBER_TERM, content, keywords);
        return keywords;
    }

    private void collectMatches(Pattern pattern, String content, Set<String> out) {
        if (content == null || content.isBlank() || out.size() >= MAX_KEYWORDS) {
            return;
        }
        Matcher matcher = pattern.matcher(content);
        while (matcher.find() && out.size() < MAX_KEYWORDS) {
            String term = matcher.group(matcher.groupCount() >= 1 ? 1 : 0).trim();
            if (term.length() >= 2 && term.length() <= 40) {
                out.add(term);
            }
        }
    }

    private String stubSummary(String user, String assistant) {
        String userPreview = preview(user, 100);
        String assistantPreview = preview(assistant, 60);
        if (assistantPreview.isEmpty()) {
            return "用户：" + userPreview;
        }
        return "用户：" + userPreview + " / 助手：" + assistantPreview;
    }

    private String buildSearchText(String user, String assistant, Set<String> keywords, List<Map<String, Object>> codeRefs) {
        StringBuilder builder = new StringBuilder(MAX_SEARCH_TEXT_CHARS);
        appendCapped(builder, preview(user, 500));
        for (String keyword : keywords) {
            appendCapped(builder, keyword);
        }
        for (Map<String, Object> ref : codeRefs) {
            appendCapped(builder, String.valueOf(ref.get("language")));
            appendCapped(builder, String.valueOf(ref.get("firstLine")));
        }
        appendCapped(builder, preview(assistant, 300));
        String text = builder.toString().trim();
        return text.length() > MAX_SEARCH_TEXT_CHARS ? text.substring(0, MAX_SEARCH_TEXT_CHARS) : text;
    }

    private void appendCapped(StringBuilder builder, String value) {
        if (value == null || value.isBlank() || builder.length() >= MAX_SEARCH_TEXT_CHARS) {
            return;
        }
        if (builder.length() > 0) {
            builder.append(' ');
        }
        builder.append(value);
    }

    private String preview(String content, int maxChars) {
        if (content == null) {
            return "";
        }
        String normalized = WHITESPACE.matcher(content.strip()).replaceAll(" ");
        return normalized.length() > maxChars ? normalized.substring(0, maxChars) : normalized;
    }

    private String toJson(Map<String, Object> structured) {
        try {
            return objectMapper.writeValueAsString(structured);
        } catch (Exception ex) {
            log.warn("stub digest serialization failed; falling back to minimal json error={}", ex.toString());
            return "{\"schemaVersion\":" + SCHEMA_VERSION + ",\"serializationError\":true}";
        }
    }

    private static String sha256(String content) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            byte[] hash = digest.digest(content.getBytes(StandardCharsets.UTF_8));
            StringBuilder hex = new StringBuilder(hash.length * 2);
            for (byte b : hash) {
                hex.append(Character.forDigit((b >> 4) & 0xF, 16));
                hex.append(Character.forDigit(b & 0xF, 16));
            }
            return hex.toString();
        } catch (NoSuchAlgorithmException ex) {
            throw new IllegalStateException("SHA-256 unavailable", ex);
        }
    }

    private static String safe(String value) {
        return value == null ? "" : value;
    }

    public record BuiltStubDigest(
            String summary,
            String structuredDigestJson,
            String searchText,
            String sourceHash,
            int digestVersion,
            int tokenEstimate
    ) {
    }
}
