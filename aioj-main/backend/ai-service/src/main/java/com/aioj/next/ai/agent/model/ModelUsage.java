package com.aioj.next.ai.agent.model;

/** Provider-reported token usage for one actual model invocation. */
public record ModelUsage(
        String provider,
        String model,
        long promptTokens,
        long completionTokens,
        boolean reported
) {
    /** Compatibility constructor for tests and callers that provide known usage. */
    public ModelUsage(String provider, String model, long promptTokens, long completionTokens) {
        this(provider, model, promptTokens, completionTokens, true);
    }

    public static ModelUsage from(GatewayResponse response) {
        if (response == null) {
            return null;
        }
        long promptTokens = Math.max(0L, response.promptTokens());
        long completionTokens = Math.max(0L, response.completionTokens());
        // GatewayResponse predates an explicit usage-present flag. A successful
        // completion with both counters at zero is therefore conservatively
        // treated as missing provider usage, not as fabricated zero-token usage.
        return new ModelUsage(response.provider(), response.model(), promptTokens, completionTokens,
                promptTokens > 0L || completionTokens > 0L);
    }
}
