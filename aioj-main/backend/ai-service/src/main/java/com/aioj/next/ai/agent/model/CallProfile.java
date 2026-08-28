package com.aioj.next.ai.agent.model;

/**
 * Call profiles with thinking/max_tokens safety defaults (design doc §3.4).
 * Origin: spike proved thinking + small max_tokens yields empty content, so
 * structured small-output calls force thinking off or a large token budget.
 */
public enum CallProfile {
    /** Normal, unrestricted chat turn. */
    CHAT_STREAM(false, 4096, 0.3, false),
    /** Restricted turn: full generation, output-guard check, pseudo-stream replay (P3). */
    CHAT_BUFFERED(false, 4096, 0.3, false),
    /** Small structured outputs (classification/judgement): thinking off, JSON object. */
    STRUCTURED_SMALL(true, 4096, 0.1, true),
    /** Async curator structured outputs (TurnDigest etc.): thinking off, JSON object. */
    CURATOR(true, 4096, 0.1, true),
    /**
     * Thinking-off recovery call: blank-completion retry and budget-exhausted final
     * answers. Live eval confirmed thinking can burn the whole max_tokens budget on
     * hard turns and return empty content (same spike fact as §3.4).
     */
    RECOVERY_ANSWER(true, 4096, 0.3, false);

    private final boolean forceThinkingDisabled;
    private final int defaultMaxTokens;
    private final double defaultTemperature;
    private final boolean forceJsonOutput;

    CallProfile(boolean forceThinkingDisabled, int defaultMaxTokens, double defaultTemperature, boolean forceJsonOutput) {
        this.forceThinkingDisabled = forceThinkingDisabled;
        this.defaultMaxTokens = defaultMaxTokens;
        this.defaultTemperature = defaultTemperature;
        this.forceJsonOutput = forceJsonOutput;
    }

    public boolean forceThinkingDisabled() {
        return forceThinkingDisabled;
    }

    public int defaultMaxTokens() {
        return defaultMaxTokens;
    }

    public double defaultTemperature() {
        return defaultTemperature;
    }

    /** Structured profiles force response_format=json_object regardless of DB config (§3.4). */
    public boolean forceJsonOutput() {
        return forceJsonOutput;
    }

    /** Minimum max_tokens when thinking stays enabled (spike fact: small budgets are eaten by reasoning). */
    public static final int MIN_MAX_TOKENS_WHEN_THINKING = 2_000;
}
