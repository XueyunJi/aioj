package com.aioj.next.ai.agent.asyncjob;

import com.aioj.next.ai.config.AiProperties;
import com.aioj.next.ai.persistence.entity.AiAsyncJobEntity;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class AgentAsyncJobWorkerTest {

    private final AgentAsyncJobService jobService = mock(AgentAsyncJobService.class);

    @Test
    void dispatchesToRegisteredHandlerAndCompletes() throws Exception {
        AtomicInteger handled = new AtomicInteger();
        AgentAsyncJobHandler handler = new AgentAsyncJobHandler() {
            @Override
            public String jobType() {
                return "TURN_CURATE";
            }

            @Override
            public void handle(AiAsyncJobEntity job) {
                handled.incrementAndGet();
            }
        };
        AgentAsyncJobWorker worker = new AgentAsyncJobWorker(jobService, new AiProperties(), List.of(handler));
        AiAsyncJobEntity job = job(1L, "TURN_CURATE");
        when(jobService.claimDueJobs(anyInt(), anyLong(), anyString())).thenReturn(List.of(job));

        worker.pollDueJobs();

        org.assertj.core.api.Assertions.assertThat(handled.get()).isEqualTo(1);
        verify(jobService).complete(job);
        verify(jobService, never()).fail(any(), any(), anyLong());
    }

    @Test
    void unknownJobTypeParksFailedImmediately() {
        AgentAsyncJobWorker worker = new AgentAsyncJobWorker(jobService, new AiProperties(), List.of());
        AiAsyncJobEntity job = job(2L, "MYSTERY");
        when(jobService.claimDueJobs(anyInt(), anyLong(), anyString())).thenReturn(List.of(job));

        worker.pollDueJobs();

        verify(jobService).failFinal(eq(job), anyString());
        verify(jobService, never()).complete(any());
    }

    @Test
    void handlerExceptionFailsWithBackoff() throws Exception {
        AgentAsyncJobHandler failing = new AgentAsyncJobHandler() {
            @Override
            public String jobType() {
                return "TURN_CURATE";
            }

            @Override
            public void handle(AiAsyncJobEntity job) throws Exception {
                throw new IllegalStateException("curator down");
            }
        };
        AgentAsyncJobWorker worker = new AgentAsyncJobWorker(jobService, new AiProperties(), List.of(failing));
        AiAsyncJobEntity job = job(3L, "TURN_CURATE");
        when(jobService.claimDueJobs(anyInt(), anyLong(), anyString())).thenReturn(List.of(job));

        worker.pollDueJobs();

        verify(jobService).fail(eq(job), any(IllegalStateException.class), eq(60L));
        verify(jobService, never()).complete(any());
    }

    private AiAsyncJobEntity job(Long id, String type) {
        AiAsyncJobEntity job = new AiAsyncJobEntity();
        job.setId(id);
        job.setJobType(type);
        job.setStatus(AgentAsyncJobService.STATUS_RUNNING);
        return job;
    }
}
