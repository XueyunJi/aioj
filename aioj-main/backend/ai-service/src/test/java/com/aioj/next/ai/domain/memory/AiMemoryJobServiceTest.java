package com.aioj.next.ai.domain.memory;

import com.aioj.next.ai.config.AiProperties;
import com.aioj.next.ai.persistence.entity.AiMemoryJobEntity;
import com.aioj.next.ai.persistence.mapper.AiMemoryJobMapper;
import com.baomidou.mybatisplus.core.conditions.query.QueryWrapper;
import com.baomidou.mybatisplus.core.conditions.update.UpdateWrapper;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.time.Clock;
import java.time.Duration;
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
class AiMemoryJobServiceTest {
    @Mock
    private AiMemoryJobMapper jobMapper;

    private AiMemoryJobService service;

    @BeforeEach
    void setUp() {
        AiProperties properties = new AiProperties();
        properties.getMemoryJobs().setBackoffBaseSeconds(30);
        properties.getMemoryJobs().setMaxAttempts(3);
        Clock clock = Clock.fixed(Instant.parse("2026-01-01T00:00:00Z"), ZoneOffset.UTC);
        service = new AiMemoryJobService(
                jobMapper,
                new AiMemoryEventPayloadSanitizer(),
                new ObjectMapper(),
                properties,
                clock
        );
    }

    @Test
    void enqueueCreatesSanitizedIdempotentJob() {
        when(jobMapper.selectOne(any(QueryWrapper.class))).thenReturn(null);
        doAnswer(invocation -> {
            AiMemoryJobEntity job = invocation.getArgument(0);
            job.setId(100L);
            return 1;
        }).when(jobMapper).insert(any(AiMemoryJobEntity.class));

        AiMemoryJobEntity job = service.enqueue(
                7L,
                "MEMORY_EXTRACT",
                "job-1",
                Map.of("codeText", "int main(){return 0;}", "note", "token=secret-token"),
                null,
                null
        );

        ArgumentCaptor<AiMemoryJobEntity> captor = ArgumentCaptor.forClass(AiMemoryJobEntity.class);
        verify(jobMapper).insert(captor.capture());
        AiMemoryJobEntity inserted = captor.getValue();

        assertThat(job).isSameAs(inserted);
        assertThat(inserted.getId()).isEqualTo(100L);
        assertThat(inserted.getEventId()).isEqualTo(7L);
        assertThat(inserted.getStatus()).isEqualTo(AiMemoryJobStatus.QUEUED.name());
        assertThat(inserted.getAttemptCount()).isZero();
        assertThat(inserted.getMaxAttempts()).isEqualTo(3);
        assertThat(inserted.getPayloadJson())
                .contains(AiMemoryEventPayloadSanitizer.OMITTED)
                .doesNotContain("int main")
                .doesNotContain("secret-token");
    }

    @Test
    void enqueueReturnsExistingDuplicateByIdempotencyKey() {
        AiMemoryJobEntity existing = job(10L, AiMemoryJobStatus.QUEUED, 0, 3);
        when(jobMapper.selectOne(any(QueryWrapper.class))).thenReturn(existing);

        AiMemoryJobEntity result = service.enqueue(7L, "MEMORY_EXTRACT", "job-1", Map.of(), null, null);

        assertThat(result).isSameAs(existing);
        verify(jobMapper, never()).insert(any(AiMemoryJobEntity.class));
    }

    @Test
    void claimDueJobsMarksJobRunningAndSetsLease() {
        AiMemoryJobEntity queued = job(10L, AiMemoryJobStatus.QUEUED, 0, 3);
        queued.setNextRunAt(LocalDateTime.of(2025, 12, 31, 23, 59));
        when(jobMapper.selectList(any(QueryWrapper.class))).thenReturn(List.of(queued));
        when(jobMapper.update(any(), any(UpdateWrapper.class))).thenReturn(1);

        List<AiMemoryJobEntity> claimed = service.claimDueJobs(20, Duration.ofSeconds(45), "worker-a");

        assertThat(claimed).containsExactly(queued);
        assertThat(queued.getStatus()).isEqualTo(AiMemoryJobStatus.RUNNING.name());
        assertThat(queued.getAttemptCount()).isEqualTo(1);
        assertThat(queued.getLeaseOwner()).isEqualTo("worker-a");
        assertThat(queued.getLeaseExpiresAt()).isEqualTo(LocalDateTime.of(2026, 1, 1, 0, 0, 45));
        verify(jobMapper).update(any(), any(UpdateWrapper.class));
    }

    @Test
    void completeClearsLeaseAndFinalizesJob() {
        AiMemoryJobEntity running = job(10L, AiMemoryJobStatus.RUNNING, 1, 3);
        running.setLeaseOwner("worker-a");
        running.setLeaseExpiresAt(LocalDateTime.of(2026, 1, 1, 0, 1));

        service.complete(running);

        assertThat(running.getStatus()).isEqualTo(AiMemoryJobStatus.COMPLETED.name());
        assertThat(running.getLeaseOwner()).isNull();
        assertThat(running.getLeaseExpiresAt()).isNull();
        assertThat(running.getCompletedAt()).isEqualTo(LocalDateTime.of(2026, 1, 1, 0, 0));
        verify(jobMapper).updateById(running);
    }

    @Test
    void failRetryableOrFinalUsesAttemptCountAndSanitizedError() {
        AiMemoryJobEntity retryable = job(10L, AiMemoryJobStatus.RUNNING, 1, 3);
        service.failRetryableOrFinal(retryable, "stderr:\nraw secret\n\napiKey=sk-secret");

        assertThat(retryable.getStatus()).isEqualTo(AiMemoryJobStatus.FAILED_RETRYABLE.name());
        assertThat(retryable.getCompletedAt()).isNull();
        assertThat(retryable.getNextRunAt()).isEqualTo(LocalDateTime.of(2026, 1, 1, 0, 0, 30));
        assertThat(retryable.getLastErrorSummary())
                .contains("[raw output omitted]")
                .doesNotContain("raw secret")
                .doesNotContain("sk-secret");

        AiMemoryJobEntity finalFailure = job(11L, AiMemoryJobStatus.RUNNING, 3, 3);
        service.failRetryableOrFinal(finalFailure, "No handler");

        assertThat(finalFailure.getStatus()).isEqualTo(AiMemoryJobStatus.FAILED_FINAL.name());
        assertThat(finalFailure.getCompletedAt()).isEqualTo(LocalDateTime.of(2026, 1, 1, 0, 0));
        verify(jobMapper).updateById(retryable);
        verify(jobMapper).updateById(finalFailure);
    }

    private AiMemoryJobEntity job(Long id, AiMemoryJobStatus status, int attempts, int maxAttempts) {
        AiMemoryJobEntity job = new AiMemoryJobEntity();
        job.setId(id);
        job.setEventId(7L);
        job.setJobType("MEMORY_EXTRACT");
        job.setStatus(status.name());
        job.setIdempotencyKey("job-" + id);
        job.setAttemptCount(attempts);
        job.setMaxAttempts(maxAttempts);
        job.setNextRunAt(LocalDateTime.of(2026, 1, 1, 0, 0));
        job.setCreatedAt(LocalDateTime.of(2026, 1, 1, 0, 0));
        job.setUpdatedAt(LocalDateTime.of(2026, 1, 1, 0, 0));
        return job;
    }
}
