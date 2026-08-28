package com.aioj.next.ai.agent.asyncjob;

import com.aioj.next.ai.config.AiProperties;
import com.aioj.next.ai.persistence.entity.AiAsyncJobEntity;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;

import java.lang.management.ManagementFactory;
import java.util.List;
import java.util.Map;
import java.util.function.Function;
import java.util.stream.Collectors;

/**
 * Polling worker for ai_async_jobs (design doc §6.3): claims due jobs under a lease,
 * dispatches to the registered handler, and records completion or backoff. Unknown job
 * types park as FAILED immediately so misconfiguration is observable instead of retried.
 */
@Service
public class AgentAsyncJobWorker {

    private static final Logger log = LoggerFactory.getLogger(AgentAsyncJobWorker.class);

    private final AgentAsyncJobService jobService;
    private final AiProperties properties;
    private final Map<String, AgentAsyncJobHandler> handlers;
    private final String workerId;

    public AgentAsyncJobWorker(
            AgentAsyncJobService jobService,
            AiProperties properties,
            List<AgentAsyncJobHandler> handlers
    ) {
        this.jobService = jobService;
        this.properties = properties == null ? new AiProperties() : properties;
        this.handlers = handlers == null ? Map.of() : handlers.stream()
                .collect(Collectors.toUnmodifiableMap(AgentAsyncJobHandler::jobType, Function.identity(), (left, right) -> left));
        this.workerId = "agent-async-" + ManagementFactory.getRuntimeMXBean().getName();
    }

    @Scheduled(fixedDelayString = "${aioj.ai.agent-jobs.poll-interval-ms:5000}")
    public void pollDueJobs() {
        if (!properties.getAgentJobs().isEnabled()) {
            return;
        }
        List<AiAsyncJobEntity> jobs = jobService.claimDueJobs(
                properties.getAgentJobs().getBatchSize(),
                properties.getAgentJobs().getLeaseSeconds(),
                workerId
        );
        for (AiAsyncJobEntity job : jobs) {
            process(job);
        }
    }

    private void process(AiAsyncJobEntity job) {
        AgentAsyncJobHandler handler = handlers.get(job.getJobType());
        if (handler == null) {
            log.warn("no handler for agent async job type: jobId={} type={}", job.getId(), job.getJobType());
            jobService.failFinal(job, "No handler registered for job type: " + job.getJobType());
            return;
        }
        try {
            handler.handle(job);
            jobService.complete(job);
        } catch (Exception ex) {
            log.warn("agent async job failed: jobId={} type={} error={}", job.getId(), job.getJobType(), ex.toString());
            jobService.fail(job, ex, properties.getAgentJobs().getBackoffBaseSeconds());
        }
    }
}
