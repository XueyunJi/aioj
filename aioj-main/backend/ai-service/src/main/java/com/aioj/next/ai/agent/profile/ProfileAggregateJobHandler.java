package com.aioj.next.ai.agent.profile;

import com.aioj.next.ai.agent.asyncjob.AgentAsyncJobHandler;
import com.aioj.next.ai.persistence.entity.AiAsyncJobEntity;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

/**
 * Agent Core V3 P2-6: PROFILE_AGGREGATE async job (V60 ai_async_jobs). Parses the
 * {userId} payload and delegates to ProfileAggregationService; any failure propagates
 * into the worker's exponential-backoff retry.
 */
@Component
public class ProfileAggregateJobHandler implements AgentAsyncJobHandler {

    public static final String JOB_TYPE_PROFILE_AGGREGATE = "PROFILE_AGGREGATE";

    private static final Logger log = LoggerFactory.getLogger(ProfileAggregateJobHandler.class);

    private final ObjectMapper objectMapper;
    private final ProfileAggregationService aggregationService;

    public ProfileAggregateJobHandler(ObjectMapper objectMapper, ProfileAggregationService aggregationService) {
        this.objectMapper = objectMapper;
        this.aggregationService = aggregationService;
    }

    @Override
    public String jobType() {
        return JOB_TYPE_PROFILE_AGGREGATE;
    }

    @Override
    public void handle(AiAsyncJobEntity job) throws Exception {
        JsonNode payload = objectMapper.readTree(job.getPayloadJson());
        Long userId = payload.path("userId").isNumber() ? payload.path("userId").asLong() : null;
        if (userId == null) {
            throw new IllegalStateException("profile aggregate job payload missing userId");
        }
        int processed = aggregationService.aggregatePendingSignals(userId);
        log.info("profile aggregate job done jobId={} userId={} processed={}", job.getId(), userId, processed);
    }
}
