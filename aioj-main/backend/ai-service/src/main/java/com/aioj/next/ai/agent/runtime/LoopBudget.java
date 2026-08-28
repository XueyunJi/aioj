package com.aioj.next.ai.agent.runtime;

import com.aioj.next.ai.config.AiProperties;

/**
 * Per-run loop budget (design doc §4.2). Bounds total model steps, total tool
 * calls, and per-category calls (search/fetch) so a model cannot loop forever
 * or hammer one tool category.
 */
public record LoopBudget(int maxAgentSteps, int maxToolCalls, int maxSearchCalls, int maxFetchCalls) {

    public static LoopBudget from(AiProperties.AgentCore config) {
        return new LoopBudget(config.getMaxAgentSteps(), config.getMaxToolCalls(),
                config.getMaxSearchCalls(), config.getMaxFetchCalls());
    }

    public String categoryOf(String toolName) {
        if (toolName != null && (toolName.startsWith("context.search") || toolName.startsWith("memory.search")
                || toolName.startsWith("profile.search"))) {
            return "search";
        }
        if (toolName != null && (toolName.startsWith("context.fetch") || toolName.startsWith("memory.fetch"))) {
            return "fetch";
        }
        return "other";
    }

    /** Category-level cap; a call over its category cap is rejected as data, not executed. */
    public boolean categoryExhausted(String toolName, int searchCalls, int fetchCalls) {
        return switch (categoryOf(toolName)) {
            case "search" -> searchCalls >= maxSearchCalls;
            case "fetch" -> fetchCalls >= maxFetchCalls;
            default -> false;
        };
    }
}
