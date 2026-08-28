package com.aioj.next.ai.domain.memory;

import com.aioj.next.ai.config.AiProperties;
import com.aioj.next.ai.persistence.entity.AiMemoryJobEntity;
import com.aioj.next.ai.persistence.mapper.AiMemoryJobMapper;
import com.baomidou.mybatisplus.core.conditions.query.QueryWrapper;
import com.baomidou.mybatisplus.core.conditions.update.UpdateWrapper;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Clock;
import java.time.Duration;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

@Service
public class AiMemoryJobService {
    private static final int MAX_BATCH_SIZE = 100;
    private static final long MAX_BACKOFF_SECONDS = 3600;

    private final AiMemoryJobMapper jobMapper;
    private final AiMemoryEventPayloadSanitizer sanitizer;
    private final ObjectMapper objectMapper;
    private final AiProperties properties;
    private final Clock clock;

    @Autowired
    public AiMemoryJobService(
            AiMemoryJobMapper jobMapper,
            AiMemoryEventPayloadSanitizer sanitizer,
            ObjectMapper objectMapper,
            AiProperties properties
    ) {
        this(jobMapper, sanitizer, objectMapper, properties, Clock.systemDefaultZone());
    }

    AiMemoryJobService(
            AiMemoryJobMapper jobMapper,
            AiMemoryEventPayloadSanitizer sanitizer,
            ObjectMapper objectMapper,
            AiProperties properties,
            Clock clock
    ) {
        this.jobMapper = jobMapper;
        this.sanitizer = sanitizer;
        this.objectMapper = objectMapper;
        this.properties = properties == null ? new AiProperties() : properties;
        this.clock = clock == null ? Clock.systemDefaultZone() : clock;
    }

    @Transactional
    public AiMemoryJobEntity enqueue(
            Long eventId,
            String jobType,
            String idempotencyKey,
            Object payload,
            Integer maxAttempts,
            LocalDateTime nextRunAt
    ) {
        requireText(jobType, "jobType");
        requireText(idempotencyKey, "idempotencyKey");
        if (eventId == null) {
            throw new IllegalArgumentException("eventId is required");
        }
        AiMemoryJobEntity existing = findByIdempotencyKey(idempotencyKey);
        if (existing != null) {
            return existing;
        }

        LocalDateTime now = now();
        AiMemoryJobEntity job = new AiMemoryJobEntity();
        job.setEventId(eventId);
        job.setJobType(jobType.strip());
        job.setStatus(AiMemoryJobStatus.QUEUED.name());
        job.setIdempotencyKey(idempotencyKey.strip());
        job.setPayloadJson(toJson(payload));
        job.setAttemptCount(0);
        job.setMaxAttempts(normalizeMaxAttempts(maxAttempts));
        job.setNextRunAt(nextRunAt == null ? now : nextRunAt);
        job.setCreatedAt(now);
        job.setUpdatedAt(now);
        try {
            jobMapper.insert(job);
            return job;
        } catch (DuplicateKeyException ex) {
            AiMemoryJobEntity duplicate = findByIdempotencyKey(idempotencyKey);
            if (duplicate != null) {
                return duplicate;
            }
            throw ex;
        }
    }

    @Transactional
    public List<AiMemoryJobEntity> claimDueJobs(int requestedLimit, Duration requestedLease, String requestedOwner) {
        int limit = Math.max(1, Math.min(MAX_BATCH_SIZE, requestedLimit));
        LocalDateTime now = now();
        List<AiMemoryJobEntity> candidates = jobMapper.selectList(new QueryWrapper<AiMemoryJobEntity>()
                .and(wrapper -> wrapper
                        .in("status", AiMemoryJobStatus.QUEUED.name(), AiMemoryJobStatus.FAILED_RETRYABLE.name())
                        .le("next_run_at", now)
                        .or()
                        .eq("status", AiMemoryJobStatus.RUNNING.name())
                        .le("lease_expires_at", now))
                .orderByAsc("next_run_at")
                .orderByAsc("created_at")
                .last("LIMIT " + limit));
        if (candidates == null || candidates.isEmpty()) {
            return List.of();
        }

        Duration lease = requestedLease == null || requestedLease.isNegative() || requestedLease.isZero()
                ? Duration.ofSeconds(Math.max(1, properties.getMemoryJobs().getLeaseSeconds()))
                : requestedLease;
        String owner = normalizeOwner(requestedOwner);
        LocalDateTime leaseExpiresAt = now.plusNanos(lease.toNanos());
        List<AiMemoryJobEntity> claimed = new ArrayList<>();
        for (AiMemoryJobEntity candidate : candidates) {
            if (candidate == null || candidate.getId() == null) {
                continue;
            }
            UpdateWrapper<AiMemoryJobEntity> update = new UpdateWrapper<AiMemoryJobEntity>()
                    .eq("id", candidate.getId())
                    .and(wrapper -> wrapper
                            .in("status", AiMemoryJobStatus.QUEUED.name(), AiMemoryJobStatus.FAILED_RETRYABLE.name())
                            .le("next_run_at", now)
                            .or()
                            .eq("status", AiMemoryJobStatus.RUNNING.name())
                            .le("lease_expires_at", now))
                    .set("status", AiMemoryJobStatus.RUNNING.name())
                    .set("lease_owner", owner)
                    .set("lease_expires_at", leaseExpiresAt)
                    .set("completed_at", null)
                    .set("updated_at", now)
                    .setSql("attempt_count = attempt_count + 1");
            if (candidate.getStartedAt() == null) {
                update.set("started_at", now);
            }
            int updated = jobMapper.update(null, update);
            if (updated > 0) {
                candidate.setStatus(AiMemoryJobStatus.RUNNING.name());
                candidate.setLeaseOwner(owner);
                candidate.setLeaseExpiresAt(leaseExpiresAt);
                candidate.setCompletedAt(null);
                candidate.setUpdatedAt(now);
                if (candidate.getStartedAt() == null) {
                    candidate.setStartedAt(now);
                }
                candidate.setAttemptCount((candidate.getAttemptCount() == null ? 0 : candidate.getAttemptCount()) + 1);
                claimed.add(candidate);
            }
        }
        return claimed;
    }

