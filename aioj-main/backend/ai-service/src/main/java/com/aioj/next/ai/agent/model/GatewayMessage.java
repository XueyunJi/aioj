package com.aioj.next.ai.agent.model;

import java.util.List;

/** Provider-neutral chat message for the agent loop. */
public record GatewayMessage(String role, String content, String toolCallId, List<GatewayToolCall> toolCalls) {

    public static GatewayMessage system(String content) {
        return new GatewayMessage("system", content, null, null);
    }

    public static GatewayMessage user(String content) {
        return new GatewayMessage("user", content, null, null);
    }

    public static GatewayMessage assistant(String content, List<GatewayToolCall> toolCalls) {
        return new GatewayMessage("assistant", content, null, toolCalls == null ? List.of() : List.copyOf(toolCalls));
    }

    public static GatewayMessage toolResult(String callId, String content) {
        return new GatewayMessage("tool", content, callId, null);
    }
}
