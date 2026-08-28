package com.aioj.next.ai.agent.model;

/**
 * AUTO: model decides. REQUIRED: the turn must include at least one tool call —
 * native where the provider supports it (Kimi K3), runtime-simulated elsewhere
 * (DeepSeek, design doc §3.5).
 */
public enum ToolChoiceMode {
    AUTO,
    REQUIRED
}
