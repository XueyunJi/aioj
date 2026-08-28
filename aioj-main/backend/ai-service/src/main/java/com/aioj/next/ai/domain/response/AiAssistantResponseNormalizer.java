package com.aioj.next.ai.domain.response;

import com.aioj.next.ai.domain.AiCompletion;
import com.aioj.next.ai.domain.clarification.ClarificationSchemaRepairer;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

@Component
public class AiAssistantResponseNormalizer {
    public static final String WARNING_EXTRACTED_JSON_FROM_MIXED_TEXT = "EXTRACTED_JSON_FROM_MIXED_TEXT";
    public static final String WARNING_MALFORMED_INTERNAL_JSON = "MALFORMED_INTERNAL_JSON";
    public static final String WARNING_EMPTY_CONTENT_WITH_CLARIFICATION = "EMPTY_CONTENT_WITH_CLARIFICATION";

    private static final String CLARIFICATION_FALLBACK = "我需要先确认一个信息，请看下方补充框。";
    private static final String MALFORMED_FALLBACK = "AI 回复解析失败，请重试或查看调试信息。";

    private final ObjectMapper objectMapper;
    private final ClarificationSchemaRepairer clarificationSchemaRepairer;

    public AiAssistantResponseNormalizer(ObjectMapper objectMapper, ClarificationSchemaRepairer clarificationSchemaRepairer) {
        this.objectMapper = objectMapper;
        this.clarificationSchemaRepairer = clarificationSchemaRepairer;
    }

    public NormalizedResponse normalize(AiCompletion completion) {
        if (completion == null) {
            return new NormalizedResponse(
                    new AiCompletion("", "unknown", "unknown", 0, 0),
                    Map.of(),
                    Map.of(),
                    List.of(),
                    false
            );
        }
        String raw = completion.content() == null ? "" : completion.content();
        Candidate candidate = extractCandidate(raw);
        if (candidate.json().isBlank()) {
            if (looksLikeInternalProtocol(raw)) {
                return malformedFallback(completion, List.of(WARNING_MALFORMED_INTERNAL_JSON));
            }
            return new NormalizedResponse(completion, metadataFrom(completion), Map.of(), List.of(), false);
        }

        JsonNode root;
        try {
            root = objectMapper.readTree(candidate.json());
        } catch (JsonProcessingException ex) {
            if (looksLikeInternalProtocol(raw) || candidate.looksInternal()) {
                return malformedFallback(completion, List.of(WARNING_MALFORMED_INTERNAL_JSON));
            }
            return new NormalizedResponse(completion, metadataFrom(completion), Map.of(), List.of(), false);
        }
        if (!isInternalProtocol(root)) {
            return new NormalizedResponse(completion, metadataFrom(completion), Map.of(), List.of(), false);
        }

        List<String> warnings = new ArrayList<>();
        if (candidate.mixedText()) {
            warnings.add(WARNING_EXTRACTED_JSON_FROM_MIXED_TEXT);
        }
        AiCompletion.Clarification clarification = clarification(root, completion.clarification());
        String content = text(root, "content");
        if (content == null || content.isBlank()) {
            if (hasClarification(clarification)) {
                content = CLARIFICATION_FALLBACK;
                warnings.add(WARNING_EMPTY_CONTENT_WITH_CLARIFICATION);
            } else {
                return malformedFallback(completion, List.of(WARNING_MALFORMED_INTERNAL_JSON));
            }
        }
        AiCompletion normalizedCompletion = new AiCompletion(
                content,
                completion.provider(),
                completion.model(),
                completion.promptTokens(),
                completion.completionTokens(),
                safeInline(textOr(root, "teachingDecision", completion.teachingDecision()), 32),
                safeInline(textOr(root, "stuckLayer", completion.stuckLayer()), 48),
                safeInline(textOr(root, "studentLevel", completion.studentLevel()), 32),
                clarification
        );
        return new NormalizedResponse(
                normalizedCompletion,
                metadata(root, normalizedCompletion),
                renderHints(root.get("renderHints")),
                List.copyOf(warnings),
                true
        );
    }

    public String normalizeVisibleContent(String role, String content) {
        if (!"assistant".equals(role)) {
            return content;
        }
        AiCompletion history = new AiCompletion(content == null ? "" : content, "history", "history", 0, 0);
        return normalize(history).completion().content();
    }

