package com.aioj.next.ai.domain.memory;

import org.springframework.stereotype.Component;

import java.lang.reflect.Array;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.regex.Pattern;

@Component
public class AiMemoryEventPayloadSanitizer {
    public static final String OMITTED = "[omitted]";
    public static final int MAX_TEXT_LENGTH = 1200;
    public static final int MAX_ERROR_LENGTH = 1000;

    private static final Pattern FENCED_CODE = Pattern.compile("(?s)```.*?```");
    private static final Pattern SECRET_ASSIGNMENT = Pattern.compile(
            "(?i)(api[_-]?key|access[_-]?key|token|password|secret|private[_-]?key)\\s*[:=]\\s*[^\\s,;]+");
    private static final Pattern BEARER_SECRET = Pattern.compile("(?i)bearer\\s+[a-z0-9._\\-]{8,}");
    private static final Pattern OPENAI_STYLE_KEY = Pattern.compile("(?i)sk-[a-z0-9_\\-]{8,}");

    public Object sanitizePayload(Object value) {
        return sanitizeValue(value);
    }

    public String sanitizeErrorSummary(Throwable throwable) {
        if (throwable == null) {
            return "";
        }
        String message = throwable.getMessage();
        String raw = message == null || message.isBlank() ? throwable.getClass().getSimpleName() : message;
        return trim(sanitizeText(raw), MAX_ERROR_LENGTH);
    }

    public String sanitizeErrorSummary(String raw) {
        return trim(sanitizeText(raw == null ? "" : raw), MAX_ERROR_LENGTH);
    }

    public String sanitizeText(String raw) {
        if (raw == null || raw.isBlank()) {
            return "";
        }
        String withoutCodeBlocks = FENCED_CODE.matcher(raw).replaceAll("[code block omitted]");
        String withoutSecrets = SECRET_ASSIGNMENT.matcher(withoutCodeBlocks).replaceAll("$1=" + OMITTED);
        withoutSecrets = BEARER_SECRET.matcher(withoutSecrets).replaceAll("bearer " + OMITTED);
        withoutSecrets = OPENAI_STYLE_KEY.matcher(withoutSecrets).replaceAll("sk-" + OMITTED);
        return trim(stripUnsafeLines(withoutSecrets), MAX_TEXT_LENGTH);
    }

    private Object sanitizeValue(Object value) {
        if (value == null) {
            return null;
        }
        if (value instanceof Map<?, ?> map) {
            Map<String, Object> sanitized = new LinkedHashMap<>();
            for (Map.Entry<?, ?> entry : map.entrySet()) {
                String key = String.valueOf(entry.getKey());
                sanitized.put(key, isSensitiveKey(key) ? OMITTED : sanitizeValue(entry.getValue()));
            }
            return sanitized;
        }
        if (value instanceof Iterable<?> iterable) {
            List<Object> sanitized = new ArrayList<>();
            for (Object item : iterable) {
                sanitized.add(sanitizeValue(item));
            }
            return sanitized;
        }
        if (value.getClass().isArray()) {
            List<Object> sanitized = new ArrayList<>();
            int length = Array.getLength(value);
            for (int i = 0; i < length; i++) {
                sanitized.add(sanitizeValue(Array.get(value, i)));
            }
            return sanitized;
        }
        if (value instanceof CharSequence sequence) {
            return sanitizeText(sequence.toString());
        }
        if (value instanceof Number || value instanceof Boolean) {
            return value;
        }
        return sanitizeText(String.valueOf(value));
    }

