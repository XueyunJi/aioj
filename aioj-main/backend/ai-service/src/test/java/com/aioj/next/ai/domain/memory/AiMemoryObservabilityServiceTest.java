package com.aioj.next.ai.domain.memory;

import com.aioj.next.ai.domain.AiFailureMetrics;
import com.aioj.next.ai.persistence.entity.AiMemoryJobEntity;
import com.aioj.next.ai.persistence.mapper.AiDomainEventMapper;
import com.aioj.next.ai.persistence.mapper.AiMemoryJobMapper;
import com.baomidou.mybatisplus.core.conditions.query.QueryWrapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class AiMemoryObservabilityServiceTest {
    @Mock
    private AiMemoryJobMapper jobMapper;
    @Mock
    private AiDomainEventMapper eventMapper;

    private AiMemoryObservabilityService service;

    @BeforeEach
    void setUp() {
        service = new AiMemoryObservabilityService(jobMapper, eventMapper, new AiMemoryEventPayloadSanitizer());
    }

    @Test
    void summarizesJobAndEventCountsWithSafeRecentFailures() {
        when(jobMapper.selectMaps(any(QueryWrapper.class))).thenReturn(
                List.of(Map.of("metric_key", "QUEUED", "metric_count", 3L), Map.of("metric_key", "FAILED_FINAL", "metric_count", 1L)),
                List.of(Map.of("metric_key", "AI_AFTER_TURN_MEMORY_PROFILE", "metric_count", 4L))
        );
        when(eventMapper.selectMaps(any(QueryWrapper.class))).thenReturn(
                List.of(Map.of("metric_key", "AI_CHAT_TURN_COMPLETED", "metric_count", 5L))
        );
        when(jobMapper.selectCount(any(QueryWrapper.class))).thenReturn(2L, 1L);
        when(jobMapper.selectList(any(QueryWrapper.class))).thenReturn(List.of(failedJob()));

        var summary = service.summary();

        assertThat(summary.jobsByStatus()).extracting("key").contains("QUEUED", "FAILED_FINAL");
        assertThat(summary.jobsByType()).singleElement().satisfies(metric -> {
            assertThat(metric.key()).isEqualTo("AI_AFTER_TURN_MEMORY_PROFILE");
            assertThat(metric.count()).isEqualTo(4L);
        });
        assertThat(summary.eventsByType()).singleElement().satisfies(metric -> {
            assertThat(metric.key()).isEqualTo("AI_CHAT_TURN_COMPLETED");
            assertThat(metric.count()).isEqualTo(5L);
        });
        assertThat(summary.dueJobCount()).isEqualTo(2L);
        assertThat(summary.expiredLeaseCount()).isEqualTo(1L);
        assertThat(summary.recentFinalFailures()).singleElement().satisfies(job -> {
            assertThat(job.jobId()).isEqualTo(10L);
            assertThat(job.lastErrorSummary())
                    .contains("[raw output omitted]")
                    .doesNotContain("hidden output", "sk-secret-value", "#include");
        });
    }

    @Test
    void summaryExposesJobFailureRateAndInProcessFailureCounters() {
        AiFailureMetrics.reset();
        AiFailureMetrics.incrementMemoryExtractionFailure();
        AiFailureMetrics.incrementEmbeddingFailure();
        AiFailureMetrics.incrementEmbeddingFailure();
        AiFailureMetrics.incrementEmbeddingCapacityRejection();
        when(jobMapper.selectMaps(any(QueryWrapper.class))).thenReturn(
                List.of(Map.of("metric_key", "QUEUED", "metric_count", 3L), Map.of("metric_key", "FAILED_FINAL", "metric_count", 1L)),
                List.of(Map.of("metric_key", "AI_AFTER_TURN_MEMORY_PROFILE", "metric_count", 4L))
        );
        when(eventMapper.selectMaps(any(QueryWrapper.class))).thenReturn(List.of());
        when(jobMapper.selectCount(any(QueryWrapper.class))).thenReturn(0L, 0L);
        when(jobMapper.selectList(any(QueryWrapper.class))).thenReturn(List.of());

        var summary = service.summary();

        assertThat(summary.totalJobCount()).isEqualTo(4L);
        assertThat(summary.jobFailureRate()).isEqualTo(0.25);
        assertThat(summary.memoryExtractionFailureCount()).isEqualTo(1L);
        assertThat(summary.embeddingFailureCount()).isEqualTo(2L);
        assertThat(summary.embeddingCapacityRejectedCount()).isEqualTo(1L);
    }

    private static AiMemoryJobEntity failedJob() {
        AiMemoryJobEntity job = new AiMemoryJobEntity();
        job.setId(10L);
        job.setJobType("AI_AFTER_TURN_MEMORY_PROFILE");
        job.setStatus(AiMemoryJobStatus.FAILED_FINAL.name());
        job.setAttemptCount(3);
        job.setMaxAttempts(3);
        job.setNextRunAt(LocalDateTime.now());
        job.setUpdatedAt(LocalDateTime.now());
        job.setLastErrorSummary("""
                stdout:
                hidden output

                #include <bits/stdc++.h>
                token=sk-secret-value
                """);
        return job;
    }
}
