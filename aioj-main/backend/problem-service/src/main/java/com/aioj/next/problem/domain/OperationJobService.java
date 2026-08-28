package com.aioj.next.problem.domain;

import com.aioj.next.common.api.PageResponse;
import com.aioj.next.common.error.DomainException;
import com.aioj.next.common.error.ErrorCode;
import com.aioj.next.common.security.Role;
import com.aioj.next.common.security.SecurityPrincipal;
import com.aioj.next.common.security.SecuritySupport;
import com.aioj.next.contract.contest.ContestExportFormat;
import com.aioj.next.contract.contest.ContestExportResponse;
import com.aioj.next.contract.contest.ContestPostmortemReportResponse;
import com.aioj.next.contract.contest.ContestScoreboardView;
import com.aioj.next.contract.contest.ContestStudentPostmortemReportResponse;
import com.aioj.next.contract.contest.ContestStudentPostmortemOperationJobResponse;
import com.aioj.next.contract.contest.PlagiarismJobCreateRequest;
import com.aioj.next.contract.contest.PlagiarismJobResponse;
import com.aioj.next.contract.operation.OperationJobArtifactResponse;
import com.aioj.next.contract.operation.OperationJobResponse;
import com.aioj.next.contract.operation.OperationJobStatus;
import com.aioj.next.contract.operation.OperationJobType;
import com.aioj.next.contract.notification.UserNotificationType;
import com.aioj.next.contract.submission.SubmissionStatus;
import com.aioj.next.problem.config.OperationProperties;
import com.aioj.next.problem.domain.notification.UserNotificationService;
import com.aioj.next.problem.persistence.entity.OperationJobArtifactEntity;
import com.aioj.next.problem.persistence.entity.OperationJobEntity;
import com.aioj.next.problem.persistence.mapper.OperationJobArtifactMapper;
import com.aioj.next.problem.persistence.mapper.OperationJobMapper;
import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.annotation.PostConstruct;
import jakarta.annotation.PreDestroy;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.context.annotation.Lazy;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.io.IOException;
import java.net.InetAddress;
import java.net.UnknownHostException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Instant;
import java.util.Base64;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.function.Supplier;
import java.util.stream.Collectors;

@Service
public class OperationJobService {
    private static final String RESOURCE_CONTEST = "CONTEST";
    private static final String RESOURCE_CONTEST_RUN = "CONTEST_RUN";
    private static final String RESOURCE_PLAGIARISM_JOB = "PLAGIARISM_JOB";

    private final OperationJobMapper jobMapper;
    private final OperationJobArtifactMapper artifactMapper;
    private final ContestExportService contestExportService;
    private final PlagiarismService plagiarismService;
    private final ContestScoreboardService contestScoreboardService;
    private final ContestPostmortemService contestPostmortemService;
    private final StudentPostmortemService studentPostmortemService;
    private final OperationAuditService auditService;
    private final UserNotificationService notificationService;
    private final OperationProperties properties;
    private final ObjectMapper objectMapper;
    private final ExecutorService executor;
    private final String workerId = workerId();

    public OperationJobService(OperationJobMapper jobMapper,
                               OperationJobArtifactMapper artifactMapper,
                               ContestExportService contestExportService,
                               PlagiarismService plagiarismService,
                               @Lazy ContestScoreboardService contestScoreboardService,
                               ContestPostmortemService contestPostmortemService,
                               StudentPostmortemService studentPostmortemService,
                               OperationAuditService auditService,
                               UserNotificationService notificationService,
                               OperationProperties properties,
                               ObjectMapper objectMapper) {
        this.jobMapper = jobMapper;
        this.artifactMapper = artifactMapper;
        this.contestExportService = contestExportService;
        this.plagiarismService = plagiarismService;
        this.contestScoreboardService = contestScoreboardService;
        this.contestPostmortemService = contestPostmortemService;
        this.studentPostmortemService = studentPostmortemService;
        this.auditService = auditService;
        this.notificationService = notificationService;
        this.properties = properties;
        this.objectMapper = objectMapper;
        this.executor = Executors.newFixedThreadPool(Math.max(1, properties.getExecutorPoolSize()));
    }

    @PostConstruct
    public void recoverExpiredRunningJobs() {
        if (!properties.isWorkerEnabled()) {
            return;
        }
        Instant now = Instant.now();
        List<OperationJobEntity> stale = jobMapper.selectList(new LambdaQueryWrapper<OperationJobEntity>()
                .eq(OperationJobEntity::getStatus, OperationJobStatus.RUNNING)
                .lt(OperationJobEntity::getLeaseExpiresAt, now));
        for (OperationJobEntity job : stale) {
            job.setStatus(OperationJobStatus.QUEUED);
            job.setLeaseOwner(null);
            job.setLeaseExpiresAt(null);
            job.setUpdatedAt(now);
            jobMapper.updateById(job);
        }
    }

    @PreDestroy
    public void shutdown() {
        executor.shutdownNow();
    }

