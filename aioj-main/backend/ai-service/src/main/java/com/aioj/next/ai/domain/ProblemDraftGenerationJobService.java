package com.aioj.next.ai.domain;

import com.aioj.next.ai.domain.problem.ProblemDraftAuditContext;
import com.aioj.next.ai.domain.problem.ProblemDraftProgressListener;
import com.aioj.next.ai.domain.problem.ReferenceCheckPolicy;
import com.aioj.next.ai.persistence.entity.ProblemDraftGenerationJobEntity;
import com.aioj.next.ai.persistence.mapper.ProblemDraftGenerationJobMapper;
import com.aioj.next.common.api.PageResponse;
import com.aioj.next.common.error.DomainException;
import com.aioj.next.common.error.ErrorCode;
import com.aioj.next.common.security.Role;
import com.aioj.next.common.security.SecuritySupport;
import com.aioj.next.contract.ai.ProblemDraftGenerationJobResponse;
import com.aioj.next.contract.ai.ProblemDraftRegenerateRequest;
import com.aioj.next.contract.ai.ProblemDraftRequest;
import com.aioj.next.contract.ai.ProblemDraftResponse;
import com.baomidou.mybatisplus.core.conditions.query.QueryWrapper;
import com.baomidou.mybatisplus.core.conditions.update.UpdateWrapper;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionTemplate;

import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.Executor;
import java.util.concurrent.RejectedExecutionException;

@Service
public class ProblemDraftGenerationJobService {
    private static final Logger log = LoggerFactory.getLogger(ProblemDraftGenerationJobService.class);
    private static final ZoneId ZONE = ZoneId.systemDefault();
    private static final int PROGRESS_TOTAL = 7;
    private static final int PAGE_SIZE_MAX = 100;
    private static final int WORKER_BATCH_SIZE = 8;
    private static final int ERROR_MESSAGE_MAX = 1000;

    private static final String STATUS_QUEUED = "QUEUED";
    private static final String STATUS_RUNNING = "RUNNING";
    private static final String STATUS_SUCCEEDED = "SUCCEEDED";
    private static final String STATUS_FAILED = "FAILED";
    private static final String STATUS_CANCELLED = "CANCELLED";

    private static final String STAGE_QUEUED = "QUEUED";
    private static final String STAGE_FAILED = "FAILED";
    private static final String STAGE_SUCCEEDED = "SUCCEEDED";
    private static final String JOB_TYPE_GENERATE = "GENERATE";
    private static final String JOB_TYPE_REGENERATE = "REGENERATE";
    private static final String AUDIT_RESOURCE_TYPE = "AI_PROBLEM_DRAFT_GENERATION_JOB";

    private final ProblemDraftGenerationJobMapper jobMapper;
    private final ProblemDraftStore problemDraftStore;
    private final ObjectMapper objectMapper;
    private final OperationAuditWriter auditWriter;
    private final Executor problemDraftExecutor;
    private final TransactionTemplate requiresNew;
    private final String workerId = "ai-draft-job-" + UUID.randomUUID();

    @Autowired
    public ProblemDraftGenerationJobService(
            ProblemDraftGenerationJobMapper jobMapper,
            ProblemDraftStore problemDraftStore,
            ObjectMapper objectMapper,
            OperationAuditWriter auditWriter,
            @Qualifier("aiProblemDraftExecutor") Executor problemDraftExecutor,
            PlatformTransactionManager transactionManager
    ) {
        this.jobMapper = jobMapper;
        this.problemDraftStore = problemDraftStore;
        this.objectMapper = objectMapper;
        this.auditWriter = auditWriter;
        this.problemDraftExecutor = problemDraftExecutor;
        this.requiresNew = new TransactionTemplate(transactionManager);
        this.requiresNew.setPropagationBehavior(TransactionDefinition.PROPAGATION_REQUIRES_NEW);
    }

