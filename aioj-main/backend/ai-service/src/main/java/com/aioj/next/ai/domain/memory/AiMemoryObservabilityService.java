package com.aioj.next.ai.domain.memory;

import com.aioj.next.ai.domain.AiFailureMetrics;
import com.aioj.next.ai.persistence.entity.AiDomainEventEntity;
import com.aioj.next.ai.persistence.entity.AiMemoryJobEntity;
import com.aioj.next.ai.persistence.mapper.AiDomainEventMapper;
import com.aioj.next.ai.persistence.mapper.AiMemoryJobMapper;
import com.aioj.next.contract.ai.AiMemoryObservabilityMetricResponse;
import com.aioj.next.contract.ai.AiMemoryObservabilityRecentJobResponse;
import com.aioj.next.contract.ai.AiMemoryObservabilityResponse;
import com.baomidou.mybatisplus.core.conditions.query.QueryWrapper;
import org.springframework.stereotype.Service;

import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import java.util.Map;

@Service
public class AiMemoryObservabilityService {
    private static final int RECENT_FAILURE_LIMIT = 10;
    private static final ZoneId ZONE = ZoneId.systemDefault();

    private final AiMemoryJobMapper jobMapper;
    private final AiDomainEventMapper eventMapper;
    private final AiMemoryEventPayloadSanitizer sanitizer;

    public AiMemoryObservabilityService(
            AiMemoryJobMapper jobMapper,
            AiDomainEventMapper eventMapper,
            AiMemoryEventPayloadSanitizer sanitizer
    ) {
        this.jobMapper = jobMapper;
        this.eventMapper = eventMapper;
        this.sanitizer = sanitizer;
    }

    public AiMemoryObservabilityResponse summary() {
        LocalDateTime now = LocalDateTime.now();
        List<AiMemoryObservabilityMetricResponse> jobsByStatus = groupedJobMetric("status", "status");
        long totalJobs = jobsByStatus.stream().mapToLong(AiMemoryObservabilityMetricResponse::count).sum();
        long failedFinalJobs = jobsByStatus.stream()
                .filter(metric -> AiMemoryJobStatus.FAILED_FINAL.name().equals(metric.key()))
                .mapToLong(AiMemoryObservabilityMetricResponse::count)
                .sum();
        double failureRate = totalJobs > 0
                ? Math.round(((double) failedFinalJobs / totalJobs) * 10_000.0) / 10_000.0
                : 0.0;
        return new AiMemoryObservabilityResponse(
                Instant.now(),
                jobsByStatus,
                groupedJobMetric("job_type", "jobType"),
                groupedEventMetric("event_type"),
                countDueJobs(now),
                countExpiredLeases(now),
                recentFinalFailures(),
                totalJobs,
                failureRate,
                AiFailureMetrics.memoryExtractionFailures(),
                AiFailureMetrics.embeddingFailures(),
                AiFailureMetrics.embeddingCapacityRejections()
        );
    }

    private List<AiMemoryObservabilityMetricResponse> groupedJobMetric(String column, String fallbackKey) {
        return metricRows(jobMapper.selectMaps(new QueryWrapper<AiMemoryJobEntity>()
                .select(column + " AS metric_key", "COUNT(*) AS metric_count")
                .groupBy(column)), fallbackKey);
    }

    private List<AiMemoryObservabilityMetricResponse> groupedEventMetric(String column) {
        return metricRows(eventMapper.selectMaps(new QueryWrapper<AiDomainEventEntity>()
                .select(column + " AS metric_key", "COUNT(*) AS metric_count")
                .groupBy(column)), column);
    }

    private long countDueJobs(LocalDateTime now) {
        Long count = jobMapper.selectCount(new QueryWrapper<AiMemoryJobEntity>()
                .in("status", AiMemoryJobStatus.QUEUED.name(), AiMemoryJobStatus.FAILED_RETRYABLE.name())
                .le("next_run_at", now));
        return count == null ? 0L : count;
    }

    private long countExpiredLeases(LocalDateTime now) {
        Long count = jobMapper.selectCount(new QueryWrapper<AiMemoryJobEntity>()
                .eq("status", AiMemoryJobStatus.RUNNING.name())
                .le("lease_expires_at", now));
        return count == null ? 0L : count;
    }

    private List<AiMemoryObservabilityRecentJobResponse> recentFinalFailures() {
        List<AiMemoryJobEntity> jobs = jobMapper.selectList(new QueryWrapper<AiMemoryJobEntity>()
                .eq("status", AiMemoryJobStatus.FAILED_FINAL.name())
                .orderByDesc("updated_at")
                .last("LIMIT " + RECENT_FAILURE_LIMIT));
        if (jobs == null || jobs.isEmpty()) {
            return List.of();
        }
        return jobs.stream()
                .filter(job -> job != null && job.getId() != null)
                .map(job -> new AiMemoryObservabilityRecentJobResponse(
                        job.getId(),
                        blankToDefault(job.getJobType(), "UNKNOWN"),
                        blankToDefault(job.getStatus(), AiMemoryJobStatus.FAILED_FINAL.name()),
                        job.getAttemptCount(),
                        job.getMaxAttempts(),
                        instant(job.getNextRunAt()),
                        instant(job.getUpdatedAt()),
                        sanitizer.sanitizeErrorSummary(job.getLastErrorSummary())
                ))
                .toList();
    }

    private List<AiMemoryObservabilityMetricResponse> metricRows(List<Map<String, Object>> rows, String fallbackKey) {
        if (rows == null || rows.isEmpty()) {
            return List.of();
        }
        List<AiMemoryObservabilityMetricResponse> metrics = new ArrayList<>();
        for (Map<String, Object> row : rows) {
            String key = metricKey(row, fallbackKey);
            long count = metricCount(row);
            if (!key.isBlank()) {
                metrics.add(new AiMemoryObservabilityMetricResponse(key, count));
            }
        }
        metrics.sort(Comparator.comparing(AiMemoryObservabilityMetricResponse::key));
        return metrics;
    }

    private String metricKey(Map<String, Object> row, String fallbackKey) {
        Object value = firstPresent(row, "metric_key", "metricKey", fallbackKey, fallbackKey.toUpperCase(Locale.ROOT));
        return value == null ? "" : String.valueOf(value);
    }

    private long metricCount(Map<String, Object> row) {
        Object value = firstPresent(row, "metric_count", "metricCount", "count", "COUNT(*)");
        if (value instanceof Number number) {
            return number.longValue();
        }
        if (value == null) {
            return 0L;
        }
        try {
            return Long.parseLong(String.valueOf(value));
        } catch (NumberFormatException ex) {
            return 0L;
        }
    }

    private Object firstPresent(Map<String, Object> row, String... keys) {
        if (row == null || row.isEmpty()) {
            return null;
        }
        for (String key : keys) {
            if (row.containsKey(key)) {
                return row.get(key);
            }
        }
        for (Map.Entry<String, Object> entry : row.entrySet()) {
            String normalized = entry.getKey() == null ? "" : entry.getKey().replace("_", "").toLowerCase(Locale.ROOT);
            for (String key : keys) {
                if (normalized.equals(key.replace("_", "").toLowerCase(Locale.ROOT))) {
                    return entry.getValue();
                }
            }
        }
        return null;
    }

    private Instant instant(LocalDateTime value) {
        return value == null ? null : value.atZone(ZONE).toInstant();
    }

    private String blankToDefault(String value, String fallback) {
        return value == null || value.isBlank() ? fallback : value;
    }
}
