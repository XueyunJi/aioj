package com.aioj.next.ai.domain.context;

import java.util.List;
import java.util.Map;

public record AiContextBuildReport(
        List<AiContextSection> sections,
        Map<String, Integer> sourceSummary,
        int totalEstimatedTokens,
        int requiredEstimatedTokens,
        int optionalEstimatedTokens,
        int requiredSectionCount,
        int optionalSectionCount,
        AiContextBudgetReport budget
) {
    public AiContextBuildReport(
            List<AiContextSection> sections,
            Map<String, Integer> sourceSummary,
            int totalEstimatedTokens,
            int requiredEstimatedTokens,
            int optionalEstimatedTokens,
            int requiredSectionCount,
            int optionalSectionCount
    ) {
        this(sections, sourceSummary, totalEstimatedTokens, requiredEstimatedTokens, optionalEstimatedTokens,
                requiredSectionCount, optionalSectionCount, AiContextBudgetReport.empty());
    }

    public static AiContextBuildReport empty() {
        return new AiContextBuildReport(List.of(), Map.of(), 0, 0, 0, 0, 0, AiContextBudgetReport.empty());
    }

    public boolean hasSections() {
        return sections != null && !sections.isEmpty();
    }

    public AiContextBuildReport withBudget(AiContextBudgetReport nextBudget) {
        return new AiContextBuildReport(sections, sourceSummary, totalEstimatedTokens, requiredEstimatedTokens,
                optionalEstimatedTokens, requiredSectionCount, optionalSectionCount,
                nextBudget == null ? AiContextBudgetReport.empty() : nextBudget);
    }
}
