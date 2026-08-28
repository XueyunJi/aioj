package com.aioj.next.ai.agent.profile;

import com.aioj.next.ai.agent.asyncjob.AgentAsyncJobService;
import com.aioj.next.ai.config.AiProperties;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

class ProfileAggregateJobProducerTest {

    private final AgentAsyncJobService jobService = mock(AgentAsyncJobService.class);
    private final ProfileAggregateJobProducer producer =
            new ProfileAggregateJobProducer(jobService, new ObjectMapper(), new AiProperties());

    @Test
    void enqueuesWithUserHourBucketedIdempotencyKey() {
        producer.enqueueForUser(7L);

        verify(jobService).enqueue(
                eq(ProfileAggregateJobHandler.JOB_TYPE_PROFILE_AGGREGATE),
                org.mockito.ArgumentMatchers.argThat(key -> key != null
                        && key.startsWith(ProfileAggregateJobHandler.JOB_TYPE_PROFILE_AGGREGATE + ":7:")
                        && key.endsWith(":00:00Z")),
                eq("{\"userId\":7}"),
                eq(5));
    }

    @Test
    void enqueueFailureNeverBreaksTheCaller() {
        when(jobService.enqueue(anyString(), anyString(), anyString(), anyInt()))
                .thenThrow(new RuntimeException("job db down"));

        assertThatCode(() -> producer.enqueueForUser(7L)).doesNotThrowAnyException();
    }

    @Test
    void nullUserIdSkipsEnqueue() {
        producer.enqueueForUser(null);

        verifyNoInteractions(jobService);
    }
}
