package com.aioj.next.ai.domain.context;

import java.util.List;
import java.util.Map;

public record AiContextBudgetReport(
        String model,
        int modelWindowTokens,
        int compressionThresholdTokens,
        int maxPromptBudgetTokens,
        int estimatedPromptTokensBefore,
        int estimatedPromptTokensAfter,
        boolean compressionApplied,
        List<String> trimmedSections,
        List<String> droppedSections,
        Map<String, Integer> estimatedBySection,
        List<String> warnings
) {
    public static AiContextBudgetReport empty() {
        return new AiContextBudgetReport("", 0, 0, 0, 0, 0, false, List.of(), List.of(), Map.of(), List.of());
    }

    public boolean hasBudget() {
        return modelWindowTokens > 0 || estimatedPromptTokensBefore > 0 || estimatedPromptTokensAfter > 0;
    }
}
