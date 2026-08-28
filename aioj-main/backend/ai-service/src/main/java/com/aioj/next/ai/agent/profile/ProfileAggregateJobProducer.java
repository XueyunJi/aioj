package com.aioj.next.ai.agent.profile;

import com.aioj.next.ai.agent.asyncjob.AgentAsyncJobService;
import com.aioj.next.ai.config.AiProperties;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Agent Core V3 P2-6: shared producer for PROFILE_AGGREGATE jobs. The idempotency key is
 * bucketed per user per hour (PROFILE_AGGREGATE:{userId}:{hourBucket}) so a burst of signal
 * writes collapses into one job, while a later hour still re-aggregates. Never throws —
 * aggregation scheduling must not break the producing flow (chat curate / judged analysis).
 */
@Component
public class ProfileAggregateJobProducer {

    private static final Logger log = LoggerFactory.getLogger(ProfileAggregateJobProducer.class);

    private final AgentAsyncJobService jobService;
    private final ObjectMapper objectMapper;
    private final AiProperties properties;

    public ProfileAggregateJobProducer(AgentAsyncJobService jobService, ObjectMapper objectMapper, AiProperties properties) {
        this.jobService = jobService;
        this.objectMapper = objectMapper;
        this.properties = properties;
    }

    public void enqueueForUser(Long userId) {
        if (userId == null) {
            return;
        }
        try {
            String hourBucket = Instant.now().truncatedTo(ChronoUnit.HOURS).toString();
            Map<String, Object> payload = new LinkedHashMap<>();
            payload.put("userId", userId);
            jobService.enqueue(
                    ProfileAggregateJobHandler.JOB_TYPE_PROFILE_AGGREGATE,
                    ProfileAggregateJobHandler.JOB_TYPE_PROFILE_AGGREGATE + ":" + userId + ":" + hourBucket,
                    objectMapper.writeValueAsString(payload),
                    properties.getAgentJobs().getMaxAttempts()
            );
        } catch (Exception ex) {
            log.warn("profile aggregate job enqueue failed userId={} error={}", userId, ex.toString());
        }
    }
}
