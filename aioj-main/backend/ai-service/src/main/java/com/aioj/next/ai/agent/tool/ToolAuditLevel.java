package com.aioj.next.ai.agent.tool;

public enum ToolAuditLevel {
    /** Audit arguments, status, hashes, and latency. */
    FULL,
    /** Audit everything except the (redacted) arguments payload. */
    NO_ARGUMENTS
}