    @Transactional
    public ProblemDraftGenerationJobResponse create(Long userId, ProblemDraftRequest request) {
        if (request == null) {
            throw new DomainException(ErrorCode.BAD_REQUEST, "Problem draft request is required");
        }
        LocalDateTime now = LocalDateTime.now();
        ProblemDraftGenerationJobEntity job = new ProblemDraftGenerationJobEntity();
        job.setCreatorUserId(userId);
        job.setJobType(JOB_TYPE_GENERATE);
        job.setStatus(STATUS_QUEUED);
        job.setStage(STAGE_QUEUED);
        job.setRequestJson(toJson(request));
        job.setTopicSnapshot(truncate(request.topic(), 255));
        job.setProgressCurrent(0);
        job.setProgressTotal(PROGRESS_TOTAL);
        job.setProgressMessage("Queued for AI problem draft generation");
        job.setCreatedAt(now);
        job.setUpdatedAt(now);
        jobMapper.insert(job);
        recordQueuedAudit(job, userId);
        return toResponse(job);
    }

    @Transactional
    public ProblemDraftGenerationJobResponse createRegeneration(Long userId, Long sourceDraftId,
                                                                ProblemDraftRegenerateRequest request) {
        if (sourceDraftId == null) {
            throw new DomainException(ErrorCode.BAD_REQUEST, "Source problem draft id is required");
        }
        if (request == null) {
            throw new DomainException(ErrorCode.BAD_REQUEST, "Problem draft rewrite request is required");
        }
        ProblemDraftResponse source = problemDraftStore.regenerationSource(sourceDraftId);
        LocalDateTime now = LocalDateTime.now();
        ProblemDraftGenerationJobEntity existing = latestRegenerationJob(sourceDraftId);
        if (existing != null) {
            resetRegenerationJob(existing, userId, source, request, now);
            return toResponse(existing);
        }
        ProblemDraftGenerationJobEntity job = new ProblemDraftGenerationJobEntity();
        job.setCreatorUserId(userId);
        job.setJobType(JOB_TYPE_REGENERATE);
        job.setSourceDraftId(sourceDraftId);
        job.setStatus(STATUS_QUEUED);
        job.setStage(STAGE_QUEUED);
        job.setRequestJson(toJson(request));
        job.setTopicSnapshot(truncate("Rewrite: " + source.title(), 255));
        job.setProgressCurrent(0);
        job.setProgressTotal(PROGRESS_TOTAL);
        job.setProgressMessage("Queued for AI problem draft rewrite");
        job.setCreatedAt(now);
        job.setUpdatedAt(now);
        jobMapper.insert(job);
        recordQueuedAudit(job, userId);
        return toResponse(job);
    }

    public PageResponse<ProblemDraftGenerationJobResponse> list(
            long page,
            long pageSize,
            String status,
            Long creatorUserId
    ) {
        boolean admin = SecuritySupport.hasRole(Role.ADMIN);
        long current = Math.max(1, page);
        long size = Math.min(Math.max(1, pageSize), PAGE_SIZE_MAX);
        long offset = (current - 1) * size;
        QueryWrapper<ProblemDraftGenerationJobEntity> countQuery = new QueryWrapper<>();
        applyListFilters(countQuery, admin, creatorUserId, status);
        long total = jobMapper.selectCount(countQuery);

        QueryWrapper<ProblemDraftGenerationJobEntity> pageQuery = new QueryWrapper<>();
        applyListFilters(pageQuery, admin, creatorUserId, status);
        pageQuery.orderByDesc("updated_at").orderByDesc("created_at");
        pageQuery.last("LIMIT " + size + " OFFSET " + offset);
        List<ProblemDraftGenerationJobResponse> records = jobMapper.selectList(pageQuery)
                .stream()
                .map(this::toResponse)
                .toList();
        return new PageResponse<>(records, total, current, size);
    }