    private NormalizedResponse malformedFallback(AiCompletion completion, List<String> warnings) {
        AiCompletion normalizedCompletion = new AiCompletion(
                MALFORMED_FALLBACK,
                completion.provider(),
                completion.model(),
                completion.promptTokens(),
                completion.completionTokens(),
                completion.teachingDecision(),
                completion.stuckLayer(),
                completion.studentLevel(),
                completion.clarification()
        );
        return new NormalizedResponse(
                normalizedCompletion,
                metadataFrom(normalizedCompletion),
                Map.of(),
                warnings,
                true
        );
    }

    private boolean isInternalProtocol(JsonNode root) {
        if (root == null || !root.isObject()) {
            return false;
        }
        boolean hasVisibleSlot = root.has("content") || root.has("clarification");
        boolean hasProtocolMetadata = root.has("teachingDecision")
                || root.has("stuckLayer")
                || root.has("studentLevel")
                || root.has("renderHints");
        return hasVisibleSlot && (hasProtocolMetadata || root.has("clarification"));
    }

    private boolean looksLikeInternalProtocol(String raw) {
        if (raw == null || raw.isBlank()) {
            return false;
        }
        String value = raw.trim();
        boolean protocolKey = value.contains("\"teachingDecision\"")
                || value.contains("\"stuckLayer\"")
                || value.contains("\"studentLevel\"")
                || value.contains("\"clarification\"")
                || value.contains("\"renderHints\"");
        return protocolKey && (value.startsWith("{") || value.startsWith("```") || value.contains("\"content\""));
    }

    private Candidate extractCandidate(String raw) {
        if (raw == null || raw.isBlank()) {
            return new Candidate("", false, false);
        }
        String text = raw.trim();
        String unfenced = stripSingleJsonFence(text);
        if (!unfenced.equals(text)) {
            String json = extractBalancedJsonObject(unfenced);
            boolean mixed = !unfenced.trim().equals(json.trim());
            return new Candidate(json, mixed, looksLikeInternalProtocol(unfenced));
        }
        String json = extractBalancedJsonObject(text);
        if (json.isBlank()) {
            return new Candidate("", false, looksLikeInternalProtocol(text));
        }
        boolean mixed = !text.equals(json.trim());
        return new Candidate(json, mixed, looksLikeInternalProtocol(text));
    }

    private String stripSingleJsonFence(String text) {
        if (!text.startsWith("```")) {
            return text;
        }
        int firstNewline = text.indexOf('\n');
        if (firstNewline < 0) {
            return text;
        }
        String language = text.substring(3, firstNewline).trim().toLowerCase();
        if (!language.isBlank() && !"json".equals(language)) {
            return text;
        }
        int closingFence = text.lastIndexOf("```");
        if (closingFence <= firstNewline) {
            return text;
        }
        String tail = text.substring(closingFence + 3).trim();
        if (!tail.isBlank()) {
            return text;
        }
        return text.substring(firstNewline + 1, closingFence).trim();
    }

    private String extractBalancedJsonObject(String text) {
        int start = text.indexOf('{');
        if (start < 0) {
            return "";
        }
        int depth = 0;
        boolean inString = false;
        boolean escape = false;
        for (int i = start; i < text.length(); i++) {
            char value = text.charAt(i);
            if (inString) {
                if (escape) {
                    escape = false;
                    continue;
                }
                if (value == '\\') {
                    escape = true;
                    continue;
                }
                if (value == '"') {
                    inString = false;
                }
                continue;
            }
            if (value == '"') {
                inString = true;
                continue;
            }
            if (value == '{') {
                depth++;
            } else if (value == '}') {
                depth--;
                if (depth == 0) {
                    return text.substring(start, i + 1);
                }
            }
        }
        return "";
    }

    private AiCompletion.Clarification clarification(JsonNode root, AiCompletion.Clarification fallback) {
        JsonNode node = root.get("clarification");
        if (node != null && node.isObject()) {
            List<AiCompletion.ClarificationOption> options = clarificationOptions(node.get("options"));
            AiCompletion.ClarificationInput input = clarificationInput(node.get("input"), options);
            return clarificationSchemaRepairer.repair(new AiCompletion.Clarification(
                    safeInline(node.path("id").asText(""), 96),
                    safeInline(node.path("priority").asText("helpful"), 32),
                    safeInline(node.path("title").asText(""), 80),
                    safeBlock(node.path("prompt").asText(""), 500),
                    input,
                    input.options() == null || input.options().isEmpty() ? options : input.options(),
                    safeInline(node.path("defaultAction").asText(""), 32),
                    safeBlock(node.path("assumption").asText(""), 240)
            ));
        }
        if (root.has("clarificationOptions")) {
            return clarificationSchemaRepairer.repair(new AiCompletion.Clarification("", "", clarificationOptions(root.get("clarificationOptions"))));
        }
        return fallback == null ? AiCompletion.Clarification.empty() : fallback;
    }

