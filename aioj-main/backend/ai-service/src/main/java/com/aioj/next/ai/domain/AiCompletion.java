package com.aioj.next.ai.domain;

import java.util.List;

public record AiCompletion(
        String content,
        String provider,
        String model,
        long promptTokens,
        long completionTokens,
        String teachingDecision,
        String stuckLayer,
        String studentLevel,
        Clarification clarification
) {
    public AiCompletion(String content, String provider, String model, long promptTokens, long completionTokens) {
        this(content, provider, model, promptTokens, completionTokens, "HINT", "UNKNOWN", "unknown", Clarification.empty());
    }

    public AiCompletion(String content, String provider, String model, long promptTokens, long completionTokens, Clarification clarification) {
        this(content, provider, model, promptTokens, completionTokens, "HINT", "UNKNOWN", "unknown", clarification);
    }

    public AiCompletion {
        teachingDecision = teachingDecision == null || teachingDecision.isBlank() ? "HINT" : teachingDecision;
        stuckLayer = stuckLayer == null || stuckLayer.isBlank() ? "UNKNOWN" : stuckLayer;
        studentLevel = studentLevel == null || studentLevel.isBlank() ? "unknown" : studentLevel;
        clarification = clarification == null ? Clarification.empty() : clarification.normalized();
    }

    public List<ClarificationOption> clarificationOptions() {
        return clarification.options();
    }

    public boolean hasClarification() {
        Clarification current = clarification == null ? Clarification.empty() : clarification;
        return (current.id() != null && !current.id().isBlank())
                || (current.title() != null && !current.title().isBlank())
                || (current.prompt() != null && !current.prompt().isBlank())
                || (current.options() != null && !current.options().isEmpty());
    }

    public record Clarification(
            String id,
            String priority,
            String title,
            String prompt,
            ClarificationInput input,
            List<ClarificationOption> options,
            String defaultAction,
            String assumption
    ) {
        public Clarification(String title, String prompt, List<ClarificationOption> options) {
            this("", "helpful", title, prompt, ClarificationInput.fromOptions(options), options, "continue", null);
        }

        public static Clarification empty() {
            return new Clarification("", "helpful", "", "", ClarificationInput.empty(), List.of(), "continue", null);
        }

        public Clarification normalized() {
            List<ClarificationOption> normalizedOptions = options == null ? List.of() : List.copyOf(options);
            return new Clarification(
                    id == null ? "" : id,
                    priority == null || priority.isBlank() ? "helpful" : priority,
                    title == null ? "" : title,
                    prompt == null ? "" : prompt,
                    input == null ? ClarificationInput.fromOptions(normalizedOptions) : input.normalized(normalizedOptions),
                    normalizedOptions,
                    defaultAction == null || defaultAction.isBlank() ? "continue" : defaultAction,
                    assumption
            );
        }
    }

    public record ClarificationInput(
            String kind,
            boolean required,
            List<ClarificationOption> options,
            boolean allowCustom,
            String customKind,
            String placeholder
    ) {
        public static ClarificationInput empty() {
            return new ClarificationInput("free_text", false, List.of(), true, "free_text", "");
        }

        public static ClarificationInput fromOptions(List<ClarificationOption> options) {
            List<ClarificationOption> normalizedOptions = options == null ? List.of() : List.copyOf(options);
            boolean hasText = normalizedOptions.stream().anyMatch(option -> "text".equals(option.type()) || "textarea".equals(option.type()) || "free_text".equals(option.type()) || "code".equals(option.type()));
            String kind = normalizedOptions.isEmpty() ? "free_text" : hasText ? "mixed" : "single_choice";
            String placeholder = normalizedOptions.stream()
                    .map(ClarificationOption::placeholder)
                    .filter(value -> value != null && !value.isBlank())
                    .findFirst()
                    .orElse("");
            return new ClarificationInput(kind, false, normalizedOptions, hasText, hasText ? "free_text" : null, placeholder);
        }

        public ClarificationInput normalized(List<ClarificationOption> fallbackOptions) {
            List<ClarificationOption> normalizedOptions = options == null || options.isEmpty() ? fallbackOptions : List.copyOf(options);
            String normalizedKind = kind == null || kind.isBlank() ? fromOptions(normalizedOptions).kind() : kind;
            return new ClarificationInput(
                    normalizedKind,
                    required,
                    normalizedOptions == null ? List.of() : normalizedOptions,
                    allowCustom,
                    customKind,
                    placeholder == null ? "" : placeholder
            );
        }
    }

    public record ClarificationOption(
            String type,
            String label,
            String message,
            String placeholder,
            String messageTemplate
    ) {
    }

    public record MemorySignal(String type, String content, double confidence, String reason, String evidenceType) {
        public MemorySignal {
            evidenceType = evidenceType == null ? "" : evidenceType;
        }
    }
}
