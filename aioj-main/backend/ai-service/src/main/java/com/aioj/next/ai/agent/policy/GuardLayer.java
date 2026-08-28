package com.aioj.next.ai.agent.policy;

/** Guard layers of the four-line contest defense (design doc §5.6 audit vocabulary). */
public enum GuardLayer {
    L1_PARTICIPANT,
    L2_POLICY_INJECT,
    L3_FINGERPRINT_MSG,
    L3_FINGERPRINT_CTX,
    L4_OUTPUT,
    /** Tool-internal ABAC denials (P3-3: problem/submission allowed-view tools, search rate limit). */
    TOOL_ABAC
}