    private AiCompletion.ClarificationInput clarificationInput(JsonNode node, List<AiCompletion.ClarificationOption> fallbackOptions) {
        if (node == null || !node.isObject()) {
            return AiCompletion.ClarificationInput.fromOptions(fallbackOptions);
        }
        return new AiCompletion.ClarificationInput(
                safeInline(node.path("kind").asText(""), 32),
                node.path("required").asBoolean(false),
                node.has("options") ? clarificationOptions(node.get("options")) : fallbackOptions,
                node.path("allowCustom").asBoolean(false),
                safeInline(node.path("customKind").asText(""), 32),
                safeInline(node.path("placeholder").asText(""), 200)
        );
    }

    private List<AiCompletion.ClarificationOption> clarificationOptions(JsonNode node) {
        if (node == null || !node.isArray()) {
            return List.of();
        }
        List<AiCompletion.ClarificationOption> options = new ArrayList<>();
        for (JsonNode item : node) {
            if (options.size() >= 5) {
                break;
            }
            if (item.isTextual()) {
                String message = safeInline(item.asText(), 240);
                options.add(new AiCompletion.ClarificationOption("choice", summarizeOptionLabel(message), message, "", ""));
            } else if (item.isObject()) {
                options.add(new AiCompletion.ClarificationOption(
                        clarificationOptionType(item.path("type").asText("")),
                        safeInline(item.path("label").asText(""), 48),
                        safeInline(item.path("message").asText(""), 240),
                        safeInline(item.path("placeholder").asText(""), 200),
                        safeInline(item.path("messageTemplate").asText(""), 240)
                ));
            }
        }
        return options;
    }

    private String clarificationOptionType(String value) {
        String normalized = value == null ? "" : value.trim();
        return switch (normalized) {
            case "text", "textarea", "free_text", "code", "confirm" -> normalized;
            default -> "choice";
        };
    }

    private boolean hasClarification(AiCompletion.Clarification clarification) {
        return clarification != null && new AiCompletion(
                "",
                "normalizer",
                "normalizer",
                0,
                0,
                clarification
        ).hasClarification();
    }

    private Map<String, String> metadata(JsonNode root, AiCompletion completion) {
        Map<String, String> metadata = new LinkedHashMap<>();
        metadata.put("teachingDecision", completion.teachingDecision());
        metadata.put("stuckLayer", completion.stuckLayer());
        metadata.put("studentLevel", completion.studentLevel());
        if (root.has("rawDebugId")) {
            metadata.put("rawDebugId", safeInline(root.path("rawDebugId").asText(""), 80));
        }
        return metadata;
    }

    private Map<String, String> metadataFrom(AiCompletion completion) {
        Map<String, String> metadata = new LinkedHashMap<>();
        metadata.put("teachingDecision", completion.teachingDecision());
        metadata.put("stuckLayer", completion.stuckLayer());
        metadata.put("studentLevel", completion.studentLevel());
        return metadata;
    }

    private Map<String, Object> renderHints(JsonNode node) {
        if (node == null || !node.isObject()) {
            return Map.of();
        }
        return objectMapper.convertValue(node, new TypeReference<Map<String, Object>>() {
        });
    }

    private String text(JsonNode root, String field) {
        JsonNode value = root.get(field);
        return value == null || value.isNull() ? null : value.asText();
    }

    private String textOr(JsonNode root, String field, String fallback) {
        String value = text(root, field);
        return value == null || value.isBlank() ? fallback : value;
    }

    private String summarizeOptionLabel(String value) {
        if (value == null || value.isBlank()) {
            return "继续";
        }
        String normalized = value.replaceAll("\\s+", " ").trim();
        return normalized.length() <= 14 ? normalized : normalized.substring(0, 14);
    }

    private String safeInline(String value, int maxLength) {
        if (value == null) {
            return "";
        }
        String collapsed = value.replace("\r", " ").replace("\n", " ").trim();
        return collapsed.length() <= maxLength ? collapsed : collapsed.substring(0, maxLength);
    }

    private String safeBlock(String value, int maxLength) {
        if (value == null) {
            return "";
        }
        String normalized = value.replace("\r\n", "\n").replace("\r", "\n").trim();
        return normalized.length() <= maxLength ? normalized : normalized.substring(0, maxLength);
    }

    private record Candidate(String json, boolean mixedText, boolean looksInternal) {
    }

    public record NormalizedResponse(
            AiCompletion completion,
            Map<String, String> metadata,
            Map<String, Object> renderHints,
            List<String> parseWarnings,
            boolean rawStoredForDebug
    ) {
    }
}
