package com.aioj.next.ai.domain.context;

import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

@Component
public class AiContextReportBuilder {
    public AiContextSection section(
            String id,
            String type,
            String title,
            int priority,
            String source,
            String sensitivity,
            boolean required,
            String content,
            Map<String, Object> metadata
    ) {
        String preview = safePreview(content, 700);
        return new AiContextSection(
                id,
                type,
                title,
                priority,
                source,
                sensitivity,
                estimateTokens(content),
                required,
                preview,
                metadata == null ? Map.of() : metadata
        );
    }

    public AiContextBuildReport build(List<AiContextSection> sections) {
        List<AiContextSection> present = sections == null ? List.of() : sections.stream()
                .filter(section -> section != null && section.contentPreview() != null && !section.contentPreview().isBlank())
                .toList();
        Map<String, Integer> sourceSummary = new LinkedHashMap<>();
        int requiredTokens = 0;
        int optionalTokens = 0;
        int requiredCount = 0;
        int optionalCount = 0;
        for (AiContextSection section : present) {
            sourceSummary.merge(section.source(), 1, Integer::sum);
            if (section.required()) {
                requiredTokens += section.estimatedTokens();
                requiredCount++;
            } else {
                optionalTokens += section.estimatedTokens();
                optionalCount++;
            }
        }
        return new AiContextBuildReport(
                present,
                sourceSummary,
                requiredTokens + optionalTokens,
                requiredTokens,
                optionalTokens,
                requiredCount,
                optionalCount
        );
    }

    public List<AiContextSection> mutableSections() {
        return new ArrayList<>();
    }

    public String safePreview(String value, int maxLength) {
        if (value == null || value.isBlank()) {
            return "";
        }
        StringBuilder kept = new StringBuilder();
        boolean inCodeFence = false;
        boolean inRawOutputBlock = false;
        for (String rawLine : value.replace("\r\n", "\n").replace('\r', '\n').split("\n")) {
            String line = rawLine.trim();
            if (line.startsWith("```")) {
                inCodeFence = !inCodeFence;
                appendLine(kept, "[code block omitted]");
                continue;
            }
            if (isRawOutputLabel(line)) {
                inRawOutputBlock = true;
                appendLine(kept, "[raw output omitted]");
                continue;
            }
            if (inRawOutputBlock) {
                if (isContextBoundary(line) || isKnownContextKey(line)) {
                    inRawOutputBlock = false;
                } else {
                    continue;
                }
            }
            if (inCodeFence || looksLikeCodeLine(line)) {
                appendLine(kept, "[code line omitted]");
                continue;
            }
            appendLine(kept, redactSecrets(rawLine));
        }
        String normalized = kept.toString().replaceAll("\\n{3,}", "\n\n").trim();
        return normalized.length() <= maxLength ? normalized : normalized.substring(0, maxLength) + "...";
    }

    public int estimateTokens(String value) {
        return AiTokenEstimator.estimate(value);
    }

    private void appendLine(StringBuilder kept, String line) {
        if (line == null || line.isBlank()) {
            return;
        }
        if (!kept.isEmpty()) {
            kept.append('\n');
        }
        kept.append(line.trim());
    }

    private String redactSecrets(String value) {
        if (value == null || value.isBlank()) {
            return "";
        }
        return value
                .replaceAll("(?i)\"(codeText|stdoutExcerpt|stderrExcerpt)\"\\s*:\\s*\"(?:\\\\.|[^\"\\\\])*\"", "\"$1\":\"[omitted]\"")
                .replaceAll("(?i)(codeText|stdoutExcerpt|stderrExcerpt)\\s*[:=].*", "$1=[omitted]")
                .replaceAll("(?i)(token|secret|password|key)\\s*[:=]\\s*\\S+", "$1=***")
                .replaceAll("sk-[A-Za-z0-9_-]{8,}", "sk-***")
                .replaceAll("sk_live_[A-Za-z0-9_-]{8,}", "sk_live_***");
    }

    private boolean isRawOutputLabel(String line) {
        if (line == null) {
            return false;
        }
        String lower = line.toLowerCase();
        return lower.startsWith("stdoutexcerpt:")
                || lower.startsWith("stderrexcerpt:")
                || lower.startsWith("stdout:")
                || lower.startsWith("stderr:");
    }

    private boolean isContextBoundary(String line) {
        return line != null && (line.startsWith("[") || line.startsWith("<"));
    }

    private boolean isKnownContextKey(String line) {
        if (line == null || !line.contains(":")) {
            return false;
        }
        String key = line.substring(0, line.indexOf(':')).trim();
        return List.of(
                "submissionId",
                "problemId",
                "scope",
                "contestActive",
                "language",
                "status",
                "judgeMessage",
                "runtimeMs",
                "memoryKb",
                "score",
                "maxScore",
                "codeHash",
                "codeAllowedToModel",
                "policyMessage",
                "caseResults"
        ).contains(key);
    }

    private boolean looksLikeCodeLine(String line) {
        if (line == null || line.isBlank()) {
            return false;
        }
        if (looksLikeStructuredSummaryLine(line)) {
            return false;
        }
        String lower = line.toLowerCase();
        return lower.startsWith("#include")
                || lower.startsWith("using namespace")
                || lower.startsWith("public class")
                || lower.startsWith("public static void main")
                || lower.startsWith("class solution")
                || lower.startsWith("def main")
                || lower.startsWith("import sys")
                || lower.startsWith("from sys")
                || lower.startsWith("if __name__")
                || lower.contains("cin >>")
                || lower.contains("cout <<")
                || lower.contains("sys.stdin")
                || lower.contains("int main(")
                || lower.matches(".*[{};]\\s*$");
    }

    private boolean looksLikeStructuredSummaryLine(String line) {
        if (line == null) {
            return false;
        }
        String trimmed = line.trim();
        return trimmed.startsWith("{")
                && trimmed.endsWith("}")
                && (trimmed.contains("\":") || trimmed.contains("="));
    }
}