    public OperationJobResponse createScoreboardExportJob(Long contestId, ContestExportFormat format, Long runId,
                                                          ContestScoreboardView view, Long atMillis, Long snapshotId) {
        Map<String, Object> payload = basePayload();
        payload.put("format", value(format == null ? ContestExportFormat.CSV : format));
        payload.put("runId", runId);
        payload.put("view", value(view));
        payload.put("atMillis", atMillis);
        payload.put("snapshotId", snapshotId);
        OperationJobEntity job = createJob(OperationJobType.EXPORT_SCOREBOARD, RESOURCE_CONTEST, contestId,
                contestId, runId, payload, true);
        auditService.recordCurrentUser("EXPORT_SCOREBOARD_QUEUED", RESOURCE_CONTEST, contestId, contestId, runId,
                null, "QUEUED", Map.of("jobId", job.getId()));
        submit(job.getId());
        return toResponse(job);
    }

    public OperationJobResponse createSubmissionsExportJob(Long contestId, ContestExportFormat format, Long runId,
                                                           Long contestProblemId, Long participantId, Long userId,
                                                           SubmissionStatus status, String language) {
        Map<String, Object> payload = basePayload();
        payload.put("format", value(format == null ? ContestExportFormat.CSV : format));
        payload.put("runId", runId);
        payload.put("contestProblemId", contestProblemId);
        payload.put("participantId", participantId);
        payload.put("userId", userId);
        payload.put("status", value(status));
        payload.put("language", language);
        OperationJobEntity job = createJob(OperationJobType.EXPORT_SUBMISSIONS, RESOURCE_CONTEST, contestId,
                contestId, runId, payload, true);
        auditService.recordCurrentUser("EXPORT_SUBMISSIONS_QUEUED", RESOURCE_CONTEST, contestId, contestId, runId,
                userId, "QUEUED", Map.of("jobId", job.getId()));
        submit(job.getId());
        return toResponse(job);
    }

    public OperationJobResponse createPlagiarismExportJob(Long contestId, Long plagiarismJobId, ContestExportFormat format) {
        Map<String, Object> payload = basePayload();
        payload.put("format", value(format == null ? ContestExportFormat.CSV : format));
        payload.put("plagiarismJobId", plagiarismJobId);
        OperationJobEntity job = createJob(OperationJobType.EXPORT_PLAGIARISM_REPORT, RESOURCE_PLAGIARISM_JOB,
                plagiarismJobId, contestId, null, payload, true);
        auditService.recordCurrentUser("EXPORT_PLAGIARISM_REPORT_QUEUED", RESOURCE_PLAGIARISM_JOB, plagiarismJobId,
                contestId, null, null, "QUEUED", Map.of("jobId", job.getId()));
        submit(job.getId());
        return toResponse(job);
    }

    public OperationJobResponse createPlagiarismCheckJob(Long contestId, Long runId, PlagiarismJobCreateRequest request) {
        PlagiarismJobCreateRequest normalized = request == null
                ? new PlagiarismJobCreateRequest(null, null, null, null)
                : request;
        Map<String, Object> payload = basePayload();
        payload.put("contestProblemIds", normalized.contestProblemIds());
        payload.put("languages", normalized.languages());
        payload.put("minimumSimilarity", normalized.minimumSimilarity());
        payload.put("includeAiAnalysis", normalized.includeAiAnalysis());
        OperationJobEntity job = createJob(OperationJobType.RUN_PLAGIARISM_CHECK, RESOURCE_CONTEST_RUN, runId,
                contestId, runId, payload, true);
        auditService.recordCurrentUser("RUN_PLAGIARISM_CHECK_QUEUED", RESOURCE_CONTEST_RUN, runId, contestId, runId,
                null, "QUEUED", Map.of("jobId", job.getId()));
        submit(job.getId());
        return toResponse(job);
    }

    public OperationJobResponse createContestPostmortemJob(Long contestId, Long runId) {
        OperationJobEntity job = createJob(OperationJobType.GENERATE_CONTEST_POSTMORTEM, RESOURCE_CONTEST_RUN, runId,
                contestId, runId, basePayload(), true);
        auditService.recordCurrentUser("GENERATE_CONTEST_POSTMORTEM_QUEUED", RESOURCE_CONTEST_RUN, runId, contestId,
                runId, null, "QUEUED", Map.of("jobId", job.getId()));
        submit(job.getId());
        return toResponse(job);
    }

    public OperationJobResponse createStudentPostmortemJob(Long contestId, Long runId, Long participantId) {
        Map<String, Object> payload = basePayload();
        payload.put("participantId", participantId);
        OperationJobEntity job = createJob(OperationJobType.GENERATE_STUDENT_POSTMORTEM, RESOURCE_CONTEST_RUN, runId,
                contestId, runId, payload, participantId != null);
        Map<String, Object> summary = new HashMap<>();
        summary.put("jobId", job.getId());
        if (participantId != null) {
            summary.put("participantId", participantId);
        }
        auditService.recordCurrentUser("GENERATE_STUDENT_POSTMORTEM_QUEUED", RESOURCE_CONTEST_RUN, runId, contestId,
                runId, null, "QUEUED", summary);
        submit(job.getId());
        return toResponse(job);
    }

