package com.aioj.next.ai.agent.model;

/**
 * Static capability matrix per provider (design doc §3.2). Values encode the
 * spike-verified / official-docs facts; illegal combinations (e.g. DeepSeek +
 * tool_choice=required) are rejected explicitly, never silently downgraded.
 */
public record ProviderCapabilities(
        String provider,
        boolean toolCalling,
        boolean toolChoiceRequiredNative,
        boolean sendsStrictFunctionSchema
) {
    /** DeepSeek: thinking + tool_choice=required returns HTTP 400 (spike), so REQUIRED is runtime-simulated. */
    public static ProviderCapabilities deepSeek() {
        return new ProviderCapabilities("deepseek", true, false, false);
    }

    /** Kimi K3: native tool_choice="required", per-function strict schema default true (official docs). */
    public static ProviderCapabilities kimi() {
        return new ProviderCapabilities("kimi", true, true, true);
    }
}
