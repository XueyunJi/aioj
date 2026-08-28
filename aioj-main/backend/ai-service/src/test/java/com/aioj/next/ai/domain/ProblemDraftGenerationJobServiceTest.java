package com.aioj.next.ai.domain;

import com.aioj.next.ai.domain.problem.ProblemDraftAuditContext;
import com.aioj.next.ai.domain.problem.ProblemDraftProgressListener;
import com.aioj.next.ai.domain.problem.ReferenceCheckPolicy;
import com.aioj.next.ai.persistence.entity.ProblemDraftGenerationJobEntity;
import com.aioj.next.ai.persistence.mapper.ProblemDraftGenerationJobMapper;
import com.aioj.next.common.error.DomainException;
import com.aioj.next.common.error.ErrorCode;
import com.aioj.next.common.security.Role;
import com.aioj.next.common.security.SecurityPrincipal;
import com.aioj.next.contract.ai.ProblemDraftRequest;
import com.aioj.next.contract.ai.ProblemDraftRegenerateRequest;
import com.aioj.next.contract.ai.ProblemDraftResponse;
import com.baomidou.mybatisplus.core.conditions.update.UpdateWrapper;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.AbstractPlatformTransactionManager;
import org.springframework.transaction.support.DefaultTransactionStatus;

import java.time.Instant;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Set;
import java.util.concurrent.Executor;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.atLeast;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class ProblemDraftGenerationJobServiceTest {
    @Mock
    private ProblemDraftGenerationJobMapper jobMapper;
    @Mock
    private ProblemDraftStore problemDraftStore;
    @Mock
    private OperationAuditWriter auditWriter;

    private ProblemDraftGenerationJobService service;

    @BeforeEach
    void setUp() {
        Executor directExecutor = Runnable::run;
        service = new ProblemDraftGenerationJobService(
                jobMapper,
                problemDraftStore,
                new ObjectMapper().findAndRegisterModules(),
                auditWriter,
                directExecutor,
                transactionManager()
        );
    }

    @AfterEach
    void tearDown() {
        SecurityContextHolder.clearContext();
    }

    @Test
    void createPersistsQueuedJobWithRequestSnapshot() {
        doAnswer(invocation -> {
            ProblemDraftGenerationJobEntity job = invocation.getArgument(0);
            job.setId(100L);
            return 1;
        }).when(jobMapper).insert(any(ProblemDraftGenerationJobEntity.class));

        var response = service.create(7L, request("线段树,排序"));

        ArgumentCaptor<ProblemDraftGenerationJobEntity> captor = ArgumentCaptor.forClass(ProblemDraftGenerationJobEntity.class);
        verify(jobMapper).insert(captor.capture());
        ProblemDraftGenerationJobEntity saved = captor.getValue();
        assertThat(saved.getCreatorUserId()).isEqualTo(7L);
        assertThat(saved.getJobType()).isEqualTo("GENERATE");
        assertThat(saved.getStatus()).isEqualTo("QUEUED");
        assertThat(saved.getStage()).isEqualTo("QUEUED");
        assertThat(saved.getTopicSnapshot()).isEqualTo("线段树,排序");
        assertThat(saved.getRequestJson()).contains("\"topic\":\"线段树,排序\"");
        assertThat(response.id()).isEqualTo(100L);
        assertThat(response.status()).isEqualTo("QUEUED");
        verify(auditWriter).record(eq("PROBLEM_DRAFT_GENERATION_JOB_CREATED"), eq("AI_PROBLEM_DRAFT_GENERATION_JOB"),
                eq(100L), eq("QUEUED"), any(), eq(7L), eq(null), eq(null), eq(null));
    }

    @Test
    void createRegenerationPersistsQueuedJobForSourceDraft() {
        when(problemDraftStore.regenerationSource(200L)).thenReturn(draft(200L));
        doAnswer(invocation -> {
            ProblemDraftGenerationJobEntity job = invocation.getArgument(0);
            job.setId(101L);
            return 1;
        }).when(jobMapper).insert(any(ProblemDraftGenerationJobEntity.class));

        var response = service.createRegeneration(7L, 200L,
                new ProblemDraftRegenerateRequest("只重新跑验证，不要修改题目主体"));

        ArgumentCaptor<ProblemDraftGenerationJobEntity> captor = ArgumentCaptor.forClass(ProblemDraftGenerationJobEntity.class);
        verify(jobMapper).insert(captor.capture());
        ProblemDraftGenerationJobEntity saved = captor.getValue();
        assertThat(saved.getJobType()).isEqualTo("REGENERATE");
        assertThat(saved.getSourceDraftId()).isEqualTo(200L);
        assertThat(saved.getStatus()).isEqualTo("QUEUED");
        assertThat(saved.getDraftId()).isNull();
        assertThat(response.id()).isEqualTo(101L);
        assertThat(response.jobType()).isEqualTo("REGENERATE");
        assertThat(response.sourceDraftId()).isEqualTo(200L);
        verify(auditWriter).record(eq("PROBLEM_DRAFT_REGENERATION_JOB_CREATED"),
                eq("AI_PROBLEM_DRAFT_GENERATION_JOB"), eq(101L), eq("QUEUED"), any(), eq(7L),
                eq(null), eq(null), eq(null));
    }

    @Test
    void createRegenerationReusesLatestSourceDraftJobAndMovesItToFront() {
        when(problemDraftStore.regenerationSource(200L)).thenReturn(draft(200L));
        ProblemDraftGenerationJobEntity existing = job(102L, 9L, "FAILED");
        existing.setJobType("REGENERATE");
        existing.setSourceDraftId(200L);
        existing.setDraftId(901L);
        existing.setErrorCode(ErrorCode.INTERNAL_ERROR.code());
        existing.setErrorMessage("old failure");
        when(jobMapper.selectOne(any())).thenReturn(existing);
        when(jobMapper.update(any(ProblemDraftGenerationJobEntity.class), any(UpdateWrapper.class))).thenReturn(1);

        var response = service.createRegeneration(7L, 200L,
                new ProblemDraftRegenerateRequest("修复官方隐藏点生成器"));

        ArgumentCaptor<ProblemDraftGenerationJobEntity> captor = ArgumentCaptor.forClass(ProblemDraftGenerationJobEntity.class);
        verify(jobMapper).update(captor.capture(), any(UpdateWrapper.class));
        ProblemDraftGenerationJobEntity update = captor.getValue();
        assertThat(update.getCreatorUserId()).isEqualTo(7L);
        assertThat(update.getStatus()).isEqualTo("QUEUED");
        assertThat(update.getProgressCurrent()).isZero();
        assertThat(existing.getDraftId()).isNull();
        assertThat(existing.getErrorMessage()).isNull();
        assertThat(response.id()).isEqualTo(102L);
        assertThat(response.creatorUserId()).isEqualTo(7L);
        assertThat(response.updatedAt()).isNotNull();
        verify(auditWriter).replaceProblemDraftGenerationJobLifecycle(eq(102L),
                eq("PROBLEM_DRAFT_REGENERATION_JOB_CREATED"), eq("QUEUED"), any());
    }

    @Test
    void processSuccessfulJobWritesDraftIdAndCompletedStatus() {
        when(jobMapper.update(any(ProblemDraftGenerationJobEntity.class), any(UpdateWrapper.class))).thenReturn(1);
        when(jobMapper.selectById(100L)).thenReturn(job(100L, 7L, "QUEUED"));
        when(problemDraftStore.generate(eq(7L), any(ProblemDraftRequest.class), any(ProblemDraftProgressListener.class),
                any(ProblemDraftAuditContext.class)))
                .thenAnswer(invocation -> {
                    ProblemDraftProgressListener listener = invocation.getArgument(2);
                    listener.onProgress("SANDBOX_VERIFYING", 3, 7, "Running sandbox verification");
                    return draft(900L);
                });

        service.process(100L);

        ArgumentCaptor<ProblemDraftGenerationJobEntity> captor = ArgumentCaptor.forClass(ProblemDraftGenerationJobEntity.class);
        verify(jobMapper, atLeast(3)).update(captor.capture(), any(UpdateWrapper.class));
        assertThat(captor.getAllValues())
                .extracting(ProblemDraftGenerationJobEntity::getStatus)
                .contains("RUNNING", "SUCCEEDED");
        ProblemDraftGenerationJobEntity completed = captor.getAllValues().stream()
                .filter(update -> "SUCCEEDED".equals(update.getStatus()))
                .findFirst()
                .orElseThrow();
        assertThat(completed.getDraftId()).isEqualTo(900L);
        assertThat(completed.getProgressCurrent()).isEqualTo(7);
        assertThat(completed.getCompletedAt()).isNotNull();
        ArgumentCaptor<ProblemDraftAuditContext> auditCaptor = ArgumentCaptor.forClass(ProblemDraftAuditContext.class);
        verify(problemDraftStore).generate(eq(7L), any(ProblemDraftRequest.class),
                any(ProblemDraftProgressListener.class), auditCaptor.capture());
        assertThat(auditCaptor.getValue().jobId()).isEqualTo(100L);
        verify(auditWriter).replaceProblemDraftGenerationJobLifecycle(eq(100L),
                eq("PROBLEM_DRAFT_GENERATION_JOB_RUNNING"), eq("RUNNING"), any());
        verify(auditWriter).replaceProblemDraftGenerationJobLifecycle(eq(100L),
                eq("PROBLEM_DRAFT_GENERATION_JOB_COMPLETED"), eq("COMPLETED"), any());
    }

    @Test
    void processSuccessfulHighRatingJobRecordsReferenceCheckDisabledRisk() {
        when(jobMapper.update(any(ProblemDraftGenerationJobEntity.class), any(UpdateWrapper.class))).thenReturn(1);
        ProblemDraftGenerationJobEntity job = job(100L, 7L, "QUEUED");
        job.setRequestJson("""
                {
                  "topic": "线段树,排序",
                  "difficulty": "HARD",
                  "cfRating": 2000,
                  "standardSolutionLanguage": "cpp",
                  "enableReferenceCheck": false
                }
                """);
        when(jobMapper.selectById(100L)).thenReturn(job);
        when(problemDraftStore.generate(eq(7L), any(ProblemDraftRequest.class), any(ProblemDraftProgressListener.class),
                any(ProblemDraftAuditContext.class)))
                .thenReturn(draft(900L));

        service.process(100L);

        ArgumentCaptor<ProblemDraftGenerationJobEntity> captor = ArgumentCaptor.forClass(ProblemDraftGenerationJobEntity.class);
        verify(jobMapper, atLeast(2)).update(captor.capture(), any(UpdateWrapper.class));
        ProblemDraftGenerationJobEntity completed = captor.getAllValues().stream()
                .filter(update -> "SUCCEEDED".equals(update.getStatus()))
                .findFirst()
                .orElseThrow();
        assertThat(completed.getProgressMessage()).isEqualTo(ReferenceCheckPolicy.HIGH_RATING_DISABLED_PROGRESS_MESSAGE);
    }

    @Test
    void processRegenerationJobCallsRewriteFlowAndWritesResultDraftId() {
        when(jobMapper.update(any(ProblemDraftGenerationJobEntity.class), any(UpdateWrapper.class))).thenReturn(1);
        ProblemDraftGenerationJobEntity job = job(103L, 7L, "QUEUED");
        job.setJobType("REGENERATE");
        job.setSourceDraftId(200L);
        job.setRequestJson("{\"feedback\":\"只重新跑验证\"}");
        when(jobMapper.selectById(103L)).thenReturn(job);
        when(problemDraftStore.regenerate(eq(200L), eq(7L), any(ProblemDraftRegenerateRequest.class),
                any(ProblemDraftProgressListener.class), any(ProblemDraftAuditContext.class)))
                .thenReturn(draft(902L));

        service.process(103L);

        ArgumentCaptor<ProblemDraftGenerationJobEntity> captor = ArgumentCaptor.forClass(ProblemDraftGenerationJobEntity.class);
        verify(jobMapper, atLeast(2)).update(captor.capture(), any(UpdateWrapper.class));
        ProblemDraftGenerationJobEntity completed = captor.getAllValues().stream()
                .filter(update -> "SUCCEEDED".equals(update.getStatus()))
                .findFirst()
                .orElseThrow();
        assertThat(completed.getDraftId()).isEqualTo(902L);
        assertThat(completed.getProgressMessage()).isEqualTo("Problem draft rewrite completed");
        verify(problemDraftStore).regenerate(eq(200L), eq(7L), any(ProblemDraftRegenerateRequest.class),
                any(ProblemDraftProgressListener.class), any(ProblemDraftAuditContext.class));
        verify(auditWriter).replaceProblemDraftGenerationJobLifecycle(eq(103L),
                eq("PROBLEM_DRAFT_REGENERATION_JOB_RUNNING"), eq("RUNNING"), any());
        verify(auditWriter).replaceProblemDraftGenerationJobLifecycle(eq(103L),
                eq("PROBLEM_DRAFT_REGENERATION_JOB_COMPLETED"), eq("COMPLETED"), any());
    }

    @Test
    void processProviderFailureMarksJobFailed() {
        when(jobMapper.update(any(ProblemDraftGenerationJobEntity.class), any(UpdateWrapper.class))).thenReturn(1);
        when(jobMapper.selectById(100L)).thenReturn(job(100L, 7L, "QUEUED"));
        when(problemDraftStore.generate(eq(7L), any(ProblemDraftRequest.class), any(ProblemDraftProgressListener.class),
                any(ProblemDraftAuditContext.class)))
                .thenThrow(new DomainException(ErrorCode.SERVICE_UNAVAILABLE, "provider timeout"));

        service.process(100L);

        ArgumentCaptor<ProblemDraftGenerationJobEntity> captor = ArgumentCaptor.forClass(ProblemDraftGenerationJobEntity.class);
        verify(jobMapper, atLeast(2)).update(captor.capture(), any(UpdateWrapper.class));
        ProblemDraftGenerationJobEntity failed = captor.getAllValues().stream()
                .filter(update -> "FAILED".equals(update.getStatus()))
                .findFirst()
                .orElseThrow();
        assertThat(failed.getErrorCode()).isEqualTo(ErrorCode.SERVICE_UNAVAILABLE.code());
        assertThat(failed.getErrorKey()).isEqualTo("service.unavailable");
        assertThat(failed.getErrorMessage()).isEqualTo("provider timeout");
        verify(auditWriter).replaceProblemDraftGenerationJobLifecycle(eq(100L),
                eq("PROBLEM_DRAFT_GENERATION_JOB_FAILED"), eq("FAILED"), any());
    }

    @Test
    void processPlanGateFailureMarksJobFailedWithReadableReason() {
        when(jobMapper.update(any(ProblemDraftGenerationJobEntity.class), any(UpdateWrapper.class))).thenReturn(1);
        when(jobMapper.selectById(100L)).thenReturn(job(100L, 7L, "QUEUED"));
        when(problemDraftStore.generate(eq(7L), any(ProblemDraftRequest.class), any(ProblemDraftProgressListener.class),
                any(ProblemDraftAuditContext.class)))
                .thenThrow(new DomainException(ErrorCode.VALIDATION_FAILED,
                        "Problem design plan gate failed: requested algorithm tokens are missing"));

        service.process(100L);

        ArgumentCaptor<ProblemDraftGenerationJobEntity> captor = ArgumentCaptor.forClass(ProblemDraftGenerationJobEntity.class);
        verify(jobMapper, atLeast(2)).update(captor.capture(), any(UpdateWrapper.class));
        ProblemDraftGenerationJobEntity failed = captor.getAllValues().stream()
                .filter(update -> "FAILED".equals(update.getStatus()))
                .findFirst()
                .orElseThrow();
        assertThat(failed.getErrorCode()).isEqualTo(ErrorCode.VALIDATION_FAILED.code());
        assertThat(failed.getErrorMessage()).contains("Problem design plan gate failed");
    }

    @Test
    void processCapacityBusyRequeuesJob() {
        when(jobMapper.update(any(ProblemDraftGenerationJobEntity.class), any(UpdateWrapper.class))).thenReturn(1);
        when(jobMapper.selectById(100L)).thenReturn(job(100L, 7L, "QUEUED"));
        when(problemDraftStore.generate(eq(7L), any(ProblemDraftRequest.class), any(ProblemDraftProgressListener.class),
                any(ProblemDraftAuditContext.class)))
                .thenThrow(new DomainException(ErrorCode.TOO_MANY_REQUESTS, "AI service is busy"));

        service.process(100L);

        ArgumentCaptor<ProblemDraftGenerationJobEntity> captor = ArgumentCaptor.forClass(ProblemDraftGenerationJobEntity.class);
        verify(jobMapper, atLeast(2)).update(captor.capture(), any(UpdateWrapper.class));
        ProblemDraftGenerationJobEntity queued = captor.getAllValues().stream()
                .filter(update -> "QUEUED".equals(update.getStatus()))
                .findFirst()
                .orElseThrow();
        assertThat(queued.getProgressMessage()).isEqualTo("Waiting for AI generation capacity");
        verify(auditWriter).replaceProblemDraftGenerationJobLifecycle(eq(100L),
                eq("PROBLEM_DRAFT_GENERATION_JOB_RUNNING"), eq("RUNNING"), any());
        verify(auditWriter).replaceProblemDraftGenerationJobLifecycle(eq(100L),
                eq("PROBLEM_DRAFT_GENERATION_JOB_CREATED"), eq("QUEUED"), any());
    }

    @Test
    void processDoesNotMarkAuditCompletedWhenTerminalStateTransitionLosesRace() {
        when(jobMapper.update(any(ProblemDraftGenerationJobEntity.class), any(UpdateWrapper.class))).thenReturn(1, 0);
        when(jobMapper.selectById(100L)).thenReturn(job(100L, 7L, "QUEUED"));
        when(problemDraftStore.generate(eq(7L), any(ProblemDraftRequest.class), any(ProblemDraftProgressListener.class),
                any(ProblemDraftAuditContext.class)))
                .thenReturn(draft(900L));

        service.process(100L);

        verify(auditWriter).replaceProblemDraftGenerationJobLifecycle(eq(100L),
                eq("PROBLEM_DRAFT_GENERATION_JOB_RUNNING"), eq("RUNNING"), any());
        verify(auditWriter, never()).replaceProblemDraftGenerationJobLifecycle(eq(100L),
                eq("PROBLEM_DRAFT_GENERATION_JOB_COMPLETED"), eq("COMPLETED"), any());
    }

    @Test
    void teacherCannotReadAnotherUsersJob() {
        authenticate(7L, Role.TEACHER);
        when(jobMapper.selectById(100L)).thenReturn(job(100L, 8L, "SUCCEEDED"));

        assertThatThrownBy(() -> service.get(100L))
                .isInstanceOf(DomainException.class)
                .extracting("errorCode")
                .isEqualTo(ErrorCode.NOT_FOUND);
    }

    @Test
    void adminCanReadAnyJob() {
        authenticate(99L, Role.ADMIN);
        when(jobMapper.selectById(100L)).thenReturn(job(100L, 8L, "SUCCEEDED"));

        var response = service.get(100L);

        assertThat(response.id()).isEqualTo(100L);
        assertThat(response.creatorUserId()).isEqualTo(8L);
    }

    private static ProblemDraftRequest request(String topic) {
        return new ProblemDraftRequest(
                topic,
                "HARD",
                2000,
                null,
                "线段树,排序",
                List.of("线段树", "排序"),
                null,
                null,
                null,
                null,
                "cpp",
                null,
                null,
                null,
                12,
                null,
                null,
                true,
                false
        );
    }

    private static ProblemDraftGenerationJobEntity job(Long id, Long creatorUserId, String status) {
        ProblemDraftGenerationJobEntity job = new ProblemDraftGenerationJobEntity();
        job.setId(id);
        job.setCreatorUserId(creatorUserId);
        job.setJobType("GENERATE");
        job.setStatus(status);
        job.setStage(status);
        job.setRequestJson("{\"topic\":\"线段树,排序\",\"difficulty\":\"HARD\",\"cfRating\":2000,\"standardSolutionLanguage\":\"cpp\"}");
        job.setTopicSnapshot("线段树,排序");
        job.setProgressCurrent(0);
        job.setProgressTotal(6);
        job.setCreatedAt(LocalDateTime.now());
        job.setUpdatedAt(LocalDateTime.now());
        return job;
    }

    private static ProblemDraftResponse draft(Long id) {
        return new ProblemDraftResponse(
                id,
                "PENDING_REVIEW",
                "Segment Tree Sorting",
                "HARD",
                "Solve it.",
                "notes",
                "cpp",
                "int main(){return 0;}",
                "print('ok')",
                "plan",
                List.of("线段树"),
                "VALID",
                List.of(),
                List.of(),
                1000,
                262144,
                null,
                "mock",
                1,
                2,
                Instant.now(),
                null,
                null,
                null,
                null,
                null
        );
    }

    private static void authenticate(Long userId, Role role) {
        SecurityPrincipal principal = new SecurityPrincipal(userId, "tester", Set.of(role));
        SecurityContextHolder.getContext().setAuthentication(new UsernamePasswordAuthenticationToken(
                principal,
                null,
                List.of(new SimpleGrantedAuthority("ROLE_" + role.name()))
        ));
    }

    private static PlatformTransactionManager transactionManager() {
        return new AbstractPlatformTransactionManager() {
            @Override
            protected Object doGetTransaction() {
                return new Object();
            }

            @Override
            protected void doBegin(Object transaction, TransactionDefinition definition) {
            }

            @Override
            protected void doCommit(DefaultTransactionStatus status) {
            }

            @Override
            protected void doRollback(DefaultTransactionStatus status) {
            }
        };
    }
}