    /**
     * Returns only the current user's active self-service postmortem task. The
     * full operation-job response is staff-only because it may contain failure
     * details and artifact metadata.
     */
    public ContestStudentPostmortemOperationJobResponse findMyActiveStudentPostmortemJob(Long contestId, Long runId) {
        Long userId = SecuritySupport.currentUserId();
        List<OperationJobEntity> candidates = jobMapper.selectList(new LambdaQueryWrapper<OperationJobEntity>()
                .eq(OperationJobEntity::getJobType, OperationJobType.GENERATE_STUDENT_POSTMORTEM)
                .eq(OperationJobEntity::getContestId, contestId)
                .eq(OperationJobEntity::getContestRunId, runId)
                .eq(OperationJobEntity::getRequestedBy, userId)
                .in(OperationJobEntity::getStatus, OperationJobStatus.QUEUED, OperationJobStatus.RUNNING)
                .orderByDesc(OperationJobEntity::getCreatedAt)
                .orderByDesc(OperationJobEntity::getId));
        for (OperationJobEntity candidate : candidates) {
            if (longValue(readJson(candidate.getRequestJson()).get("participantId")) == null) {
                return toStudentPostmortemJobResponse(candidate);
            }
        }
        return null;
    }

    public OperationJobResponse createBatchStudentPostmortemJob(Long contestId, Long runId, List<Long> participantIds) {
        Map<String, Object> payload = basePayload();
        payload.put("participantIds", participantIds);
        OperationJobEntity job = createJob(OperationJobType.BATCH_GENERATE_STUDENT_POSTMORTEMS, RESOURCE_CONTEST_RUN,
                runId, contestId, runId, payload, true);
        auditService.recordCurrentUser("BATCH_GENERATE_STUDENT_POSTMORTEMS_QUEUED", RESOURCE_CONTEST_RUN, runId,
                contestId, runId, null, "QUEUED", Map.of("jobId", job.getId()));
        submit(job.getId());
        return toResponse(job);
    }

    public synchronized OperationJobResponse findOrCreateScoreboardTimelineJob(Long contestId, Long runId,
                                                                               ContestScoreboardView view) {
        ContestScoreboardView effectiveView = view == null ? ContestScoreboardView.PUBLIC : view;
        OperationJobEntity existing = findActiveScoreboardTimelineJob(contestId, runId, effectiveView);
        if (existing != null) {
            return toResponse(existing);
        }
        Map<String, Object> payload = basePayload();
        payload.put("view", effectiveView.name());
        OperationJobEntity job = createJob(OperationJobType.GENERATE_SCOREBOARD_TIMELINE, RESOURCE_CONTEST_RUN, runId,
                contestId, runId, payload, false);
        auditService.recordCurrentUser("GENERATE_SCOREBOARD_TIMELINE_QUEUED", RESOURCE_CONTEST_RUN, runId,
                contestId, runId, null, "QUEUED", Map.of("jobId", job.getId(), "view", effectiveView.name()));
        submit(job.getId());
        return toResponse(job);
    }

    public PageResponse<OperationJobResponse> list(long page, long pageSize, OperationJobStatus status, OperationJobType type) {
        requireStaff();
        LambdaQueryWrapper<OperationJobEntity> query = new LambdaQueryWrapper<OperationJobEntity>()
                .eq(status != null, OperationJobEntity::getStatus, status)
                .eq(type != null, OperationJobEntity::getJobType, type)
                .eq(!SecuritySupport.hasRole(Role.ADMIN), OperationJobEntity::getRequestedBy, SecuritySupport.currentUserId())
                .orderByDesc(OperationJobEntity::getCreatedAt)
                .orderByDesc(OperationJobEntity::getId);
        Page<OperationJobEntity> result = jobMapper.selectPage(new Page<>(normalizePage(page), normalizePageSize(pageSize)), query);
        return new PageResponse<>(result.getRecords().stream().map(this::toResponse).toList(),
                result.getTotal(), result.getCurrent(), result.getSize());
    }

    public OperationJobResponse get(Long jobId) {
        requireStaff();
        OperationJobEntity job = requireVisibleJob(jobId);
        return toResponse(job);
    }

    public OperationJobResponse getContestJob(Long contestId, Long jobId) {
        OperationJobEntity job = requireVisibleJob(jobId);
        if (!contestId.equals(job.getContestId())) {
            throw new DomainException(ErrorCode.NOT_FOUND, "Operation job not found");
        }
        return toResponse(job);
    }