    @Transactional
    public void complete(AiMemoryJobEntity job) {
        if (job == null || job.getId() == null) {
            return;
        }
        LocalDateTime now = now();
        job.setStatus(AiMemoryJobStatus.COMPLETED.name());
        job.setLeaseOwner(null);
        job.setLeaseExpiresAt(null);
        job.setLastErrorSummary(null);
        job.setCompletedAt(now);
        job.setUpdatedAt(now);
        jobMapper.updateById(job);
    }

    @Transactional
    public void complete(Long jobId) {
        AiMemoryJobEntity job = jobMapper.selectById(jobId);
        complete(job);
    }

    @Transactional
    public void failRetryableOrFinal(AiMemoryJobEntity job, Throwable throwable) {
        failRetryableOrFinal(job, sanitizer.sanitizeErrorSummary(throwable));
    }

    @Transactional
    public void failRetryableOrFinal(AiMemoryJobEntity job, String rawErrorSummary) {
        if (job == null || job.getId() == null) {
            return;
        }
        LocalDateTime now = now();
        String safeError = sanitizer.sanitizeErrorSummary(rawErrorSummary);
        int attempts = Math.max(0, job.getAttemptCount() == null ? 0 : job.getAttemptCount());
        int maxAttempts = Math.max(1, job.getMaxAttempts() == null ? properties.getMemoryJobs().getMaxAttempts() : job.getMaxAttempts());
        boolean retryable = attempts < maxAttempts;
        job.setStatus(retryable ? AiMemoryJobStatus.FAILED_RETRYABLE.name() : AiMemoryJobStatus.FAILED_FINAL.name());
        job.setLeaseOwner(null);
        job.setLeaseExpiresAt(null);
        job.setLastErrorSummary(safeError);
        job.setCompletedAt(retryable ? null : now);
        job.setNextRunAt(retryable ? now.plusSeconds(backoffSeconds(attempts)) : now);
        job.setUpdatedAt(now);
        jobMapper.updateById(job);
    }

    @Transactional
    public void failFinal(AiMemoryJobEntity job, String rawErrorSummary) {
        if (job == null || job.getId() == null) {
            return;
        }
        LocalDateTime now = now();
        job.setStatus(AiMemoryJobStatus.FAILED_FINAL.name());
        job.setLeaseOwner(null);
        job.setLeaseExpiresAt(null);
        job.setLastErrorSummary(sanitizer.sanitizeErrorSummary(rawErrorSummary));
        job.setNextRunAt(now);
        job.setCompletedAt(now);
        job.setUpdatedAt(now);
        jobMapper.updateById(job);
    }

    @Transactional
    public void failFinal(AiMemoryJobEntity job, Throwable throwable) {
        failFinal(job, sanitizer.sanitizeErrorSummary(throwable));
    }

    private AiMemoryJobEntity findByIdempotencyKey(String idempotencyKey) {
        return jobMapper.selectOne(new QueryWrapper<AiMemoryJobEntity>()
                .eq("idempotency_key", idempotencyKey)
                .last("LIMIT 1"));
    }

    private String toJson(Object payload) {
        Object sanitized = sanitizer.sanitizePayload(payload == null ? Map.of() : payload);
        try {
            return objectMapper.writeValueAsString(sanitized);
        } catch (JsonProcessingException ex) {
            return "{\"error\":\"payload_serialization_failed\"}";
        }
    }

    private int normalizeMaxAttempts(Integer requested) {
        if (requested == null || requested <= 0) {
            return Math.max(1, properties.getMemoryJobs().getMaxAttempts());
        }
        return Math.max(1, Math.min(20, requested));
    }

    private long backoffSeconds(int attempts) {
        long base = Math.max(1, properties.getMemoryJobs().getBackoffBaseSeconds());
        int exponent = Math.max(0, Math.min(6, attempts - 1));
        return Math.min(MAX_BACKOFF_SECONDS, base * (1L << exponent));
    }

    private String normalizeOwner(String requestedOwner) {
        if (requestedOwner == null || requestedOwner.isBlank()) {
            return "ai-memory-job-worker";
        }
        return requestedOwner.length() > 128 ? requestedOwner.substring(0, 128) : requestedOwner;
    }

    private LocalDateTime now() {
        return LocalDateTime.now(clock);
    }

    private void requireText(String value, String name) {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException(name + " is required");
        }
    }
}
