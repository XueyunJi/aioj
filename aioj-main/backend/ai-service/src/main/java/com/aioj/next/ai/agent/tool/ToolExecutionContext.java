package com.aioj.next.ai.agent.tool;

import com.aioj.next.ai.agent.policy.ContestPolicyView;

import java.time.Instant;
import java.util.Set;

/**
 * Server-generated execution context for every tool call. The model can never
 * supply or influence these fields (design doc §4.1): identity, policy, and time
 * come from the trusted control plane, not from tool arguments.
 *
 * @param contestPolicy optional contest policy projection (P3-3); null for
 *                      callers that predate the wiring and for turns without
 *                      any contest involvement. Tool-internal ABAC treats null
 *                      as "no contest participation".
 */
public record ToolExecutionContext(
        long userId,
        String conversationId,
        String turnId,
        long turnSeq,
        String policySnapshotId,
        Set<String> grantedScopes,
        Instant serverTime,
        String traceId,
        ContestPolicyView contestPolicy
) {
    public ToolExecutionContext {
        grantedScopes = grantedScopes == null ? Set.of() : Set.copyOf(grantedScopes);
    }

    /** Pre-P3-3 signature, kept so existing callers/tests compile unchanged. */
    public ToolExecutionContext(long userId, String conversationId, String turnId, long turnSeq,
                                String policySnapshotId, Set<String> grantedScopes,
                                Instant serverTime, String traceId) {
        this(userId, conversationId, turnId, turnSeq, policySnapshotId, grantedScopes,
                serverTime, traceId, null);
    }
}