    public OperationJobResponse retry(Long jobId) {
        requireStaff();
        OperationJobEntity job = requireVisibleJob(jobId);
        if (job.getStatus() != OperationJobStatus.FAILED && job.getStatus() != OperationJobStatus.CANCELLED) {
            throw new DomainException(ErrorCode.BAD_REQUEST, "Only failed or cancelled jobs can be retried");
        }
        job.setStatus(OperationJobStatus.QUEUED);
        job.setErrorMessage(null);
        job.setLeaseOwner(null);
        job.setLeaseExpiresAt(null);
        job.setCompletedAt(null);
        job.setUpdatedAt(Instant.now());
        jobMapper.updateById(job);
        auditService.recordCurrentUser("OPERATION_JOB_RETRY", "OPERATION_JOB", job.getId(), job.getContestId(),
                job.getContestRunId(), null, "QUEUED", Map.of("jobType", job.getJobType().name()));
        submit(job.getId());
        return toResponse(job);
    }

    public ContestExportResponse artifact(Long jobId) {
        requireStaff();
        OperationJobEntity job = requireVisibleJob(jobId);
        if (job.getStatus() != OperationJobStatus.COMPLETED) {
            throw new DomainException(ErrorCode.BAD_REQUEST, "Operation job has no completed artifact");
        }
        OperationJobArtifactEntity artifact = artifactForJob(job.getId());
        if (artifact == null) {
            throw new DomainException(ErrorCode.NOT_FOUND, "Operation job artifact not found");
        }
        try {
            Path root = properties.getArtifactRoot().toAbsolutePath().normalize();
            Path file = root.resolve(artifact.getStorageKey()).normalize();
            if (!file.startsWith(root)) {
                throw new DomainException(ErrorCode.FORBIDDEN, "Invalid artifact path");
            }
            byte[] content = Files.readAllBytes(file);
            auditService.recordCurrentUser("OPERATION_ARTIFACT_DOWNLOAD", "OPERATION_JOB", job.getId(),
                    job.getContestId(), job.getContestRunId(), null, "COMPLETED",
                    Map.of("fileName", artifact.getFileName(), "byteSize", artifact.getByteSize()));
            return new ContestExportResponse(artifact.getFileName(), artifact.getContentType(),
                    Base64.getEncoder().encodeToString(content), content.length);
        } catch (IOException ex) {
            throw new DomainException(ErrorCode.NOT_FOUND, "Operation job artifact not found");
        }
    }

    @Scheduled(fixedDelayString = "${aioj.operations.poll-millis:5000}")
    public void pollQueuedJobs() {
        if (!properties.isWorkerEnabled()) {
            return;
        }
        recoverExpiredRunningJobs();
        List<OperationJobEntity> queued = jobMapper.selectList(new LambdaQueryWrapper<OperationJobEntity>()
                .eq(OperationJobEntity::getStatus, OperationJobStatus.QUEUED)
                .orderByAsc(OperationJobEntity::getCreatedAt)
                .last("LIMIT " + Math.max(1, properties.getPollBatchSize())));
        for (OperationJobEntity job : queued) {
            submit(job.getId());
        }
    }

    private OperationJobEntity createJob(OperationJobType type, String resourceType, Long resourceId,
                                         Long contestId, Long contestRunId, Map<String, Object> payload,
                                         boolean staffOnly) {
        if (staffOnly) {
            requireStaff();
        }
        Instant now = Instant.now();
        OperationJobEntity job = new OperationJobEntity();
        job.setJobType(type);
        job.setStatus(OperationJobStatus.QUEUED);
        job.setResourceType(resourceType);
        job.setResourceId(resourceId);
        job.setContestId(contestId);
        job.setContestRunId(contestRunId);
        job.setRequestedBy(SecuritySupport.currentUserId());
        job.setRequestJson(toJson(payload));
        job.setAttemptCount(0);
        job.setMaxAttempts(3);
        job.setProgressCurrent(0);
        job.setProgressTotal(1);
        job.setProgressMessage("Queued");
        job.setCreatedAt(now);
        job.setUpdatedAt(now);
        jobMapper.insert(job);
        return job;
    }

    private void submit(Long jobId) {
        if (!properties.isWorkerEnabled()) {
            return;
        }
        executor.submit(() -> process(jobId));
    }

