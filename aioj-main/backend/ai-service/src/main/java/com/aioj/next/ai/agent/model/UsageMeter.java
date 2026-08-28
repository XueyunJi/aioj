package com.aioj.next.ai.agent.model;

/** Per-run token accumulator; usage is settled once per turn by the facade. */
public final class UsageMeter {

    private long promptTokens;
    private long completionTokens;
    private long cacheHitTokens;

    public void add(GatewayResponse response) {
        if (response == null) {
            return;
        }
        promptTokens += Math.max(0, response.promptTokens());
        completionTokens += Math.max(0, response.completionTokens());
        cacheHitTokens += Math.max(0, response.cacheHitTokens());
    }

    public long promptTokens() {
        return promptTokens;
    }

    public long completionTokens() {
        return completionTokens;
    }

    public long cacheHitTokens() {
        return cacheHitTokens;
    }
}