    private boolean isSensitiveKey(String key) {
        if (key == null) {
            return false;
        }
        String normalized = key.toLowerCase(Locale.ROOT)
                .replace("_", "")
                .replace("-", "")
                .replace(" ", "");
        if (normalized.equals("codetext")
                || normalized.equals("source")
                || normalized.equals("sourcecode")
                || normalized.contains("stdoutexcerpt")
                || normalized.contains("stderrexcerpt")
                || normalized.equals("stdout")
                || normalized.equals("stderr")
                || normalized.equals("prompt")
                || normalized.equals("cookie")) {
            return true;
        }
        return normalized.equals("key")
                || normalized.equals("token")
                || normalized.endsWith("token")
                || normalized.equals("password")
                || normalized.endsWith("password")
                || normalized.equals("secret")
                || normalized.endsWith("secret")
                || normalized.contains("apikey")
                || normalized.contains("accesskey")
                || normalized.contains("privatekey")
                || normalized.contains("secretkey");
    }

    private String stripUnsafeLines(String text) {
        String[] lines = text.replace("\r\n", "\n").replace('\r', '\n').split("\n", -1);
        StringBuilder builder = new StringBuilder();
        boolean skippingRawOutput = false;
        boolean wroteRawMarker = false;
        for (String line : lines) {
            String trimmed = line.trim();
            if (isRawOutputLabel(trimmed)) {
                appendLine(builder, "[raw output omitted]");
                skippingRawOutput = true;
                wroteRawMarker = true;
                continue;
            }
            if (isSensitiveTextLabel(trimmed)) {
                appendLine(builder, "[sensitive text omitted]");
                continue;
            }
            if (skippingRawOutput) {
                if (trimmed.isBlank()) {
                    skippingRawOutput = false;
                    wroteRawMarker = false;
                } else if (!wroteRawMarker) {
                    appendLine(builder, "[raw output omitted]");
                    wroteRawMarker = true;
                }
                continue;
            }
            if (looksLikeCodeLine(trimmed)) {
                appendLine(builder, "[code line omitted]");
                continue;
            }
            appendLine(builder, line);
        }
        return builder.toString().trim();
    }

    private boolean isRawOutputLabel(String trimmed) {
        String lower = trimmed.toLowerCase(Locale.ROOT);
        return lower.startsWith("stdout:")
                || lower.startsWith("stderr:")
                || lower.startsWith("stdout=")
                || lower.startsWith("stderr=")
                || lower.startsWith("stdoutexcerpt:")
                || lower.startsWith("stderrexcerpt:");
    }

    private boolean isSensitiveTextLabel(String trimmed) {
        String lower = trimmed.toLowerCase(Locale.ROOT);
        return lower.startsWith("prompt:")
                || lower.startsWith("prompt=")
                || lower.startsWith("codetext:")
                || lower.startsWith("codetext=")
                || lower.startsWith("sourcecode:")
                || lower.startsWith("sourcecode=")
                || lower.startsWith("source:")
                || lower.startsWith("source=");
    }

    private boolean looksLikeCodeLine(String trimmed) {
        if (trimmed.isBlank()) {
            return false;
        }
        String lower = trimmed.toLowerCase(Locale.ROOT);
        return lower.startsWith("#include")
                || lower.startsWith("using namespace")
                || lower.contains("int main(")
                || lower.contains("public static void main")
                || lower.startsWith("system.out.")
                || lower.startsWith("console.log")
                || lower.matches("def\\s+[a-zA-Z_][a-zA-Z0-9_]*\\s*\\(.*\\)\\s*:")
                || lower.matches("class\\s+[a-zA-Z_][a-zA-Z0-9_]*\\s*\\{?")
                || (trimmed.contains("{") && trimmed.endsWith(";"))
                || (trimmed.endsWith(";") && (lower.startsWith("return ") || lower.contains(" = ")));
    }

    private void appendLine(StringBuilder builder, String line) {
        if (builder.length() > 0) {
            builder.append('\n');
        }
        builder.append(line);
    }

    private String trim(String value, int maxLength) {
        if (value == null || value.length() <= maxLength) {
            return value == null ? "" : value;
        }
        return value.substring(0, Math.max(0, maxLength - 16)).stripTrailing() + " [truncated]";
    }
}