    public ProblemDraftGenerationJobResponse get(Long id) {
        ProblemDraftGenerationJobEntity job = requireJob(id);
        boolean admin = SecuritySupport.hasRole(Role.ADMIN);
        Long currentUserId = SecuritySupport.currentUserId();
        if (!admin && !currentUserId.equals(job.getCreatorUserId())) {
            throw new DomainException(ErrorCode.NOT_FOUND, "Problem draft generation job not found");
        }
        return toResponse(job);
    }

    @Scheduled(fixedDelayString = "${aioj.ai.problem-draft.generation-job-poll-ms:2000}")
    public void pollQueuedJobs() {
        recoverExpiredRunningJobs();
        List<ProblemDraftGenerationJobEntity> queued = jobMapper.selectList(new QueryWrapper<ProblemDraftGenerationJobEntity>()
                .eq("status", STATUS_QUEUED)
                .orderByAsc("created_at")
                .last("LIMIT " + WORKER_BATCH_SIZE));
        for (ProblemDraftGenerationJobEntity job : queued) {
            if (!claim(job.getId())) {
                continue;
            }
            try {
                problemDraftExecutor.execute(() -> processClaimed(job.getId()));
            } catch (RejectedExecutionException ex) {
                requeue(job.getId(), "Waiting for AI generation capacity");
                break;
            }
        }
    }

    void process(Long jobId) {
        if (!claim(jobId)) {
            return;
        }
        processClaimed(jobId);
    }

    private void processClaimed(Long jobId) {
        ProblemDraftGenerationJobEntity job = jobMapper.selectById(jobId);
        if (job == null) {
            return;
        }
        try {
            ProblemDraftProgressListener listener = (stage, current, total, message) ->
                    updateProgress(jobId, stage, current, total, message);
            ProblemDraftResponse draft;
            if (JOB_TYPE_REGENERATE.equals(jobType(job))) {
                if (job.getSourceDraftId() == null) {
                    throw new DomainException(ErrorCode.BAD_REQUEST, "Source problem draft id is required");
                }
                draft = problemDraftStore.regenerate(
                        job.getSourceDraftId(),
                        job.getCreatorUserId(),
                        readRegenerateRequest(job.getRequestJson()),
                        listener,
                        new ProblemDraftAuditContext(jobId)
                );
            } else {
                draft = problemDraftStore.generate(
                        job.getCreatorUserId(),
                        readGenerateRequest(job.getRequestJson()),
                        listener,
                        new ProblemDraftAuditContext(jobId)
                );
            }
            complete(jobId, draft.id());
        } catch (DomainException ex) {
            if (ex.errorCode() == ErrorCode.TOO_MANY_REQUESTS) {
                requeue(jobId, "Waiting for AI generation capacity");
                return;
            }
            fail(jobId, ex.errorCode().code(), errorKey(ex.errorCode()), ex.getMessage());
        } catch (RuntimeException ex) {
            log.warn("Problem draft generation job {} failed: {}", jobId, ex.toString(), ex);
            fail(jobId, ErrorCode.INTERNAL_ERROR.code(), errorKey(ErrorCode.INTERNAL_ERROR), safeMessage(ex));
        }
    }

    private boolean claim(Long jobId) {
        return Boolean.TRUE.equals(requiresNew.execute(status -> {
            LocalDateTime now = LocalDateTime.now();
            ProblemDraftGenerationJobEntity update = new ProblemDraftGenerationJobEntity();
            update.setStatus(STATUS_RUNNING);
            update.setStage("GENERATING");
            update.setProgressCurrent(1);
            update.setProgressTotal(PROGRESS_TOTAL);
            update.setProgressMessage("Starting AI problem draft task");
            update.setLeaseOwner(workerId);
            update.setLeaseExpiresAt(now.plusMinutes(30));
            update.setStartedAt(now);
            update.setUpdatedAt(now);
            int changed = jobMapper.update(update, new UpdateWrapper<ProblemDraftGenerationJobEntity>()
                    .eq("id", jobId)
                    .eq("status", STATUS_QUEUED));
            if (changed != 1) {
                return false;
            }
            ProblemDraftGenerationJobEntity current = jobMapper.selectById(jobId);
            if (current != null) {
                replaceAudit(current, "RUNNING", STATUS_RUNNING,
                        auditSummary(current, "startedAt", now, null, null, null));
            }
            return true;
        }));
    }

