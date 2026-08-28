package com.aioj.next.ai.agent.asyncjob;

import com.aioj.next.ai.persistence.entity.AiAsyncJobEntity;
import com.aioj.next.ai.persistence.mapper.AiAsyncJobMapper;
import com.baomidou.mybatisplus.core.conditions.query.QueryWrapper;
import com.baomidou.mybatisplus.core.conditions.update.UpdateWrapper;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.dao.DuplicateKeyException;

import java.time.LocalDateTime;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class AgentAsyncJobServiceTest {

    private final AiAsyncJobMapper mapper = mock(AiAsyncJobMapper.class);
    private final AgentAsyncJobService service = new AgentAsyncJobService(mapper);

    @Test
    void enqueueInsertsPendingJob() {
        when(mapper.insert(any(AiAsyncJobEntity.class))).thenReturn(1);

        boolean inserted = service.enqueue("TURN_CURATE", "TURN_CURATE:t-1", "{\"turnId\":\"t-1\"}", 5);

        assertThat(inserted).isTrue();
        ArgumentCaptor<AiAsyncJobEntity> captor = ArgumentCaptor.forClass(AiAsyncJobEntity.class);
        verify(mapper).insert(captor.capture());
        AiAsyncJobEntity job = captor.getValue();
        assertThat(job.getStatus()).isEqualTo(AgentAsyncJobService.STATUS_PENDING);
        assertThat(job.getIdempotencyKey()).isEqualTo("TURN_CURATE:t-1");
        assertThat(job.getAttemptCount()).isEqualTo(0);
        assertThat(job.getMaxAttempts()).isEqualTo(5);
    }

    @Test
    void enqueueDuplicateKeyIsIdempotentNoop() {
        when(mapper.insert(any(AiAsyncJobEntity.class))).thenThrow(new DuplicateKeyException("dup"));

        boolean inserted = service.enqueue("TURN_CURATE", "TURN_CURATE:t-1", "{}", 5);

        assertThat(inserted).isFalse();
    }

    @Test
    @SuppressWarnings("unchecked")
    void claimDueJobsLeasesViaCasAndSkipsLostRows() {
        AiAsyncJobEntity won = job(1L);
        AiAsyncJobEntity lost = job(2L);
        when(mapper.selectList(any(QueryWrapper.class)))
                .thenReturn(List.of(won, lost))
                .thenReturn(List.of());
        when(mapper.update(isNull(), any(UpdateWrapper.class)))
                .thenReturn(1)  // won: CAS success
                .thenReturn(0); // lost: claimed by another instance

        List<AiAsyncJobEntity> claimed = service.claimDueJobs(4, 120L, "worker-1");

        assertThat(claimed).containsExactly(won);
        assertThat(won.getStatus()).isEqualTo(AgentAsyncJobService.STATUS_RUNNING);
        assertThat(won.getLeaseOwner()).isEqualTo("worker-1");
    }

    @Test
    @SuppressWarnings("unchecked")
    void failBackoffKeepsPendingUntilAttemptsExhausted() {
        AiAsyncJobEntity job = job(1L);
        job.setAttemptCount(1);
        job.setMaxAttempts(5);

        service.fail(job, new RuntimeException("boom"), 60L);

        ArgumentCaptor<UpdateWrapper> captor = ArgumentCaptor.forClass(UpdateWrapper.class);
        verify(mapper).update(isNull(), captor.capture());
        String sqlSet = captor.getValue().getSqlSet();
        assertThat(sqlSet).contains("status=");
        assertThat(sqlSet).contains("next_retry_at=");
        assertThat(sqlSet).contains("attempt_count=");
        assertThat(sqlSet).doesNotContain("completed_at=");
    }

    @Test
    @SuppressWarnings("unchecked")
    void failMarksFailedWhenAttemptsExhausted() {
        AiAsyncJobEntity job = job(1L);
        job.setAttemptCount(4);
        job.setMaxAttempts(5);

        service.fail(job, new RuntimeException("boom"), 60L);

        ArgumentCaptor<UpdateWrapper> captor = ArgumentCaptor.forClass(UpdateWrapper.class);
        verify(mapper).update(isNull(), captor.capture());
        String sqlSet = captor.getValue().getSqlSet();
        assertThat(sqlSet).contains("completed_at=");
        assertThat(sqlSet).doesNotContain("next_retry_at=");
    }

    @Test
    @SuppressWarnings("unchecked")
    void completeClearsLease() {
        AiAsyncJobEntity job = job(1L);
        job.setStatus(AgentAsyncJobService.STATUS_RUNNING);

        service.complete(job);

        ArgumentCaptor<UpdateWrapper> captor = ArgumentCaptor.forClass(UpdateWrapper.class);
        verify(mapper).update(isNull(), captor.capture());
        String sqlSet = captor.getValue().getSqlSet();
        assertThat(sqlSet).contains("status=");
        assertThat(sqlSet).contains("completed_at=");
        assertThat(sqlSet).contains("lease_owner=");
    }

    private AiAsyncJobEntity job(Long id) {
        AiAsyncJobEntity job = new AiAsyncJobEntity();
        job.setId(id);
        job.setJobType("TURN_CURATE");
        job.setStatus(AgentAsyncJobService.STATUS_PENDING);
        job.setAttemptCount(0);
        job.setMaxAttempts(5);
        job.setNextRetryAt(LocalDateTime.now().minusSeconds(1));
        return job;
    }
}
