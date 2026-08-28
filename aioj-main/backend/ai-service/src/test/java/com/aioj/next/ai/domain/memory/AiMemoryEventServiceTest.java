package com.aioj.next.ai.domain.memory;

import com.aioj.next.ai.config.AiProperties;
import com.aioj.next.ai.persistence.entity.AiDomainEventEntity;
import com.aioj.next.ai.persistence.entity.AiMemoryJobEntity;
import com.aioj.next.ai.persistence.mapper.AiDomainEventMapper;
import com.aioj.next.ai.persistence.mapper.AiMemoryJobMapper;
import com.baomidou.mybatisplus.core.conditions.query.QueryWrapper;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.time.Clock;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class AiMemoryEventServiceTest {
    @Mock
    private AiDomainEventMapper eventMapper;
    @Mock
    private AiMemoryJobMapper jobMapper;

    private AiMemoryEventService eventService;

    @BeforeEach
    void setUp() {
        Clock clock = Clock.fixed(Instant.parse("2026-01-01T00:00:00Z"), ZoneOffset.UTC);
        AiMemoryEventPayloadSanitizer sanitizer = new AiMemoryEventPayloadSanitizer();
        ObjectMapper objectMapper = new ObjectMapper();
        AiMemoryJobService jobService = new AiMemoryJobService(jobMapper, sanitizer, objectMapper, new AiProperties(), clock);
        eventService = new AiMemoryEventService(eventMapper, jobService, sanitizer, objectMapper, clock);
    }

    @Test
    void recordEventWritesSanitizedEventAndIdempotentJobs() {
        when(eventMapper.selectOne(any(QueryWrapper.class))).thenReturn(null);
        when(jobMapper.selectOne(any(QueryWrapper.class))).thenReturn(null);
        doAnswer(invocation -> {
            AiDomainEventEntity event = invocation.getArgument(0);
            event.setId(88L);
            return 1;
        }).when(eventMapper).insert(any(AiDomainEventEntity.class));
        doAnswer(invocation -> {
            AiMemoryJobEntity job = invocation.getArgument(0);
            job.setId(99L);
            return 1;
        }).when(jobMapper).insert(any(AiMemoryJobEntity.class));

        AiMemoryEventService.RecordedEvent recorded = eventService.recordEvent(
                "AI_CHAT_TURN_COMPLETED",
                7L,
                "conversation",
                "42",
                "event-1",
                Map.of("sourceCode", "int main(){return 0;}", "note", "stdout:\nhidden-output-content\n\nsafe"),
                AiMemoryEventService.SENSITIVITY_USER_PRIVATE_SAFE,
                List.of(new AiMemoryEventService.EventJobSpec(
                        "MEMORY_EXTRACT",
                        "job-1",
                        Map.of("stderr", "hidden", "note", "token=plain-secret"),
                        3,
                        LocalDateTime.of(2026, 1, 1, 0, 5)
                ))
        );

        ArgumentCaptor<AiDomainEventEntity> eventCaptor = ArgumentCaptor.forClass(AiDomainEventEntity.class);
        ArgumentCaptor<AiMemoryJobEntity> jobCaptor = ArgumentCaptor.forClass(AiMemoryJobEntity.class);
        verify(eventMapper).insert(eventCaptor.capture());
        verify(jobMapper).insert(jobCaptor.capture());

        AiDomainEventEntity event = eventCaptor.getValue();
        AiMemoryJobEntity job = jobCaptor.getValue();
        assertThat(recorded.event()).isSameAs(event);
        assertThat(recorded.jobs()).containsExactly(job);
        assertThat(event.getPayloadJson())
                .contains(AiMemoryEventPayloadSanitizer.OMITTED)
                .contains("[raw output omitted]")
                .doesNotContain("int main")
                .doesNotContain("hidden-output-content");
        assertThat(job.getEventId()).isEqualTo(88L);
        assertThat(job.getPayloadJson())
                .contains(AiMemoryEventPayloadSanitizer.OMITTED)
                .doesNotContain("hidden")
                .doesNotContain("plain-secret");
    }

    @Test
    void recordEventReturnsExistingEventForDuplicateIdempotencyKey() {
        AiDomainEventEntity existing = new AiDomainEventEntity();
        existing.setId(88L);
        existing.setEventType("AI_CHAT_TURN_COMPLETED");
        existing.setIdempotencyKey("event-1");
        when(eventMapper.selectOne(any(QueryWrapper.class))).thenReturn(existing);

        AiDomainEventEntity result = eventService.recordEvent(
                "AI_CHAT_TURN_COMPLETED",
                7L,
                "conversation",
                "42",
                "event-1",
                Map.of()
        );

        assertThat(result).isSameAs(existing);
        verify(eventMapper, never()).insert(any(AiDomainEventEntity.class));
    }
}