    private void requeue(Long jobId, String message) {
        requiresNew.executeWithoutResult(status -> {
            LocalDateTime now = LocalDateTime.now();
            ProblemDraftGenerationJobEntity current = jobMapper.selectById(jobId);
            ProblemDraftGenerationJobEntity update = new ProblemDraftGenerationJobEntity();
            update.setStatus(STATUS_QUEUED);
            update.setStage(STAGE_QUEUED);
            update.setProgressCurrent(0);
            update.setProgressTotal(PROGRESS_TOTAL);
            update.setProgressMessage(truncate(message, 255));
            update.setUpdatedAt(now);
            UpdateWrapper<ProblemDraftGenerationJobEntity> wrapper = new UpdateWrapper<ProblemDraftGenerationJobEntity>()
                    .set("lease_owner", null)
                    .set("lease_expires_at", null)
                    .eq("id", jobId)
                    .eq("status", STATUS_RUNNING);
            if (jobMapper.update(update, wrapper) == 1 && current != null) {
                replaceAudit(current, "CREATED", STATUS_QUEUED, auditSummary(current, null, null, null, null, null));
            }
        });
    }

    private void updateProgress(Long jobId, String stage, int current, int total, String message) {
        requiresNew.executeWithoutResult(status -> {
            LocalDateTime now = LocalDateTime.now();
            ProblemDraftGenerationJobEntity update = new ProblemDraftGenerationJobEntity();
            update.setStage(nonBlank(stage, "RUNNING"));
            update.setProgressCurrent(Math.max(0, current));
            update.setProgressTotal(Math.max(1, total));
            update.setProgressMessage(truncate(message, 255));
            update.setLeaseOwner(workerId);
            update.setLeaseExpiresAt(now.plusMinutes(30));
            update.setUpdatedAt(now);
            jobMapper.update(update, new UpdateWrapper<ProblemDraftGenerationJobEntity>()
                    .eq("id", jobId)
                    .eq("status", STATUS_RUNNING));
        });
    }

    private void complete(Long jobId, Long draftId) {
        requiresNew.executeWithoutResult(status -> {
            LocalDateTime now = LocalDateTime.now();
            ProblemDraftGenerationJobEntity current = jobMapper.selectById(jobId);
            String progressMessage = "Problem draft generation completed";
            if (current != null && JOB_TYPE_REGENERATE.equals(jobType(current))) {
                progressMessage = "Problem draft rewrite completed";
            } else if (current != null && ReferenceCheckPolicy.highRatingExplicitlyDisabled(readGenerateRequest(current.getRequestJson()))) {
                progressMessage = ReferenceCheckPolicy.HIGH_RATING_DISABLED_PROGRESS_MESSAGE;
            }
            ProblemDraftGenerationJobEntity update = new ProblemDraftGenerationJobEntity();
            update.setStatus(STATUS_SUCCEEDED);
            update.setStage(STAGE_SUCCEEDED);
            update.setProgressCurrent(PROGRESS_TOTAL);
            update.setProgressTotal(PROGRESS_TOTAL);
            update.setProgressMessage(progressMessage);
            update.setDraftId(draftId);
            update.setCompletedAt(now);
            update.setUpdatedAt(now);
            UpdateWrapper<ProblemDraftGenerationJobEntity> wrapper = new UpdateWrapper<ProblemDraftGenerationJobEntity>()
                    .set("lease_owner", null)
                    .set("lease_expires_at", null)
                    .eq("id", jobId)
                    .eq("status", STATUS_RUNNING);
            if (jobMapper.update(update, wrapper) == 1 && current != null) {
                replaceAudit(current, "COMPLETED", "COMPLETED",
                        auditSummary(current, "completedAt", now, draftId, null, null));
            }
        });
    }

