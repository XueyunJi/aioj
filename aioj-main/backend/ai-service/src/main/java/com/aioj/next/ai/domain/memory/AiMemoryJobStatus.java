package com.aioj.next.ai.domain.memory;

public enum AiMemoryJobStatus {
    QUEUED,
    RUNNING,
    COMPLETED,
    FAILED_RETRYABLE,
    FAILED_FINAL
}