    @Transactional
    public void process(Long jobId) {
        OperationJobEntity job = jobMapper.selectById(jobId);
        if (job == null || job.getStatus() != OperationJobStatus.QUEUED) {
            return;
        }
        Map<String, Object> payload = readJson(job.getRequestJson());
        Instant now = Instant.now();
        job.setStatus(OperationJobStatus.RUNNING);
        job.setLeaseOwner(workerId);
        job.setLeaseExpiresAt(now.plus(properties.getLeaseDuration()));
        job.setStartedAt(now);
        job.setAttemptCount((job.getAttemptCount() == null ? 0 : job.getAttemptCount()) + 1);
        job.setProgressCurrent(0);
        job.setProgressTotal(job.getProgressTotal() == null || job.getProgressTotal() <= 0 ? 1 : job.getProgressTotal());
        job.setProgressMessage("Running");
        job.setUpdatedAt(now);
        jobMapper.updateById(job);
        try {
            JobExecutionResult result = withJobSecurity(job, payload, () -> executeJob(job, payload));
            if (result.artifact() != null) {
                persistArtifact(job, result.artifact());
            }
            Instant done = Instant.now();
            job.setStatus(OperationJobStatus.COMPLETED);
            Map<String, Object> resultJson = new HashMap<>(result.result() == null ? Map.of() : result.result());
            resultJson.put("completedAt", done.toString());
            job.setResultJson(toJson(resultJson));
            job.setErrorMessage(null);
            job.setProgressCurrent(job.getProgressTotal() == null || job.getProgressTotal() <= 0 ? 1 : job.getProgressTotal());
            job.setProgressMessage("Completed");
            job.setLeaseOwner(null);
            job.setLeaseExpiresAt(null);
            job.setCompletedAt(done);
            job.setUpdatedAt(done);
            jobMapper.updateById(job);
            auditService.record(job.getRequestedBy(), action(job.getJobType()) + "_COMPLETED", job.getResourceType(),
                    job.getResourceId(), job.getContestId(), job.getContestRunId(), null, "COMPLETED",
                    Map.of("jobId", job.getId()));
            notifyStudentPostmortemTerminal(job, payload, UserNotificationType.STUDENT_POSTMORTEM_JOB_COMPLETED);
        } catch (Exception ex) {
            Instant failedAt = Instant.now();
            String errorMessage = ex.getMessage() == null ? ex.getClass().getSimpleName() : ex.getMessage();
            job.setStatus(OperationJobStatus.FAILED);
            job.setErrorMessage(trim(errorMessage, 1000));
            job.setProgressMessage("Failed");
            job.setLeaseOwner(null);
            job.setLeaseExpiresAt(null);
            job.setCompletedAt(failedAt);
            job.setUpdatedAt(failedAt);
            jobMapper.updateById(job);
            auditService.record(job.getRequestedBy(), action(job.getJobType()) + "_FAILED", job.getResourceType(),
                    job.getResourceId(), job.getContestId(), job.getContestRunId(), null, "FAILED",
                    Map.of("jobId", job.getId(), "error", trim(errorMessage, 300)));
            notifyStudentPostmortemTerminal(job, payload, UserNotificationType.STUDENT_POSTMORTEM_JOB_FAILED);
        }
    }

    private void notifyStudentPostmortemTerminal(OperationJobEntity job, Map<String, Object> payload,
                                                 UserNotificationType type) {
        if (job.getRequestedBy() == null
                || job.getJobType() != OperationJobType.GENERATE_STUDENT_POSTMORTEM
                || longValue(payload.get("participantId")) != null
                || !isStudentOnly(payload)) {
            return;
        }
        notificationService.createStudentPostmortemTerminal(job.getRequestedBy(), job, type);
    }

    private boolean isStudentOnly(Map<String, Object> payload) {
        Object rawRoles = payload.get("actorRoles");
        if (!(rawRoles instanceof List<?> roles)) {
            return false;
        }
        boolean student = false;
        for (Object role : roles) {
            String name = String.valueOf(role);
            if (Role.TEACHER.name().equals(name) || Role.ADMIN.name().equals(name)) {
                return false;
            }
            if (Role.STUDENT.name().equals(name)) {
                student = true;
            }
        }
        return student;
    }

    private JobExecutionResult executeJob(OperationJobEntity job, Map<String, Object> payload) {
        return switch (job.getJobType()) {
            case EXPORT_SCOREBOARD, EXPORT_SUBMISSIONS, EXPORT_PLAGIARISM_REPORT ->
                    new JobExecutionResult(executeExport(job, payload), Map.<String, Object>of("artifact", true));
            case RUN_PLAGIARISM_CHECK -> executePlagiarismCheck(job, payload);
            case GENERATE_SCOREBOARD_TIMELINE -> executeScoreboardTimeline(job, payload);
            case GENERATE_CONTEST_POSTMORTEM -> executeContestPostmortem(job);
            case GENERATE_STUDENT_POSTMORTEM -> executeStudentPostmortem(job, payload);
            case BATCH_GENERATE_STUDENT_POSTMORTEMS -> executeBatchStudentPostmortems(job, payload);
        };
    }

