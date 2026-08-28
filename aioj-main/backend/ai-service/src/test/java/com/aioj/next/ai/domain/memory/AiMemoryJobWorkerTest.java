package com.aioj.next.ai.domain.memory;

import com.aioj.next.ai.config.AiProperties;
import com.aioj.next.ai.persistence.entity.AiMemoryJobEntity;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.util.List;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.contains;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class AiMemoryJobWorkerTest {
    @Test
    void unknownJobTypeIsFailedFinalWithoutThrowing() {
        AiMemoryJobService jobService = mock(AiMemoryJobService.class);
        AiProperties properties = new AiProperties();
        properties.getMemoryJobs().setBatchSize(1);
        AiMemoryJobEntity job = job(10L, "UNKNOWN_JOB");
        when(jobService.claimDueJobs(eq(1), any(Duration.class), any())).thenReturn(List.of(job));

        AiMemoryJobWorker worker = new AiMemoryJobWorker(jobService, properties, List.of());
        worker.pollDueJobs();

        verify(jobService).failFinal(eq(job), contains("No handler registered"));
    }

    @Test
    void registeredHandlerCompletesJob() {
        AiMemoryJobService jobService = mock(AiMemoryJobService.class);
        AiProperties properties = new AiProperties();
        AiMemoryJobEntity job = job(10L, "MEMORY_EXTRACT");
        when(jobService.claimDueJobs(eq(8), any(Duration.class), any())).thenReturn(List.of(job));
        AiMemoryJobHandler handler = new AiMemoryJobHandler() {
            @Override
            public String jobType() {
                return "MEMORY_EXTRACT";
            }

            @Override
            public void handle(AiMemoryJobEntity ignored) {
            }
        };

        AiMemoryJobWorker worker = new AiMemoryJobWorker(jobService, properties, List.of(handler));
        worker.pollDueJobs();

        verify(jobService).complete(job);
    }

    @Test
    void handlerExceptionIsRecordedAsRetryableOrFinalFailure() {
        AiMemoryJobService jobService = mock(AiMemoryJobService.class);
        AiProperties properties = new AiProperties();
        AiMemoryJobEntity job = job(10L, "MEMORY_EXTRACT");
        when(jobService.claimDueJobs(eq(8), any(Duration.class), any())).thenReturn(List.of(job));
        RuntimeException failure = new RuntimeException("stderr:\nraw output");
        AiMemoryJobHandler handler = new AiMemoryJobHandler() {
            @Override
            public String jobType() {
                return "MEMORY_EXTRACT";
            }

            @Override
            public void handle(AiMemoryJobEntity ignored) {
                throw failure;
            }
        };

        AiMemoryJobWorker worker = new AiMemoryJobWorker(jobService, properties, List.of(handler));
        worker.pollDueJobs();

        verify(jobService).failRetryableOrFinal(job, failure);
    }

    @Test
    void permanentHandlerFailureIsRecordedAsFinalFailure() {
        AiMemoryJobService jobService = mock(AiMemoryJobService.class);
        AiProperties properties = new AiProperties();
        AiMemoryJobEntity job = job(10L, "MEMORY_EXTRACT");
        when(jobService.claimDueJobs(eq(8), any(Duration.class), any())).thenReturn(List.of(job));
        AiMemoryJobPermanentFailure failure = new AiMemoryJobPermanentFailure("bad payload");
        AiMemoryJobHandler handler = new AiMemoryJobHandler() {
            @Override
            public String jobType() {
                return "MEMORY_EXTRACT";
            }

            @Override
            public void handle(AiMemoryJobEntity ignored) {
                throw failure;
            }
        };

        AiMemoryJobWorker worker = new AiMemoryJobWorker(jobService, properties, List.of(handler));
        worker.pollDueJobs();

        verify(jobService).failFinal(job, failure);
    }

    private AiMemoryJobEntity job(Long id, String jobType) {
        AiMemoryJobEntity job = new AiMemoryJobEntity();
        job.setId(id);
        job.setJobType(jobType);
        return job;
    }
}
