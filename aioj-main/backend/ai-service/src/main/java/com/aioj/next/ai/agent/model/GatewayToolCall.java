package com.aioj.next.ai.agent.model;

/**
 * One model-initiated tool call. {@code name} is always the internal dotted
 * name; adapters handle wire encoding.
 */
public record GatewayToolCall(String callId, String name, String argumentsJson) {
}
