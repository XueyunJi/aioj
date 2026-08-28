package com.aioj.next.ai.agent.tool;

import com.aioj.next.ai.agent.context.DataClassification;
import com.aioj.next.ai.agent.context.TrustLevel;

import java.util.List;

/**
 * Uniform tool result. Returned to the model as structured JSON with
 * {@code instructionAllowed=false} — tool output is data, never instructions.
 */
public record ToolResult<T>(
        String callId,
        ToolStatus status,
        T data,
        List<SourceRef> sources,
        DataClassification classification,
        TrustLevel trustLevel,
        String policyDecisionId,
        boolean truncated,
        String nextCursor,
        String resultHash,
        List<String> warnings,
        String errorCode,
        String errorMessage
) {
    public ToolResult {
        sources = sources == null ? List.of() : List.copyOf(sources);
        warnings = warnings == null ? List.of() : List.copyOf(warnings);
    }

    public static <T> ToolResult<T> success(String callId, T data, List<SourceRef> sources,
                                            DataClassification classification, TrustLevel trustLevel) {
        return new ToolResult<>(callId, ToolStatus.SUCCESS, data, sources, classification, trustLevel,
                null, false, null, null, List.of(), null, null);
    }

    public static <T> ToolResult<T> failure(String callId, ToolStatus status, String errorCode, String errorMessage) {
        return new ToolResult<>(callId, status, null, List.of(), null, null,
                null, false, null, null, List.of(), errorCode, errorMessage);
    }

    public ToolResult<T> withPolicyDecisionId(String decisionId) {
        return new ToolResult<>(callId, status, data, sources, classification, trustLevel,
                decisionId, truncated, nextCursor, resultHash, warnings, errorCode, errorMessage);
    }

    public ToolResult<T> withTruncation(String nextCursor, String resultHash, List<String> extraWarnings) {
        List<String> merged = new java.util.ArrayList<>(warnings);
        if (extraWarnings != null) {
            merged.addAll(extraWarnings);
        }
        return new ToolResult<>(callId, status, data, sources, classification, trustLevel,
                policyDecisionId, true, nextCursor, resultHash, merged, errorCode, errorMessage);
    }

    public boolean ok() {
        return status == ToolStatus.SUCCESS;
    }
}
