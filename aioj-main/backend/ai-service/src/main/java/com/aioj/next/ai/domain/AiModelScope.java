package com.aioj.next.ai.domain;

import java.util.Arrays;

public enum AiModelScope {
    TEXT_GENERATION,
    MEMORY_EXTRACTION,
    REPORT_ANALYSIS,
    PROBLEM_DRAFT,
    ACCOUNT_IMPORT_PARSE,
    INTENT,
    AGENT_CURATOR,
    EMBEDDING;

    public static AiModelScope from(String value) {
        if (value == null || value.isBlank()) {
            return TEXT_GENERATION;
        }
        String normalized = value.trim().replace('-', '_').toUpperCase();
        return Arrays.stream(values())
                .filter(scope -> scope.name().equals(normalized))
                .findFirst()
                .orElseThrow(() -> new IllegalArgumentException("Unsupported AI model config scope: " + value));
    }
}