    private void fail(Long jobId, int errorCode, String errorKey, String errorMessage) {
        requiresNew.executeWithoutResult(status -> {
            LocalDateTime now = LocalDateTime.now();
            ProblemDraftGenerationJobEntity current = jobMapper.selectById(jobId);
            ProblemDraftGenerationJobEntity update = new ProblemDraftGenerationJobEntity();
            update.setStatus(STATUS_FAILED);
            update.setStage(STAGE_FAILED);
            update.setProgressMessage("Problem draft generation failed");
            update.setErrorCode(errorCode);
            update.setErrorKey(truncate(errorKey, 128));
            update.setErrorMessage(truncate(nonBlank(errorMessage, "Problem draft generation failed"), ERROR_MESSAGE_MAX));
            update.setCompletedAt(now);
            update.setUpdatedAt(now);
            UpdateWrapper<ProblemDraftGenerationJobEntity> wrapper = new UpdateWrapper<ProblemDraftGenerationJobEntity>()
                    .set("lease_owner", null)
                    .set("lease_expires_at", null)
                    .eq("id", jobId)
                    .in("status", List.of(STATUS_RUNNING, STATUS_QUEUED));
            if (jobMapper.update(update, wrapper) == 1 && current != null) {
                replaceAudit(current, "FAILED", STATUS_FAILED,
                        auditSummary(current, "completedAt", now, null, errorCode, truncate(errorKey, 128)));
            }
        });
    }

    private void recoverExpiredRunningJobs() {
        LocalDateTime now = LocalDateTime.now();
        List<ProblemDraftGenerationJobEntity> expired = jobMapper.selectList(new QueryWrapper<ProblemDraftGenerationJobEntity>()
                .eq("status", STATUS_RUNNING)
                .le("lease_expires_at", now)
                .orderByAsc("lease_expires_at")
                .last("LIMIT " + WORKER_BATCH_SIZE));
        for (ProblemDraftGenerationJobEntity job : expired) {
            fail(job.getId(), ErrorCode.INTERNAL_ERROR.code(), errorKey(ErrorCode.INTERNAL_ERROR),
                    "Problem draft generation worker stopped before completion; please submit a new task");
        }
    }

    private void applyListFilters(QueryWrapper<ProblemDraftGenerationJobEntity> query, boolean admin, Long creatorUserId, String status) {
        if (status != null && !status.isBlank()) {
            String normalized = status.trim().toUpperCase();
            if (!isKnownStatus(normalized)) {
                throw new DomainException(ErrorCode.BAD_REQUEST, "Unsupported generation job status");
            }
            query.eq("status", normalized);
        }
        if (admin) {
            if (creatorUserId != null) {
                query.eq("creator_user_id", creatorUserId);
            }
        } else {
            query.eq("creator_user_id", SecuritySupport.currentUserId());
        }
    }

    private ProblemDraftGenerationJobEntity latestRegenerationJob(Long sourceDraftId) {
        return jobMapper.selectOne(new QueryWrapper<ProblemDraftGenerationJobEntity>()
                .eq("job_type", JOB_TYPE_REGENERATE)
                .eq("source_draft_id", sourceDraftId)
                .orderByDesc("updated_at")
                .orderByDesc("created_at")
                .last("LIMIT 1"));
    }