    private ContestExportResponse executeExport(OperationJobEntity job, Map<String, Object> payload) {
        ContestExportFormat format = enumValue(ContestExportFormat.class, (String) payload.get("format"), ContestExportFormat.CSV);
        return switch (job.getJobType()) {
            case EXPORT_SCOREBOARD -> contestExportService.exportScoreboard(job.getContestId(), format,
                    longValue(payload.get("runId")),
                    enumValue(ContestScoreboardView.class, (String) payload.get("view"), ContestScoreboardView.PUBLIC),
                    longValue(payload.get("atMillis")), longValue(payload.get("snapshotId")));
            case EXPORT_SUBMISSIONS -> contestExportService.exportSubmissions(job.getContestId(), format,
                    longValue(payload.get("runId")), longValue(payload.get("contestProblemId")),
                    longValue(payload.get("participantId")), longValue(payload.get("userId")),
                    enumValue(SubmissionStatus.class, (String) payload.get("status"), null),
                    (String) payload.get("language"));
            case EXPORT_PLAGIARISM_REPORT -> plagiarismService.exportJob(job.getContestId(),
                    longValue(payload.get("plagiarismJobId")), format);
            default -> throw new DomainException(ErrorCode.BAD_REQUEST, "Operation job is not an export job");
        };
    }

    private JobExecutionResult executePlagiarismCheck(OperationJobEntity job, Map<String, Object> payload) {
        PlagiarismJobCreateRequest request = new PlagiarismJobCreateRequest(
                longListValue(payload.get("contestProblemIds")),
                stringListValue(payload.get("languages")),
                doubleValue(payload.get("minimumSimilarity")),
                booleanValue(payload.get("includeAiAnalysis"))
        );
        PlagiarismJobResponse created = plagiarismService.createJobForOperation(job.getContestId(), job.getContestRunId(), request);
        job.setProgressMessage("Running plagiarism job #" + created.id());
        jobMapper.updateById(job);
        PlagiarismJobResponse completed = plagiarismService.runJobForOperation(job.getContestId(), created.id(), request);
        return new JobExecutionResult(null, Map.of(
                "plagiarismJobId", completed.id(),
                "totalSubmissions", completed.totalSubmissions(),
                "totalPairs", completed.totalPairs(),
                "highRiskPairs", completed.highRiskPairs()
        ));
    }

    private JobExecutionResult executeScoreboardTimeline(OperationJobEntity job, Map<String, Object> payload) {
        ContestScoreboardView view = enumValue(ContestScoreboardView.class, (String) payload.get("view"), ContestScoreboardView.PUBLIC);
        int tickCount = contestScoreboardService.generateTimelineForOperation(job.getContestId(), job.getContestRunId(), view,
                (current, total, message) -> {
                    job.setProgressCurrent(current);
                    job.setProgressTotal(total);
                    job.setProgressMessage(message);
                    job.setUpdatedAt(Instant.now());
                    jobMapper.updateById(job);
                });
        return new JobExecutionResult(null, Map.of("tickCount", tickCount, "view", view.name()));
    }

    private JobExecutionResult executeContestPostmortem(OperationJobEntity job) {
        ContestPostmortemReportResponse report = contestPostmortemService.createReport(job.getContestId(), job.getContestRunId());
        return new JobExecutionResult(null, Map.of("reportId", report.id()));
    }

    private JobExecutionResult executeStudentPostmortem(OperationJobEntity job, Map<String, Object> payload) {
        Long participantId = longValue(payload.get("participantId"));
        ContestStudentPostmortemReportResponse report = participantId == null
                ? studentPostmortemService.createMyReport(job.getContestId(), job.getContestRunId())
                : studentPostmortemService.createParticipantReport(job.getContestId(), job.getContestRunId(), participantId);
        return new JobExecutionResult(null, Map.of("reportId", report.id(), "participantId", report.contestParticipantId()));
    }

    private JobExecutionResult executeBatchStudentPostmortems(OperationJobEntity job, Map<String, Object> payload) {
        List<Long> participantIds = longListValue(payload.get("participantIds"));
        List<ContestStudentPostmortemReportResponse> reports = studentPostmortemService.createParticipantReports(
                job.getContestId(),
                job.getContestRunId(),
                participantIds,
                100,
                (index, account) -> {
                    job.setProgressCurrent(index);
                    job.setProgressMessage(account == null ? "Generating student postmortems" : "Generating " + account);
                    jobMapper.updateById(job);
                });
        return new JobExecutionResult(null, Map.of(
                "generatedReportIds", reports.stream().map(ContestStudentPostmortemReportResponse::id).toList(),
                "generatedCount", reports.size()
        ));
    }

    private void persistArtifact(OperationJobEntity job, ContestExportResponse exported) {
        try {
            byte[] content = Base64.getDecoder().decode(exported.base64Content());
            Path root = properties.getArtifactRoot().toAbsolutePath().normalize();
            String safeName = exported.fileName().replaceAll("[^A-Za-z0-9._-]", "_");
            Path directory = root.resolve(String.valueOf(job.getId())).normalize();
            if (!directory.startsWith(root)) {
                throw new DomainException(ErrorCode.FORBIDDEN, "Invalid artifact directory");
            }
            Files.createDirectories(directory);
            Path file = directory.resolve(safeName).normalize();
            Files.write(file, content);
            OperationJobArtifactEntity artifact = new OperationJobArtifactEntity();
            artifact.setJobId(job.getId());
            artifact.setFileName(exported.fileName());
            artifact.setContentType(exported.contentType());
            artifact.setStorageProvider("LOCAL_FILE");
            artifact.setStorageKey(root.relativize(file).toString().replace('\\', '/'));
            artifact.setByteSize((long) content.length);
            artifact.setSha256(sha256(content));
            artifact.setCreatedAt(Instant.now());
            artifactMapper.insert(artifact);
        } catch (IOException ex) {
            throw new DomainException(ErrorCode.INTERNAL_ERROR, "Failed to persist operation artifact");
        }
    }

