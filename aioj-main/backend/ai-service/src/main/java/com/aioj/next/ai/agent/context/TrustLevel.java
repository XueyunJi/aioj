package com.aioj.next.ai.agent.context;

/**
 * Trust level attached to every context section, tool result, and memory item.
 * Only SYSTEM_POLICY / SERVER_AUTHORITATIVE content may act as instructions;
 * everything else is data (prompt-injection guardrail, design doc §6.8).
 */
public enum TrustLevel {
    SYSTEM_POLICY,
    SERVER_AUTHORITATIVE,
    USER_PROVIDED,
    DERIVED_SUMMARY,
    MODEL_INFERRED,
    EXTERNAL_UNTRUSTED
}