    private void resetRegenerationJob(ProblemDraftGenerationJobEntity job, Long userId, ProblemDraftResponse source,
                                      ProblemDraftRegenerateRequest request, LocalDateTime now) {
        job.setCreatorUserId(userId);
        job.setJobType(JOB_TYPE_REGENERATE);
        job.setSourceDraftId(source.id());
        job.setStatus(STATUS_QUEUED);
        job.setStage(STAGE_QUEUED);
        job.setRequestJson(toJson(request));
        job.setTopicSnapshot(truncate("Rewrite: " + source.title(), 255));
        job.setProgressCurrent(0);
        job.setProgressTotal(PROGRESS_TOTAL);
        job.setProgressMessage("Queued for AI problem draft rewrite");
        job.setDraftId(null);
        job.setErrorCode(null);
        job.setErrorKey(null);
        job.setErrorMessage(null);
        job.setLeaseOwner(null);
        job.setLeaseExpiresAt(null);
        job.setStartedAt(null);
        job.setCompletedAt(null);
        job.setUpdatedAt(now);
        ProblemDraftGenerationJobEntity update = new ProblemDraftGenerationJobEntity();
        update.setCreatorUserId(job.getCreatorUserId());
        update.setJobType(job.getJobType());
        update.setSourceDraftId(job.getSourceDraftId());
        update.setStatus(job.getStatus());
        update.setStage(job.getStage());
        update.setRequestJson(job.getRequestJson());
        update.setTopicSnapshot(job.getTopicSnapshot());
        update.setProgressCurrent(job.getProgressCurrent());
        update.setProgressTotal(job.getProgressTotal());
        update.setProgressMessage(job.getProgressMessage());
        update.setUpdatedAt(now);
        if (jobMapper.update(update, new UpdateWrapper<ProblemDraftGenerationJobEntity>()
                .set("draft_id", null)
                .set("error_code", null)
                .set("error_key", null)
                .set("error_message", null)
                .set("lease_owner", null)
                .set("lease_expires_at", null)
                .set("started_at", null)
                .set("completed_at", null)
                .eq("id", job.getId())) == 1) {
            replaceAudit(job, "CREATED", STATUS_QUEUED, auditSummary(job, null, null, null, null, null));
        }
    }

    private void recordQueuedAudit(ProblemDraftGenerationJobEntity job, Long actorUserId) {
        auditWriter.record(
                auditAction(job, "CREATED"),
                AUDIT_RESOURCE_TYPE,
                job.getId(),
                STATUS_QUEUED,
                auditSummary(job, null, null, null, null, null),
                actorUserId,
                null,
                null,
                null
        );
    }

    private void replaceAudit(ProblemDraftGenerationJobEntity job, String actionState, String status,
                              Map<String, Object> summary) {
        auditWriter.replaceProblemDraftGenerationJobLifecycle(job.getId(), auditAction(job, actionState), status, summary);
    }

    private String auditAction(ProblemDraftGenerationJobEntity job, String state) {
        String prefix = JOB_TYPE_REGENERATE.equals(jobType(job))
                ? "PROBLEM_DRAFT_REGENERATION_JOB"
                : "PROBLEM_DRAFT_GENERATION_JOB";
        return prefix + "_" + state;
    }

    private Map<String, Object> auditSummary(ProblemDraftGenerationJobEntity job, String timestampKey,
                                             LocalDateTime timestamp, Long draftId, Integer errorCode,
                                             String errorKey) {
        Map<String, Object> summary = new LinkedHashMap<>();
        summary.put("jobId", job.getId());
        if (job.getSourceDraftId() != null) {
            summary.put("sourceDraftId", job.getSourceDraftId());
        }
        if (draftId != null) {
            summary.put("draftId", draftId);
        }
        if (timestampKey != null && timestamp != null) {
            summary.put(timestampKey, toInstant(timestamp).toString());
        }
        if (errorCode != null) {
            summary.put("errorCode", errorCode);
        }
        if (errorKey != null && !errorKey.isBlank()) {
            summary.put("errorKey", errorKey);
        }
        return summary;
    }

    private ProblemDraftGenerationJobEntity requireJob(Long id) {
        ProblemDraftGenerationJobEntity job = jobMapper.selectById(id);
        if (job == null) {
            throw new DomainException(ErrorCode.NOT_FOUND, "Problem draft generation job not found");
        }
        return job;
    }

