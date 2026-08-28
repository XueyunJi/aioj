package com.aioj.next.ai.domain.memory;

import com.aioj.next.ai.persistence.entity.AiDomainEventEntity;
import com.aioj.next.ai.persistence.entity.AiMemoryJobEntity;
import com.aioj.next.ai.persistence.mapper.AiDomainEventMapper;
import com.baomidou.mybatisplus.core.conditions.query.QueryWrapper;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Clock;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

@Service
public class AiMemoryEventService {
    public static final String SENSITIVITY_USER_PRIVATE_SAFE = "USER_PRIVATE_SAFE";

    private final AiDomainEventMapper eventMapper;
    private final AiMemoryJobService jobService;
    private final AiMemoryEventPayloadSanitizer sanitizer;
    private final ObjectMapper objectMapper;
    private final Clock clock;

    @Autowired
    public AiMemoryEventService(
            AiDomainEventMapper eventMapper,
            AiMemoryJobService jobService,
            AiMemoryEventPayloadSanitizer sanitizer,
            ObjectMapper objectMapper
    ) {
        this(eventMapper, jobService, sanitizer, objectMapper, Clock.systemDefaultZone());
    }

    AiMemoryEventService(
            AiDomainEventMapper eventMapper,
            AiMemoryJobService jobService,
            AiMemoryEventPayloadSanitizer sanitizer,
            ObjectMapper objectMapper,
            Clock clock
    ) {
        this.eventMapper = eventMapper;
        this.jobService = jobService;
        this.sanitizer = sanitizer;
        this.objectMapper = objectMapper;
        this.clock = clock == null ? Clock.systemDefaultZone() : clock;
    }

    @Transactional
    public RecordedEvent recordEvent(
            String eventType,
            Long userId,
            String sourceType,
            String sourceId,
            String idempotencyKey,
            Object payload,
            String sensitivity,
            List<EventJobSpec> jobs
    ) {
        AiDomainEventEntity event = ensureEvent(eventType, userId, sourceType, sourceId, idempotencyKey, payload, sensitivity);
        List<AiMemoryJobEntity> enqueuedJobs = new ArrayList<>();
        if (jobs != null) {
            for (EventJobSpec job : jobs) {
                if (job == null) {
                    continue;
                }
                enqueuedJobs.add(jobService.enqueue(
                        event.getId(),
                        job.jobType(),
                        job.idempotencyKey(),
                        job.payload(),
                        job.maxAttempts(),
                        job.nextRunAt()
                ));
            }
        }
        return new RecordedEvent(event, List.copyOf(enqueuedJobs));
    }

    @Transactional
    public AiDomainEventEntity recordEvent(
            String eventType,
            Long userId,
            String sourceType,
            String sourceId,
            String idempotencyKey,
            Object payload
    ) {
        return recordEvent(eventType, userId, sourceType, sourceId, idempotencyKey,
                payload, SENSITIVITY_USER_PRIVATE_SAFE, List.of()).event();
    }

    private AiDomainEventEntity ensureEvent(
            String eventType,
            Long userId,
            String sourceType,
            String sourceId,
            String idempotencyKey,
            Object payload,
            String sensitivity
    ) {
        requireText(eventType, "eventType");
        requireText(sourceType, "sourceType");
        requireText(idempotencyKey, "idempotencyKey");
        if (userId == null) {
            throw new IllegalArgumentException("userId is required");
        }
        AiDomainEventEntity existing = findByIdempotencyKey(idempotencyKey);
        if (existing != null) {
            return existing;
        }

        LocalDateTime now = LocalDateTime.now(clock);
        AiDomainEventEntity event = new AiDomainEventEntity();
        event.setEventType(eventType.strip());
        event.setUserId(userId);
        event.setSourceType(sourceType.strip());
        event.setSourceId(sourceId == null || sourceId.isBlank() ? null : sourceId.strip());
        event.setIdempotencyKey(idempotencyKey.strip());
        event.setPayloadJson(toJson(payload));
        event.setSensitivity(normalizeSensitivity(sensitivity));
        event.setCreatedAt(now);
        event.setUpdatedAt(now);
        try {
            eventMapper.insert(event);
            return event;
        } catch (DuplicateKeyException ex) {
            AiDomainEventEntity duplicate = findByIdempotencyKey(idempotencyKey);
            if (duplicate != null) {
                return duplicate;
            }
            throw ex;
        }
    }

    private AiDomainEventEntity findByIdempotencyKey(String idempotencyKey) {
        return eventMapper.selectOne(new QueryWrapper<AiDomainEventEntity>()
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

    private String normalizeSensitivity(String sensitivity) {
        if (sensitivity == null || sensitivity.isBlank()) {
            return SENSITIVITY_USER_PRIVATE_SAFE;
        }
        return sensitivity.length() > 48 ? sensitivity.substring(0, 48) : sensitivity;
    }

    private void requireText(String value, String name) {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException(name + " is required");
        }
    }

    public record EventJobSpec(
            String jobType,
            String idempotencyKey,
            Object payload,
            Integer maxAttempts,
            LocalDateTime nextRunAt
    ) {
    }

    public record RecordedEvent(AiDomainEventEntity event, List<AiMemoryJobEntity> jobs) {
    }
}