    private <T> T withJobSecurity(OperationJobEntity job, Map<String, Object> payload, Supplier<T> action) {
        String account = (String) payload.getOrDefault("actorAccount", "operation-job-" + job.getRequestedBy());
        @SuppressWarnings("unchecked")
        List<String> roleNames = (List<String>) payload.get("actorRoles");
        if (roleNames == null || roleNames.isEmpty()) {
            throw new DomainException(ErrorCode.FORBIDDEN, "Operation job actor roles are missing");
        }
        Set<Role> roles = roleNames.stream().map(Role::valueOf).collect(Collectors.toSet());
        var authorities = roles.stream()
                .map(role -> new SimpleGrantedAuthority("ROLE_" + role.name()))
                .toList();
        var context = SecurityContextHolder.createEmptyContext();
        context.setAuthentication(new UsernamePasswordAuthenticationToken(
                new SecurityPrincipal(job.getRequestedBy(), account, roles), null, authorities));
        var previous = SecurityContextHolder.getContext();
        try {
            SecurityContextHolder.setContext(context);
            return action.get();
        } finally {
            SecurityContextHolder.setContext(previous);
        }
    }

    private OperationJobEntity requireVisibleJob(Long jobId) {
        OperationJobEntity job = jobMapper.selectById(jobId);
        if (job == null) {
            throw new DomainException(ErrorCode.NOT_FOUND, "Operation job not found");
        }
        if (!SecuritySupport.hasRole(Role.ADMIN) && !SecuritySupport.currentUserId().equals(job.getRequestedBy())) {
            throw new DomainException(ErrorCode.FORBIDDEN, "Cannot view this operation job");
        }
        return job;
    }

    private OperationJobArtifactEntity artifactForJob(Long jobId) {
        return artifactMapper.selectOne(new LambdaQueryWrapper<OperationJobArtifactEntity>()
                .eq(OperationJobArtifactEntity::getJobId, jobId)
                .orderByDesc(OperationJobArtifactEntity::getCreatedAt)
                .last("LIMIT 1"));
    }

    private OperationJobEntity findActiveScoreboardTimelineJob(Long contestId, Long runId, ContestScoreboardView view) {
        List<OperationJobEntity> candidates = jobMapper.selectList(new LambdaQueryWrapper<OperationJobEntity>()
                .eq(OperationJobEntity::getJobType, OperationJobType.GENERATE_SCOREBOARD_TIMELINE)
                .eq(OperationJobEntity::getContestId, contestId)
                .eq(OperationJobEntity::getContestRunId, runId)
                .in(OperationJobEntity::getStatus, OperationJobStatus.QUEUED, OperationJobStatus.RUNNING)
                .orderByDesc(OperationJobEntity::getCreatedAt)
                .orderByDesc(OperationJobEntity::getId));
        for (OperationJobEntity candidate : candidates) {
            ContestScoreboardView candidateView = enumValue(ContestScoreboardView.class,
                    (String) readJson(candidate.getRequestJson()).get("view"), ContestScoreboardView.PUBLIC);
            if (candidateView == view) {
                return candidate;
            }
        }
        return null;
    }

    private OperationJobResponse toResponse(OperationJobEntity job) {
        return new OperationJobResponse(job.getId(), job.getJobType(), job.getStatus(), job.getResourceType(),
                job.getResourceId(), job.getContestId(), job.getContestRunId(), job.getRequestedBy(),
                job.getErrorMessage(), job.getAttemptCount(), job.getMaxAttempts(),
                job.getProgressCurrent(), job.getProgressTotal(), job.getProgressMessage(), job.getResultJson(),
                toArtifactResponse(artifactForJob(job.getId())), job.getStartedAt(), job.getCompletedAt(),
                job.getCreatedAt(), job.getUpdatedAt());
    }

    private ContestStudentPostmortemOperationJobResponse toStudentPostmortemJobResponse(OperationJobEntity job) {
        return new ContestStudentPostmortemOperationJobResponse(job.getId(), job.getStatus(), job.getCreatedAt(),
                job.getStartedAt(), job.getUpdatedAt());
    }

    private OperationJobArtifactResponse toArtifactResponse(OperationJobArtifactEntity artifact) {
        if (artifact == null) {
            return null;
        }
        return new OperationJobArtifactResponse(artifact.getId(), artifact.getJobId(), artifact.getFileName(),
                artifact.getContentType(), artifact.getByteSize() == null ? 0 : artifact.getByteSize(),
                artifact.getSha256(), artifact.getExpiresAt(), artifact.getCreatedAt());
    }