    private String toJson(Object request) {
        try {
            return objectMapper.writeValueAsString(request);
        } catch (JsonProcessingException ex) {
            throw new DomainException(ErrorCode.BAD_REQUEST, "Problem draft request could not be serialized");
        }
    }

    private ProblemDraftRequest readGenerateRequest(String json) {
        try {
            return objectMapper.readValue(json, ProblemDraftRequest.class);
        } catch (JsonProcessingException ex) {
            throw new DomainException(ErrorCode.INTERNAL_ERROR, "Problem draft generation job payload is invalid");
        }
    }

    private ProblemDraftRegenerateRequest readRegenerateRequest(String json) {
        try {
            return objectMapper.readValue(json, ProblemDraftRegenerateRequest.class);
        } catch (JsonProcessingException ex) {
            throw new DomainException(ErrorCode.INTERNAL_ERROR, "Problem draft rewrite job payload is invalid");
        }
    }

    private ProblemDraftGenerationJobResponse toResponse(ProblemDraftGenerationJobEntity job) {
        return new ProblemDraftGenerationJobResponse(
                job.getId(),
                job.getCreatorUserId(),
                jobType(job),
                job.getSourceDraftId(),
                job.getStatus(),
                job.getStage(),
                job.getTopicSnapshot(),
                valueOrDefault(job.getProgressCurrent(), 0),
                valueOrDefault(job.getProgressTotal(), PROGRESS_TOTAL),
                job.getProgressMessage(),
                job.getDraftId(),
                job.getErrorCode(),
                job.getErrorKey(),
                job.getErrorMessage(),
                toInstant(job.getStartedAt()),
                toInstant(job.getCompletedAt()),
                toInstant(job.getCreatedAt()),
                toInstant(job.getUpdatedAt())
        );
    }

    private String jobType(ProblemDraftGenerationJobEntity job) {
        return job == null || job.getJobType() == null || job.getJobType().isBlank()
                ? JOB_TYPE_GENERATE
                : job.getJobType().trim().toUpperCase();
    }

    private Instant toInstant(LocalDateTime time) {
        return time == null ? null : time.atZone(ZONE).toInstant();
    }

    private boolean isKnownStatus(String status) {
        return STATUS_QUEUED.equals(status)
                || STATUS_RUNNING.equals(status)
                || STATUS_SUCCEEDED.equals(status)
                || STATUS_FAILED.equals(status)
                || STATUS_CANCELLED.equals(status);
    }

    private String errorKey(ErrorCode errorCode) {
        return switch (errorCode) {
            case BAD_REQUEST, VALIDATION_FAILED, INVALID_PAYLOAD, MISSING_PARAMETER, TYPE_MISMATCH -> "request.invalid";
            case UNAUTHORIZED -> "auth.unauthorized";
            case FORBIDDEN -> "auth.forbidden";
            case NOT_FOUND -> "resource.notFound";
            case TOO_MANY_REQUESTS -> "ai.busy";
            case SERVICE_UNAVAILABLE -> "service.unavailable";
            default -> "system.internal";
        };
    }

    private String safeMessage(RuntimeException ex) {
        String message = ex.getMessage();
        if (message == null || message.isBlank()) {
            return "Problem draft generation failed";
        }
        return message.replaceAll("(?i)(api[-_ ]?key|authorization|token|password)=?\\S+", "$1=***");
    }

    private String nonBlank(String value, String fallback) {
        return value == null || value.isBlank() ? fallback : value;
    }

    private int valueOrDefault(Integer value, int fallback) {
        return value == null ? fallback : value;
    }

    private String truncate(String value, int maxLength) {
        if (value == null) {
            return null;
        }
        String trimmed = value.trim();
        if (trimmed.length() <= maxLength) {
            return trimmed;
        }
        return trimmed.substring(0, maxLength);
    }
}
