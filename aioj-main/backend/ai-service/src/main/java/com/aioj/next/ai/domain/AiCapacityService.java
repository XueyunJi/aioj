package com.aioj.next.ai.domain;

import com.aioj.next.ai.config.AiProperties;
import com.aioj.next.common.error.DomainException;
import com.aioj.next.common.error.ErrorCode;
import org.springframework.stereotype.Service;

import java.util.EnumMap;
import java.util.Map;
import java.util.concurrent.Semaphore;
import java.util.function.Supplier;

@Service
public class AiCapacityService {
    private final Map<AiWorkload, Semaphore> semaphores;

    public AiCapacityService(AiProperties properties) {
        AiProperties.Capacity capacity = properties.getCapacity();
        this.semaphores = new EnumMap<>(AiWorkload.class);
        semaphores.put(AiWorkload.STUDENT_ASSISTANT, semaphore(capacity.getStudentAssistantConcurrency()));
        semaphores.put(AiWorkload.PROBLEM_DRAFT, semaphore(capacity.getProblemDraftConcurrency()));
        semaphores.put(AiWorkload.INTENT_MEMORY, semaphore(capacity.getIntentMemoryConcurrency()));
        semaphores.put(AiWorkload.BATCH_REPORT, semaphore(capacity.getBatchReportConcurrency()));
    }

    public Lease acquire(AiWorkload workload) {
        Semaphore semaphore = semaphores.get(workload);
        if (semaphore == null || !semaphore.tryAcquire()) {
            throw new DomainException(ErrorCode.TOO_MANY_REQUESTS, "AI service is busy; please try again later");
        }
        return new Lease(semaphore);
    }

    public <T> T call(AiWorkload workload, Supplier<T> action) {
        try (Lease ignored = acquire(workload)) {
            return action.get();
        }
    }

    private Semaphore semaphore(int permits) {
        return new Semaphore(Math.max(1, permits));
    }

    public enum AiWorkload {
        STUDENT_ASSISTANT,
        PROBLEM_DRAFT,
        INTENT_MEMORY,
        BATCH_REPORT
    }

    public static final class Lease implements AutoCloseable {
        private final Semaphore semaphore;
        private boolean closed;

        private Lease(Semaphore semaphore) {
            this.semaphore = semaphore;
        }

        @Override
        public void close() {
            if (!closed) {
                closed = true;
                semaphore.release();
            }
        }
    }
}
