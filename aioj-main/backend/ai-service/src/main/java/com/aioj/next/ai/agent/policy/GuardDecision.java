package com.aioj.next.ai.agent.policy;

/** Per-layer verdict (design doc §5.6). PASS is always persisted too. */
public enum GuardDecision {
    PASS,
    CONSTRAIN,
    REFUSE,
    BLOCK
}
