package com.aioj.next.ai.domain.memory;

public class AiMemoryJobPermanentFailure extends RuntimeException {
    public AiMemoryJobPermanentFailure(String message) {
        super(message);
    }

    public AiMemoryJobPermanentFailure(String message, Throwable cause) {
        super(message, cause);
    }
}
