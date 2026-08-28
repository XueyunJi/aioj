package com.aioj.next.ai.agent.profile;

import com.aioj.next.ai.persistence.entity.AiAsyncJobEntity;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

class ProfileAggregateJobHandlerTest {

    private final ProfileAggregationService aggregationService = mock(ProfileAggregationService.class);
    private final ProfileAggregateJobHandler handler =
            new ProfileAggregateJobHandler(new ObjectMapper(), aggregationService);

    @Test
    void jobTypeIsProfileAggregate() {
        assertThat(handler.jobType()).isEqualTo(ProfileAggregateJobHandler.JOB_TYPE_PROFILE_AGGREGATE);
    }

    @Test
    void missingUserIdFailsRetryably() {
        assertThatThrownBy(() -> handler.handle(job("{\"foo\":1}")))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("userId");
        assertThatThrownBy(() -> handler.handle(job("{\"userId\":\"7\"}")))
                .isInstanceOf(IllegalStateException.class);
        verifyNoInteractions(aggregationService);
    }

    @Test
    void validPayloadDelegatesToAggregationService() throws Exception {
        when(aggregationService.aggregatePendingSignals(7L)).thenReturn(3);

        handler.handle(job("{\"userId\":7}"));

        verify(aggregationService).aggregatePendingSignals(7L);
    }

    private AiAsyncJobEntity job(String payload) {
        AiAsyncJobEntity job = new AiAsyncJobEntity();
        job.setId(1L);
        job.setJobType(ProfileAggregateJobHandler.JOB_TYPE_PROFILE_AGGREGATE);
        job.setPayloadJson(payload);
        return job;
    }
}
