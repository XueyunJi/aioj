package com.aioj.next.ai.agent.model;

/**
 * Resolved per-call settings after CallProfile safety defaults are applied
 * to the DB-backed model config (design doc §3.4/§3.6).
 */
public record CallSettings(boolean thinkingEnabled, String reasoningEffort, double temperature, int maxTokens) {
}