    private Map<String, Object> basePayload() {
        var principal = SecuritySupport.currentPrincipal();
        Map<String, Object> payload = new HashMap<>();
        payload.put("actorAccount", principal.account());
        payload.put("actorRoles", principal.roles().stream().map(Role::name).toList());
        return payload;
    }

    private void requireStaff() {
        if (!SecuritySupport.hasAnyRole(Role.TEACHER, Role.ADMIN)) {
            throw new DomainException(ErrorCode.FORBIDDEN, "Cannot manage operation jobs");
        }
    }

    private Map<String, Object> readJson(String json) {
        if (json == null || json.isBlank()) {
            return new HashMap<>();
        }
        try {
            return objectMapper.readValue(json, new TypeReference<>() {});
        } catch (JsonProcessingException ex) {
            throw new DomainException(ErrorCode.INTERNAL_ERROR, "Invalid operation job payload");
        }
    }

    private String toJson(Map<String, Object> payload) {
        try {
            return objectMapper.writeValueAsString(payload);
        } catch (JsonProcessingException ex) {
            throw new DomainException(ErrorCode.INTERNAL_ERROR, "Failed to serialize operation job payload");
        }
    }

    private <T extends Enum<T>> T enumValue(Class<T> type, String value, T fallback) {
        if (value == null || value.isBlank()) {
            return fallback;
        }
        return Enum.valueOf(type, value);
    }

    private String value(Enum<?> value) {
        return value == null ? null : value.name();
    }

    private Long longValue(Object value) {
        if (value == null) {
            return null;
        }
        if (value instanceof Number number) {
            return number.longValue();
        }
        String string = String.valueOf(value);
        return string.isBlank() ? null : Long.valueOf(string);
    }

    private List<Long> longListValue(Object value) {
        if (!(value instanceof List<?> list) || list.isEmpty()) {
            return null;
        }
        return list.stream()
                .map(this::longValue)
                .filter(item -> item != null)
                .toList();
    }

    private List<String> stringListValue(Object value) {
        if (!(value instanceof List<?> list) || list.isEmpty()) {
            return null;
        }
        return list.stream()
                .map(item -> item == null ? null : String.valueOf(item))
                .filter(item -> item != null && !item.isBlank())
                .toList();
    }

    private Double doubleValue(Object value) {
        if (value == null) {
            return null;
        }
        if (value instanceof Number number) {
            return number.doubleValue();
        }
        String string = String.valueOf(value);
        return string.isBlank() ? null : Double.valueOf(string);
    }

    private Boolean booleanValue(Object value) {
        if (value == null) {
            return null;
        }
        if (value instanceof Boolean bool) {
            return bool;
        }
        return Boolean.valueOf(String.valueOf(value));
    }

    private String sha256(byte[] content) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            byte[] hash = digest.digest(content);
            StringBuilder builder = new StringBuilder();
            for (byte b : hash) {
                builder.append(String.format(Locale.ROOT, "%02x", b));
            }
            return builder.toString();
        } catch (NoSuchAlgorithmException ex) {
            throw new DomainException(ErrorCode.INTERNAL_ERROR, "SHA-256 is unavailable");
        }
    }

    private String action(OperationJobType type) {
        return switch (type) {
            case EXPORT_SCOREBOARD -> "EXPORT_SCOREBOARD";
            case EXPORT_SUBMISSIONS -> "EXPORT_SUBMISSIONS";
            case EXPORT_PLAGIARISM_REPORT -> "EXPORT_PLAGIARISM_REPORT";
            case RUN_PLAGIARISM_CHECK -> "RUN_PLAGIARISM_CHECK";
            case GENERATE_SCOREBOARD_TIMELINE -> "GENERATE_SCOREBOARD_TIMELINE";
            case GENERATE_CONTEST_POSTMORTEM -> "GENERATE_CONTEST_POSTMORTEM";
            case GENERATE_STUDENT_POSTMORTEM -> "GENERATE_STUDENT_POSTMORTEM";
            case BATCH_GENERATE_STUDENT_POSTMORTEMS -> "BATCH_GENERATE_STUDENT_POSTMORTEMS";
        };
    }

    private String workerId() {
        try {
            return InetAddress.getLocalHost().getHostName() + "-" + UUID.randomUUID();
        } catch (UnknownHostException ex) {
            return "operation-worker-" + UUID.randomUUID();
        }
    }

    private String trim(String value, int maxLength) {
        if (value == null) {
            return null;
        }
        return value.length() <= maxLength ? value : value.substring(0, maxLength);
    }

    private long normalizePage(long page) {
        return Math.max(page, 1);
    }

    private long normalizePageSize(long pageSize) {
        if (pageSize <= 0) {
            return 20;
        }
        return Math.min(pageSize, 100);
    }

    private record JobExecutionResult(ContestExportResponse artifact, Map<String, Object> result) {
    }
}
