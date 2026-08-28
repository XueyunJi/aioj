package com.aioj.next.ai.domain;

import java.util.concurrent.atomic.AtomicLong;

/**
 * In-process failure counters for previously silent provider/retrieval
 * degradation paths (W1.6). Process-local by design (single ai-service
 * instance per deployment); surfaced through the admin-only memory
 * observability endpoint. No external dependency on purpose.
 */
public final class AiFailureMetrics {
    private static final AtomicLong MEMORY_EXTRACTION_FAILURES = new AtomicLong();
    private static final AtomicLong EMBEDDING_FAILURES = new AtomicLong();
    private static final AtomicLong EMBEDDING_CAPACITY_REJECTIONS = new AtomicLong();

    private AiFailureMetrics() {
    }

    public static void incrementMemoryExtractionFailure() {
        MEMORY_EXTRACTION_FAILURES.incrementAndGet();
    }

    public static void incrementEmbeddingFailure() {
        EMBEDDING_FAILURES.incrementAndGet();
    }

    public static void incrementEmbeddingCapacityRejection() {
        EMBEDDING_CAPACITY_REJECTIONS.incrementAndGet();
    }

    public static long memoryExtractionFailures() {
        return MEMORY_EXTRACTION_FAILURES.get();
    }

    public static long embeddingFailures() {
        return EMBEDDING_FAILURES.get();
    }

    public static long embeddingCapacityRejections() {
        return EMBEDDING_CAPACITY_REJECTIONS.get();
    }

    /** Test hook only; production code must not reset counters. */
    public static void reset() {
        MEMORY_EXTRACTION_FAILURES.set(0);
        EMBEDDING_FAILURES.set(0);
        EMBEDDING_CAPACITY_REJECTIONS.set(0);
    }
}
