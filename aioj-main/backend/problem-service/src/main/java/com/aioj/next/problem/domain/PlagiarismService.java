package com.aioj.next.problem.domain;

import com.aioj.next.common.api.PageResponse;
import com.aioj.next.common.api.TraceIds;
import com.aioj.next.common.error.DomainException;
import com.aioj.next.common.error.ErrorCode;
import com.aioj.next.common.security.Role;
import com.aioj.next.common.security.SecuritySupport;
import com.aioj.next.contract.ai.PlagiarismAnalysisRequest;
import com.aioj.next.contract.ai.PlagiarismAnalysisResponse;
import com.aioj.next.contract.contest.ContestExportFormat;
import com.aioj.next.contract.contest.ContestExportResponse;
import com.aioj.next.contract.contest.PlagiarismAiStatus;
import com.aioj.next.contract.contest.PlagiarismDetectorType;
import com.aioj.next.contract.contest.PlagiarismFragmentResponse;
import com.aioj.next.contract.contest.PlagiarismJobCreateRequest;
import com.aioj.next.contract.contest.PlagiarismJobResponse;
import com.aioj.next.contract.contest.PlagiarismJobStatus;
import com.aioj.next.contract.contest.PlagiarismPairDetailResponse;
import com.aioj.next.contract.contest.PlagiarismPairResponse;
import com.aioj.next.contract.contest.PlagiarismPairUpdateRequest;
import com.aioj.next.contract.contest.PlagiarismReviewStatus;
import com.aioj.next.contract.contest.PlagiarismRiskLevel;
import com.aioj.next.contract.contest.ContestRunStatus;
import com.aioj.next.contract.submission.SubmissionStatus;
import com.aioj.next.problem.config.PlagiarismProperties;
import com.aioj.next.problem.domain.plagiarism.PlagiarismAiAnalysisClient;
import com.aioj.next.problem.domain.plagiarism.PlagiarismDetector;
import com.aioj.next.problem.persistence.entity.ContestEntity;
import com.aioj.next.problem.persistence.entity.ContestParticipantEntity;
import com.aioj.next.problem.persistence.entity.ContestProblemEntity;
import com.aioj.next.problem.persistence.entity.ContestRunEntity;
import com.aioj.next.problem.persistence.entity.PlagiarismAiAnalysisEntity;
import com.aioj.next.problem.persistence.entity.PlagiarismFragmentEntity;
import com.aioj.next.problem.persistence.entity.PlagiarismJobEntity;
import com.aioj.next.problem.persistence.entity.PlagiarismJobSubmissionEntity;
import com.aioj.next.problem.persistence.entity.PlagiarismPairEntity;
import com.aioj.next.problem.persistence.entity.SubmissionEntity;
import com.aioj.next.problem.persistence.mapper.ContestMapper;
import com.aioj.next.problem.persistence.mapper.ContestParticipantMapper;
import com.aioj.next.problem.persistence.mapper.ContestProblemMapper;
import com.aioj.next.problem.persistence.mapper.ContestRunMapper;
import com.aioj.next.problem.persistence.mapper.PlagiarismAiAnalysisMapper;
import com.aioj.next.problem.persistence.mapper.PlagiarismFragmentMapper;
import com.aioj.next.problem.persistence.mapper.PlagiarismJobMapper;
import com.aioj.next.problem.persistence.mapper.PlagiarismJobSubmissionMapper;
import com.aioj.next.problem.persistence.mapper.PlagiarismPairMapper;
import com.aioj.next.problem.persistence.mapper.SubmissionMapper;
import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.conditions.update.UpdateWrapper;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.apache.poi.ss.usermodel.Cell;
import org.apache.poi.ss.usermodel.CellStyle;
import org.apache.poi.ss.usermodel.Row;
import org.apache.poi.ss.usermodel.Sheet;
import org.apache.poi.ss.usermodel.Workbook;
import org.apache.poi.xssf.usermodel.XSSFWorkbook;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import org.springframework.util.StringUtils;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Instant;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.Base64;
import java.util.Collection;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.concurrent.Executor;
import java.util.function.Function;
import java.util.stream.Collectors;

@Service
public class PlagiarismService {
    private static final int PAGE_SIZE_LIMIT = 100;
    private static final double DEFAULT_MINIMUM_SIMILARITY = 0.55;
    private static final byte[] UTF8_BOM = new byte[]{(byte) 0xEF, (byte) 0xBB, (byte) 0xBF};
    private static final DateTimeFormatter FILE_TIME_FORMATTER = DateTimeFormatter
            .ofPattern("yyyyMMdd-HHmmss")
            .withZone(ZoneOffset.UTC);
    private static final Set<String> SUPPORTED_LANGUAGES = Set.of("cpp", "java", "python");
    private static final Set<SubmissionStatus> UNFINISHED_STATUSES = Set.of(
            SubmissionStatus.QUEUED,
            SubmissionStatus.RUNNING,
            SubmissionStatus.SYSTEM_ERROR
    );

    private final ContestMapper contestMapper;
    private final ContestProblemMapper contestProblemMapper;
    private final ContestParticipantMapper contestParticipantMapper;
    private final ContestRunMapper contestRunMapper;
    private final SubmissionMapper submissionMapper;
    private final PlagiarismJobMapper jobMapper;
    private final PlagiarismJobSubmissionMapper jobSubmissionMapper;
    private final PlagiarismPairMapper pairMapper;
    private final PlagiarismFragmentMapper fragmentMapper;
    private final PlagiarismAiAnalysisMapper aiAnalysisMapper;
    private final PlagiarismDetector detector;
    private final PlagiarismAiAnalysisClient aiAnalysisClient;
    private final PlagiarismProperties properties;
    private final ObjectMapper objectMapper;
    private final Executor executor;

    public PlagiarismService(ContestMapper contestMapper,
                             ContestProblemMapper contestProblemMapper,
                             ContestParticipantMapper contestParticipantMapper,
                             ContestRunMapper contestRunMapper,
                             SubmissionMapper submissionMapper,
                             PlagiarismJobMapper jobMapper,
                             PlagiarismJobSubmissionMapper jobSubmissionMapper,
                             PlagiarismPairMapper pairMapper,
                             PlagiarismFragmentMapper fragmentMapper,
                             PlagiarismAiAnalysisMapper aiAnalysisMapper,
                             PlagiarismDetector detector,
                             PlagiarismAiAnalysisClient aiAnalysisClient,
                             PlagiarismProperties properties,
                             ObjectMapper objectMapper,
                             @Qualifier("plagiarismExecutor") Executor executor) {
        this.contestMapper = contestMapper;
        this.contestProblemMapper = contestProblemMapper;
        this.contestParticipantMapper = contestParticipantMapper;
        this.contestRunMapper = contestRunMapper;
        this.submissionMapper = submissionMapper;
        this.jobMapper = jobMapper;
        this.jobSubmissionMapper = jobSubmissionMapper;
        this.pairMapper = pairMapper;
        this.fragmentMapper = fragmentMapper;
        this.aiAnalysisMapper = aiAnalysisMapper;
        this.detector = detector;
        this.aiAnalysisClient = aiAnalysisClient;
        this.properties = properties;
        this.objectMapper = objectMapper;
        this.executor = executor;
    }

    @Transactional
    public PlagiarismJobResponse createJob(Long contestId, Long runId, PlagiarismJobCreateRequest request) {
        return createJob(contestId, runId, request, true);
    }

    @Transactional
    public PlagiarismJobResponse createJobForOperation(Long contestId, Long runId, PlagiarismJobCreateRequest request) {
        return createJob(contestId, runId, request, false);
    }

    private PlagiarismJobResponse createJob(Long contestId, Long runId, PlagiarismJobCreateRequest request, boolean schedule) {
        requireManagedContest(contestId);
        requireEligibleRunForJob(contestId, runId);
        PlagiarismJobCreateRequest normalized = normalizeRequest(request);
        Instant now = Instant.now();
        PlagiarismJobEntity job = new PlagiarismJobEntity();
        job.setContestId(contestId);
        job.setContestRunId(runId);
        job.setStatus(PlagiarismJobStatus.QUEUED);
        job.setDetector(PlagiarismDetectorType.JPLAG_4_3);
        job.setOptionsJson(optionsJson(normalized));
        job.setMinimumSimilarity(normalized.minimumSimilarity());
        job.setIncludeAiAnalysis(Boolean.TRUE.equals(normalized.includeAiAnalysis()));
        job.setTotalSubmissions(0);
        job.setTotalPairs(0);
        job.setHighRiskPairs(0);
        job.setCreatedBy(SecuritySupport.currentUserId());
        job.setCreatedAt(now);
        job.setUpdatedAt(now);
        jobMapper.insert(job);
        if (schedule) {
            scheduleJobAfterCommit(job.getId(), normalized);
        }
        return toJobResponse(job);
    }

    public PlagiarismJobResponse createJob(Long contestId, PlagiarismJobCreateRequest request) {
        return createJob(contestId, null, request);
    }

    public PageResponse<PlagiarismJobResponse> listJobs(Long contestId, Long runId, long page, long pageSize) {
        requireManagedContest(contestId);
        resumeQueuedJobs(contestId);
        Page<PlagiarismJobEntity> result = jobMapper.selectPage(new Page<>(normalizePage(page), normalizePageSize(pageSize)),
                new LambdaQueryWrapper<PlagiarismJobEntity>()
                        .eq(PlagiarismJobEntity::getContestId, contestId)
                        .eq(runId != null, PlagiarismJobEntity::getContestRunId, runId)
                        .orderByDesc(PlagiarismJobEntity::getCreatedAt)
                        .orderByDesc(PlagiarismJobEntity::getId));
        return new PageResponse<>(result.getRecords().stream().map(this::toJobResponse).toList(),
                result.getTotal(), result.getCurrent(), result.getSize());
    }

    public PageResponse<PlagiarismJobResponse> listJobs(Long contestId, long page, long pageSize) {
        return listJobs(contestId, null, page, pageSize);
    }

    public PlagiarismJobResponse getJob(Long contestId, Long jobId) {
        requireManagedContest(contestId);
        PlagiarismJobEntity job = requireJob(contestId, jobId);
        if (job.getStatus() == PlagiarismJobStatus.QUEUED) {
            scheduleJob(job.getId(), requestFromJob(job));
        }
        return toJobResponse(job);
    }

    public PageResponse<PlagiarismPairResponse> listPairs(Long contestId, Long jobId, long page, long pageSize,
                                                          Long contestProblemId, String language,
                                                          PlagiarismRiskLevel riskLevel,
                                                          PlagiarismReviewStatus reviewStatus) {
        requireManagedContest(contestId);
        requireJob(contestId, jobId);
        String normalizedLanguage = normalizeLanguage(language);
        Page<PlagiarismPairEntity> result = pairMapper.selectPage(new Page<>(normalizePage(page), normalizePageSize(pageSize)),
                new LambdaQueryWrapper<PlagiarismPairEntity>()
                        .eq(PlagiarismPairEntity::getContestId, contestId)
                        .eq(PlagiarismPairEntity::getJobId, jobId)
                        .eq(contestProblemId != null, PlagiarismPairEntity::getContestProblemId, contestProblemId)
                        .eq(normalizedLanguage != null, PlagiarismPairEntity::getLanguage, normalizedLanguage)
                        .eq(riskLevel != null, PlagiarismPairEntity::getRiskLevel, riskLevel)
                        .eq(reviewStatus != null, PlagiarismPairEntity::getReviewStatus, reviewStatus)
                        .orderByDesc(PlagiarismPairEntity::getSimilarity)
                        .orderByDesc(PlagiarismPairEntity::getId));
        Lookup lookup = lookup(contestId, result.getRecords());
        return new PageResponse<>(result.getRecords().stream().map(pair -> toPairResponse(pair, lookup, null)).toList(),
                result.getTotal(), result.getCurrent(), result.getSize());
    }

    public PlagiarismPairDetailResponse pairDetail(Long contestId, Long jobId, Long pairId) {
        requireManagedContest(contestId);
        PlagiarismPairEntity pair = requirePair(contestId, jobId, pairId);
        Lookup lookup = lookup(contestId, List.of(pair));
        PlagiarismAiAnalysisEntity analysis = latestAnalysis(pairId);
        List<PlagiarismFragmentResponse> fragments = fragmentMapper.selectList(new LambdaQueryWrapper<PlagiarismFragmentEntity>()
                        .eq(PlagiarismFragmentEntity::getPairId, pairId)
                        .orderByAsc(PlagiarismFragmentEntity::getSequenceNo))
                .stream()
                .map(this::toFragmentResponse)
                .toList();
        return new PlagiarismPairDetailResponse(toPairResponse(pair, lookup, analysis), fragments,
                analysis == null ? null : analysis.getAnalysis(), analysis == null ? null : analysis.getErrorMessage());
    }

    @Transactional
    public PlagiarismPairResponse updatePair(Long contestId, Long jobId, Long pairId, PlagiarismPairUpdateRequest request) {
        requireManagedContest(contestId);
        PlagiarismPairEntity pair = requirePair(contestId, jobId, pairId);
        pair.setReviewStatus(request == null || request.reviewStatus() == null ? pair.getReviewStatus() : request.reviewStatus());
        pair.setTeacherNote(normalizeNote(request == null ? null : request.teacherNote()));
        pair.setReviewedBy(SecuritySupport.currentUserId());
        pair.setReviewedAt(Instant.now());
        pair.setUpdatedAt(Instant.now());
        pairMapper.updateById(pair);
        return toPairResponse(pair, lookup(contestId, List.of(pair)), latestAnalysis(pairId));
    }

    @Transactional
    public PlagiarismPairDetailResponse retryAiAnalysis(Long contestId, Long jobId, Long pairId) {
        requireManagedContest(contestId);
        PlagiarismPairEntity pair = requirePair(contestId, jobId, pairId);
        PlagiarismJobEntity job = requireJob(contestId, jobId);
        runAiAnalysis(job, pair);
        return pairDetail(contestId, jobId, pairId);
    }

    public ContestExportResponse exportJob(Long contestId, Long jobId, ContestExportFormat format) {
        requireManagedContest(contestId);
        requireJob(contestId, jobId);
        ContestExportFormat effectiveFormat = format == null ? ContestExportFormat.CSV : format;
        List<PlagiarismPairEntity> pairs = pairMapper.selectList(new LambdaQueryWrapper<PlagiarismPairEntity>()
                .eq(PlagiarismPairEntity::getContestId, contestId)
                .eq(PlagiarismPairEntity::getJobId, jobId)
                .orderByDesc(PlagiarismPairEntity::getSimilarity)
                .orderByDesc(PlagiarismPairEntity::getId));
        Lookup lookup = lookup(contestId, pairs);
        List<List<String>> rows = new ArrayList<>();
        rows.add(List.of("pairId", "riskLevel", "reviewStatus", "similarity", "maximalSimilarity", "minimalSimilarity",
                "matchedTokens", "problemLabel", "problemTitle", "language", "leftSubmissionId", "leftParticipant",
                "rightSubmissionId", "rightParticipant", "aiStatus", "aiSummary", "teacherNote", "createdAt"));
        for (PlagiarismPairEntity pair : pairs) {
            PlagiarismAiAnalysisEntity analysis = latestAnalysis(pair.getId());
            PlagiarismPairResponse response = toPairResponse(pair, lookup, analysis);
            rows.add(row(
                    string(response.id()),
                    response.riskLevel().name(),
                    response.reviewStatus().name(),
                    decimal(response.similarity()),
                    decimal(response.maximalSimilarity()),
                    decimal(response.minimalSimilarity()),
                    string(response.matchedTokens()),
                    response.problemLabel(),
                    response.problemTitle(),
                    response.language(),
                    string(response.leftSubmissionId()),
                    response.leftDisplayNameSnapshot() + " (" + response.leftAccountSnapshot() + ")",
                    string(response.rightSubmissionId()),
                    response.rightDisplayNameSnapshot() + " (" + response.rightAccountSnapshot() + ")",
                    response.aiStatus().name(),
                    response.aiSummary(),
                    response.teacherNote(),
                    string(response.createdAt())
            ));
        }
        String fileName = "contest-" + contestId + "-plagiarism-" + jobId + "-"
                + FILE_TIME_FORMATTER.format(Instant.now()) + extension(effectiveFormat);
        byte[] content = switch (effectiveFormat) {
            case CSV -> csv(rows);
            case XLSX -> xlsx("plagiarism", rows);
        };
        return new ContestExportResponse(fileName, contentType(effectiveFormat),
                Base64.getEncoder().encodeToString(content), content.length);
    }

    void runJob(Long jobId, PlagiarismJobCreateRequest request) {
        PlagiarismJobEntity job = jobMapper.selectById(jobId);
        if (job == null) {
            return;
        }
        try {
            if (!markJobRunning(job)) {
                return;
            }
            RunData runData = collectSubmissions(job, request);
            List<PlagiarismPairEntity> insertedPairs = new ArrayList<>();
            Map<Long, PlagiarismDetector.DetectionSubmission> detectionBySubmissionId = runData.detectionSubmissions().stream()
                    .collect(Collectors.toMap(PlagiarismDetector.DetectionSubmission::submissionId, Function.identity()));
            for (Map.Entry<GroupKey, List<PlagiarismDetector.DetectionSubmission>> entry : runData.groups().entrySet()) {
                if (entry.getValue().size() < 2) {
                    continue;
                }
                List<PlagiarismDetector.DetectedPair> detectedPairs = detector.detect(
                        new PlagiarismDetector.DetectionGroup(entry.getKey().language(), entry.getValue()),
                        new PlagiarismDetector.DetectionOptions(job.getMinimumSimilarity(), properties.getMaxFragmentExcerptChars())
                );
                for (PlagiarismDetector.DetectedPair detectedPair : detectedPairs) {
                    PlagiarismDetector.DetectionSubmission leftDetection = detectionBySubmissionId.get(detectedPair.leftSubmissionId());
                    PlagiarismDetector.DetectionSubmission rightDetection = detectionBySubmissionId.get(detectedPair.rightSubmissionId());
                    if (leftDetection == null || rightDetection == null
                            || Objects.equals(leftDetection.userId(), rightDetection.userId())
                            || Objects.equals(leftDetection.participantId(), rightDetection.participantId())) {
                        continue;
                    }
                    PlagiarismJobSubmissionEntity left = runData.bySubmissionId().get(detectedPair.leftSubmissionId());
                    PlagiarismJobSubmissionEntity right = runData.bySubmissionId().get(detectedPair.rightSubmissionId());
                    PlagiarismPairEntity pair = insertPair(job, entry.getKey(), left, right, detectedPair);
                    insertFragments(job, pair, detectedPair.fragments());
                    insertedPairs.add(pair);
                }
            }
            promoteRepeatedHighRiskPairs(insertedPairs);
            List<PlagiarismPairEntity> finalPairs = pairMapper.selectList(new LambdaQueryWrapper<PlagiarismPairEntity>()
                    .eq(PlagiarismPairEntity::getJobId, job.getId()));
            if (Boolean.TRUE.equals(job.getIncludeAiAnalysis())) {
                for (PlagiarismPairEntity pair : finalPairs) {
                    if (isHighRisk(pair.getRiskLevel())) {
                        runAiAnalysis(job, pair);
                    }
                }
            }
            completeJob(job, runData.bySubmissionId().size(), finalPairs.size(),
                    (int) finalPairs.stream().filter(pair -> isHighRisk(pair.getRiskLevel())).count());
        } catch (RuntimeException ex) {
            failJob(job, ex.getMessage());
        }
    }

    public PlagiarismJobResponse runJobForOperation(Long contestId, Long jobId, PlagiarismJobCreateRequest request) {
        runJob(jobId, request);
        PlagiarismJobEntity job = requireJob(contestId, jobId);
        if (job.getStatus() == PlagiarismJobStatus.FAILED) {
            throw new DomainException(ErrorCode.INTERNAL_ERROR, job.getErrorMessage() == null
                    ? "Plagiarism job failed"
                    : job.getErrorMessage());
        }
        return toJobResponse(job);
    }

    private void scheduleJobAfterCommit(Long jobId, PlagiarismJobCreateRequest request) {
        if (!TransactionSynchronizationManager.isSynchronizationActive()) {
            scheduleJob(jobId, request);
            return;
        }
        TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
            @Override
            public void afterCommit() {
                scheduleJob(jobId, request);
            }
        });
    }

    private void scheduleJob(Long jobId, PlagiarismJobCreateRequest request) {
        executor.execute(() -> runJob(jobId, request));
    }

    private void resumeQueuedJobs(Long contestId) {
        List<PlagiarismJobEntity> queuedJobs = jobMapper.selectList(new LambdaQueryWrapper<PlagiarismJobEntity>()
                .eq(PlagiarismJobEntity::getContestId, contestId)
                .eq(PlagiarismJobEntity::getStatus, PlagiarismJobStatus.QUEUED)
                .orderByAsc(PlagiarismJobEntity::getCreatedAt)
                .last("LIMIT 8"));
        for (PlagiarismJobEntity queuedJob : queuedJobs) {
            scheduleJob(queuedJob.getId(), requestFromJob(queuedJob));
        }
    }

    private boolean markJobRunning(PlagiarismJobEntity job) {
        Instant now = Instant.now();
        int updated = jobMapper.update(null, new UpdateWrapper<PlagiarismJobEntity>()
                .eq("id", job.getId())
                .eq("status", PlagiarismJobStatus.QUEUED.name())
                .set("status", PlagiarismJobStatus.RUNNING.name())
                .set("started_at", now)
                .set("updated_at", now));
        if (updated <= 0) {
            return false;
        }
        job.setStatus(PlagiarismJobStatus.RUNNING);
        job.setStartedAt(now);
        job.setUpdatedAt(now);
        return true;
    }

    private RunData collectSubmissions(PlagiarismJobEntity job, PlagiarismJobCreateRequest request) {
        List<SubmissionEntity> submissions = submissionMapper.selectList(new LambdaQueryWrapper<SubmissionEntity>()
                .eq(SubmissionEntity::getContestId, job.getContestId())
                .eq(job.getContestRunId() != null, SubmissionEntity::getContestRunId, job.getContestRunId())
                .eq(SubmissionEntity::getStatus, SubmissionStatus.ACCEPTED)
                .isNotNull(SubmissionEntity::getContestProblemId)
                .isNotNull(SubmissionEntity::getContestParticipantId)
                .isNotNull(SubmissionEntity::getCode)
                .orderByAsc(SubmissionEntity::getCreatedAt)
                .orderByAsc(SubmissionEntity::getId));
        Set<Long> problemFilter = request.contestProblemIds() == null ? Set.of() : new HashSet<>(request.contestProblemIds());
        Set<String> languageFilter = request.languages() == null ? Set.of() : request.languages().stream()
                .map(this::normalizeLanguage)
                .filter(Objects::nonNull)
                .collect(Collectors.toSet());
        Map<Long, ContestParticipantEntity> participants = contestParticipantMapper.selectList(new LambdaQueryWrapper<ContestParticipantEntity>()
                        .eq(ContestParticipantEntity::getContestId, job.getContestId())
                        .eq(job.getContestRunId() != null, ContestParticipantEntity::getContestRunId, job.getContestRunId()))
                .stream()
                .collect(Collectors.toMap(ContestParticipantEntity::getId, Function.identity()));
        Map<SubmissionSelectionKey, SubmissionEntity> latestByKey = new LinkedHashMap<>();
        for (SubmissionEntity submission : submissions) {
            String language = normalizeLanguage(submission.getLanguage());
            if (language == null || !SUPPORTED_LANGUAGES.contains(language)) {
                continue;
            }
            if (!problemFilter.isEmpty() && !problemFilter.contains(submission.getContestProblemId())) {
                continue;
            }
            if (!languageFilter.isEmpty() && !languageFilter.contains(language)) {
                continue;
            }
            if (!StringUtils.hasText(submission.getCode()) || !participants.containsKey(submission.getContestParticipantId())) {
                continue;
            }
            SubmissionSelectionKey key = new SubmissionSelectionKey(submission.getContestProblemId(),
                    submission.getContestParticipantId(), language);
            latestByKey.put(key, submission);
        }
        Map<Long, PlagiarismJobSubmissionEntity> bySubmissionId = new LinkedHashMap<>();
        Map<GroupKey, List<PlagiarismDetector.DetectionSubmission>> groups = new LinkedHashMap<>();
        List<PlagiarismDetector.DetectionSubmission> detectionSubmissions = new ArrayList<>();
        Instant now = Instant.now();
        for (SubmissionEntity submission : latestByKey.values()) {
            ContestParticipantEntity participant = participants.get(submission.getContestParticipantId());
            PlagiarismJobSubmissionEntity jobSubmission = new PlagiarismJobSubmissionEntity();
            jobSubmission.setJobId(job.getId());
            jobSubmission.setContestId(job.getContestId());
            jobSubmission.setContestProblemId(submission.getContestProblemId());
            jobSubmission.setProblemId(submission.getProblemId());
            jobSubmission.setSubmissionId(submission.getId());
            jobSubmission.setContestParticipantId(submission.getContestParticipantId());
            jobSubmission.setUserId(submission.getUserId());
            jobSubmission.setLanguage(normalizeLanguage(submission.getLanguage()));
            jobSubmission.setAccountSnapshot(participant.getAccountSnapshot());
            jobSubmission.setDisplayNameSnapshot(participant.getDisplayNameSnapshot());
            jobSubmission.setCodeHash(sha256(submission.getCode()));
            jobSubmission.setCodeChars(submission.getCode().length());
            jobSubmission.setIncluded(true);
            jobSubmission.setCreatedAt(now);
            jobSubmissionMapper.insert(jobSubmission);
            bySubmissionId.put(submission.getId(), jobSubmission);
            PlagiarismDetector.DetectionSubmission detectionSubmission = new PlagiarismDetector.DetectionSubmission(
                    jobSubmission.getId(), submission.getId(), submission.getUserId(), submission.getContestParticipantId(), submission.getCode());
            detectionSubmissions.add(detectionSubmission);
            groups.computeIfAbsent(new GroupKey(submission.getContestProblemId(), submission.getProblemId(), jobSubmission.getLanguage()),
                    ignored -> new ArrayList<>()).add(detectionSubmission);
        }
        return new RunData(bySubmissionId, groups, detectionSubmissions);
    }

    private PlagiarismPairEntity insertPair(PlagiarismJobEntity job, GroupKey key, PlagiarismJobSubmissionEntity left,
                                            PlagiarismJobSubmissionEntity right, PlagiarismDetector.DetectedPair detectedPair) {
        Instant now = Instant.now();
        PlagiarismPairEntity pair = new PlagiarismPairEntity();
        pair.setJobId(job.getId());
        pair.setContestId(job.getContestId());
        pair.setContestProblemId(key.contestProblemId());
        pair.setProblemId(key.problemId());
        pair.setLanguage(key.language());
        pair.setLeftJobSubmissionId(left.getId());
        pair.setRightJobSubmissionId(right.getId());
        pair.setLeftSubmissionId(left.getSubmissionId());
        pair.setRightSubmissionId(right.getSubmissionId());
        pair.setLeftUserId(left.getUserId());
        pair.setRightUserId(right.getUserId());
        pair.setLeftParticipantId(left.getContestParticipantId());
        pair.setRightParticipantId(right.getContestParticipantId());
        pair.setSimilarity(round(detectedPair.similarity()));
        pair.setMaximalSimilarity(round(detectedPair.maximalSimilarity()));
        pair.setMinimalSimilarity(round(detectedPair.minimalSimilarity()));
        pair.setMatchedTokens(Math.max(0, detectedPair.matchedTokens()));
        pair.setRiskLevel(riskLevel(detectedPair.similarity()));
        pair.setReviewStatus(PlagiarismReviewStatus.OPEN);
        pair.setAiStatus(PlagiarismAiStatus.SKIPPED);
        pair.setCreatedAt(now);
        pair.setUpdatedAt(now);
        pairMapper.insert(pair);
        return pair;
    }

    private void insertFragments(PlagiarismJobEntity job, PlagiarismPairEntity pair, List<PlagiarismDetector.DetectedFragment> fragments) {
        Instant now = Instant.now();
        for (PlagiarismDetector.DetectedFragment fragment : fragments == null ? List.<PlagiarismDetector.DetectedFragment>of() : fragments) {
            PlagiarismFragmentEntity entity = new PlagiarismFragmentEntity();
            entity.setJobId(job.getId());
            entity.setPairId(pair.getId());
            entity.setSequenceNo(fragment.sequenceNo());
            entity.setLeftStartToken(fragment.leftStartToken());
            entity.setRightStartToken(fragment.rightStartToken());
            entity.setTokenLength(fragment.tokenLength());
            entity.setLeftExcerpt(fragment.leftExcerpt());
            entity.setRightExcerpt(fragment.rightExcerpt());
            entity.setCreatedAt(now);
            fragmentMapper.insert(entity);
        }
    }

    private void promoteRepeatedHighRiskPairs(List<PlagiarismPairEntity> pairs) {
        Map<String, Set<Long>> highRiskProblemCounts = new HashMap<>();
        for (PlagiarismPairEntity pair : pairs) {
            if (!isHighRisk(pair.getRiskLevel())) {
                continue;
            }
            highRiskProblemCounts.computeIfAbsent(pairKey(pair), ignored -> new HashSet<>()).add(pair.getContestProblemId());
        }
        for (PlagiarismPairEntity pair : pairs) {
            if (highRiskProblemCounts.getOrDefault(pairKey(pair), Set.of()).size() >= 2
                    && pair.getRiskLevel() != PlagiarismRiskLevel.CRITICAL) {
                pair.setRiskLevel(PlagiarismRiskLevel.CRITICAL);
                pair.setUpdatedAt(Instant.now());
                pairMapper.updateById(pair);
            }
        }
    }

    private void runAiAnalysis(PlagiarismJobEntity job, PlagiarismPairEntity pair) {
        PlagiarismAiAnalysisEntity analysis = new PlagiarismAiAnalysisEntity();
        Instant now = Instant.now();
        analysis.setJobId(job.getId());
        analysis.setPairId(pair.getId());
        analysis.setStatus(PlagiarismAiStatus.RUNNING);
        analysis.setPromptTokens(0L);
        analysis.setCompletionTokens(0L);
        analysis.setTraceId(TraceIds.current());
        analysis.setCreatedAt(now);
        analysis.setUpdatedAt(now);
        aiAnalysisMapper.insert(analysis);
        pair.setAiStatus(PlagiarismAiStatus.RUNNING);
        pair.setUpdatedAt(Instant.now());
        pairMapper.updateById(pair);
        try {
            PlagiarismAnalysisResponse response = aiAnalysisClient.analyze(aiRequest(job, pair));
            if (!response.success()) {
                analysis.setStatus(PlagiarismAiStatus.FAILED);
                analysis.setProvider(response.provider());
                analysis.setModel(response.model());
                analysis.setPromptTokens(response.promptTokens());
                analysis.setCompletionTokens(response.completionTokens());
                analysis.setErrorMessage(summarize(response.errorMessage(), 480));
                analysis.setUpdatedAt(Instant.now());
                aiAnalysisMapper.updateById(analysis);
                pair.setAiStatus(PlagiarismAiStatus.FAILED);
                pair.setAiSummary(null);
                pair.setUpdatedAt(Instant.now());
                pairMapper.updateById(pair);
                return;
            }
            analysis.setStatus(PlagiarismAiStatus.COMPLETED);
            analysis.setProvider(response.provider());
            analysis.setModel(response.model());
            analysis.setPromptTokens(response.promptTokens());
            analysis.setCompletionTokens(response.completionTokens());
            analysis.setAnalysis(response.analysis());
            analysis.setErrorMessage(null);
            analysis.setUpdatedAt(Instant.now());
            aiAnalysisMapper.updateById(analysis);
            pair.setAiStatus(PlagiarismAiStatus.COMPLETED);
            pair.setAiSummary(summarize(response.analysis(), 480));
        } catch (RuntimeException ex) {
            analysis.setStatus(PlagiarismAiStatus.FAILED);
            analysis.setErrorMessage(summarize(ex.getMessage(), 480));
            analysis.setUpdatedAt(Instant.now());
            aiAnalysisMapper.updateById(analysis);
            pair.setAiStatus(PlagiarismAiStatus.FAILED);
            pair.setAiSummary(null);
        }
        pair.setUpdatedAt(Instant.now());
        pairMapper.updateById(pair);
    }

    private PlagiarismAnalysisRequest aiRequest(PlagiarismJobEntity job, PlagiarismPairEntity pair) {
        ContestEntity contest = contestMapper.selectById(pair.getContestId());
        ContestProblemEntity problem = contestProblemMapper.selectById(pair.getContestProblemId());
        Map<Long, PlagiarismJobSubmissionEntity> submissions = mapById(List.of(pair.getLeftJobSubmissionId(), pair.getRightJobSubmissionId()),
                jobSubmissionMapper::selectBatchIds, PlagiarismJobSubmissionEntity::getId);
        PlagiarismJobSubmissionEntity left = submissions.get(pair.getLeftJobSubmissionId());
        PlagiarismJobSubmissionEntity right = submissions.get(pair.getRightJobSubmissionId());
        List<PlagiarismAnalysisRequest.Fragment> fragments = fragmentMapper.selectList(new LambdaQueryWrapper<PlagiarismFragmentEntity>()
                        .eq(PlagiarismFragmentEntity::getPairId, pair.getId())
                        .orderByAsc(PlagiarismFragmentEntity::getSequenceNo))
                .stream()
                .limit(5)
                .map(fragment -> new PlagiarismAnalysisRequest.Fragment(fragment.getSequenceNo(), fragment.getTokenLength(),
                        fragment.getLeftExcerpt(), fragment.getRightExcerpt()))
                .toList();
        return new PlagiarismAnalysisRequest(job.getCreatedBy(), pair.getContestId(),
                contest == null ? "" : contest.getTitle(), job.getId(), pair.getId(),
                problem == null ? "" : problem.getLabel(), problem == null ? "" : problem.getDisplayTitle(),
                pair.getLanguage(), pair.getRiskLevel().name(), pair.getSimilarity(),
                participant(left), participant(right), fragments);
    }

    private PlagiarismAnalysisRequest.Participant participant(PlagiarismJobSubmissionEntity submission) {
        if (submission == null) {
            return new PlagiarismAnalysisRequest.Participant(null, null, null, "", "");
        }
        return new PlagiarismAnalysisRequest.Participant(submission.getUserId(), submission.getContestParticipantId(),
                submission.getSubmissionId(), submission.getAccountSnapshot(), submission.getDisplayNameSnapshot());
    }

    private void completeJob(PlagiarismJobEntity job, int totalSubmissions, int totalPairs, int highRiskPairs) {
        job.setStatus(PlagiarismJobStatus.COMPLETED);
        job.setTotalSubmissions(totalSubmissions);
        job.setTotalPairs(totalPairs);
        job.setHighRiskPairs(highRiskPairs);
        job.setCompletedAt(Instant.now());
        job.setUpdatedAt(Instant.now());
        jobMapper.updateById(job);
    }

    private void failJob(PlagiarismJobEntity job, String message) {
        job.setStatus(PlagiarismJobStatus.FAILED);
        job.setErrorMessage(summarize(message, 480));
        job.setCompletedAt(Instant.now());
        job.setUpdatedAt(Instant.now());
        jobMapper.updateById(job);
    }

    private ContestEntity requireManagedContest(Long contestId) {
        ContestEntity contest = contestMapper.selectById(contestId);
        if (contest == null || contest.getDeletedAt() != null) {
            throw new DomainException(ErrorCode.NOT_FOUND, "Contest not found");
        }
        if (SecuritySupport.hasRole(Role.ADMIN)
                || (SecuritySupport.hasRole(Role.TEACHER) && Objects.equals(contest.getOwnerUserId(), SecuritySupport.currentUserId()))) {
            return contest;
        }
        throw new DomainException(ErrorCode.FORBIDDEN, "Cannot manage contest plagiarism reports");
    }

    private ContestRunEntity requireEligibleRunForJob(Long contestId, Long runId) {
        if (runId == null) {
            throw new DomainException(ErrorCode.BAD_REQUEST, "Contest run is required for plagiarism jobs");
        }
        ContestRunEntity run = contestRunMapper.selectById(runId);
        if (run == null || run.getDeletedAt() != null || !Objects.equals(run.getContestId(), contestId)) {
            throw new DomainException(ErrorCode.NOT_FOUND, "Contest run not found");
        }
        Instant now = Instant.now();
        if (ContestRunStatePolicy.isArchived(run)) {
            throw new DomainException(ErrorCode.BAD_REQUEST, "Archived contest runs cannot start plagiarism jobs");
        }
        if (!ContestRunStatePolicy.isAiOperationsEligible(run, now)) {
            throw new DomainException(ErrorCode.BAD_REQUEST, "Plagiarism jobs require an ended contest run");
        }
        long unfinished = submissionMapper.selectCount(new LambdaQueryWrapper<SubmissionEntity>()
                .eq(SubmissionEntity::getContestId, contestId)
                .eq(SubmissionEntity::getContestRunId, run.getId())
                .in(SubmissionEntity::getStatus, UNFINISHED_STATUSES));
        if (unfinished > 0) {
            throw new DomainException(ErrorCode.BAD_REQUEST, "Contest run has unfinished or system-error submissions");
        }
        return run;
    }

    private PlagiarismJobEntity requireJob(Long contestId, Long jobId) {
        PlagiarismJobEntity job = jobMapper.selectOne(new LambdaQueryWrapper<PlagiarismJobEntity>()
                .eq(PlagiarismJobEntity::getId, jobId)
                .eq(PlagiarismJobEntity::getContestId, contestId));
        if (job == null) {
            throw new DomainException(ErrorCode.NOT_FOUND, "Plagiarism job not found");
        }
        return job;
    }

    private PlagiarismPairEntity requirePair(Long contestId, Long jobId, Long pairId) {
        PlagiarismPairEntity pair = pairMapper.selectOne(new LambdaQueryWrapper<PlagiarismPairEntity>()
                .eq(PlagiarismPairEntity::getId, pairId)
                .eq(PlagiarismPairEntity::getContestId, contestId)
                .eq(PlagiarismPairEntity::getJobId, jobId));
        if (pair == null) {
            throw new DomainException(ErrorCode.NOT_FOUND, "Plagiarism pair not found");
        }
        return pair;
    }

    private Lookup lookup(Long contestId, List<PlagiarismPairEntity> pairs) {
        Map<Long, ContestProblemEntity> problems = contestProblemMapper.selectList(new LambdaQueryWrapper<ContestProblemEntity>()
                        .eq(ContestProblemEntity::getContestId, contestId))
                .stream()
                .collect(Collectors.toMap(ContestProblemEntity::getId, Function.identity()));
        Set<Long> jobSubmissionIds = pairs.stream()
                .flatMap(pair -> List.of(pair.getLeftJobSubmissionId(), pair.getRightJobSubmissionId()).stream())
                .filter(Objects::nonNull)
                .collect(Collectors.toSet());
        Map<Long, PlagiarismJobSubmissionEntity> jobSubmissions = mapById(jobSubmissionIds,
                jobSubmissionMapper::selectBatchIds, PlagiarismJobSubmissionEntity::getId);
        return new Lookup(problems, jobSubmissions);
    }

    private PlagiarismJobResponse toJobResponse(PlagiarismJobEntity job) {
        return new PlagiarismJobResponse(job.getId(), job.getContestId(), job.getContestRunId(), job.getStatus(), job.getDetector(),
                value(job.getMinimumSimilarity(), DEFAULT_MINIMUM_SIMILARITY), Boolean.TRUE.equals(job.getIncludeAiAnalysis()),
                value(job.getTotalSubmissions()), value(job.getTotalPairs()), value(job.getHighRiskPairs()),
                job.getErrorMessage(), job.getCreatedBy(), job.getStartedAt(), job.getCompletedAt(),
                job.getCreatedAt(), job.getUpdatedAt());
    }

    private PlagiarismPairResponse toPairResponse(PlagiarismPairEntity pair, Lookup lookup, PlagiarismAiAnalysisEntity analysis) {
        ContestProblemEntity problem = lookup.problems().get(pair.getContestProblemId());
        PlagiarismJobSubmissionEntity left = lookup.jobSubmissions().get(pair.getLeftJobSubmissionId());
        PlagiarismJobSubmissionEntity right = lookup.jobSubmissions().get(pair.getRightJobSubmissionId());
        PlagiarismAiAnalysisEntity effectiveAnalysis = analysis == null ? latestAnalysis(pair.getId()) : analysis;
        return new PlagiarismPairResponse(pair.getId(), pair.getJobId(), pair.getContestId(), pair.getContestProblemId(),
                pair.getProblemId(), problem == null ? "" : problem.getLabel(), problem == null ? "" : problem.getDisplayTitle(),
                pair.getLanguage(), pair.getLeftSubmissionId(), pair.getRightSubmissionId(), pair.getLeftParticipantId(),
                pair.getRightParticipantId(), pair.getLeftUserId(), pair.getRightUserId(),
                left == null ? "" : left.getAccountSnapshot(), left == null ? "" : left.getDisplayNameSnapshot(),
                right == null ? "" : right.getAccountSnapshot(), right == null ? "" : right.getDisplayNameSnapshot(),
                value(pair.getSimilarity(), 0), value(pair.getMaximalSimilarity(), 0), value(pair.getMinimalSimilarity(), 0),
                value(pair.getMatchedTokens()), pair.getRiskLevel(), pair.getReviewStatus(), pair.getTeacherNote(),
                pair.getAiStatus(), effectiveAnalysis == null ? pair.getAiSummary() : summarize(effectiveAnalysis.getAnalysis(), 480),
                pair.getCreatedAt(), pair.getUpdatedAt());
    }

    private PlagiarismFragmentResponse toFragmentResponse(PlagiarismFragmentEntity fragment) {
        return new PlagiarismFragmentResponse(fragment.getId(), fragment.getPairId(), value(fragment.getSequenceNo()),
                value(fragment.getLeftStartToken()), value(fragment.getRightStartToken()), value(fragment.getTokenLength()),
                fragment.getLeftExcerpt(), fragment.getRightExcerpt());
    }

    private PlagiarismAiAnalysisEntity latestAnalysis(Long pairId) {
        return aiAnalysisMapper.selectOne(new LambdaQueryWrapper<PlagiarismAiAnalysisEntity>()
                .eq(PlagiarismAiAnalysisEntity::getPairId, pairId)
                .orderByDesc(PlagiarismAiAnalysisEntity::getCreatedAt)
                .orderByDesc(PlagiarismAiAnalysisEntity::getId)
                .last("LIMIT 1"));
    }

    private PlagiarismJobCreateRequest normalizeRequest(PlagiarismJobCreateRequest request) {
        double minimumSimilarity = request == null || request.minimumSimilarity() == null
                ? DEFAULT_MINIMUM_SIMILARITY
                : Math.max(0.1, Math.min(1.0, request.minimumSimilarity()));
        List<String> languages = request == null || request.languages() == null ? List.of() : request.languages().stream()
                .map(this::normalizeLanguage)
                .filter(Objects::nonNull)
                .distinct()
                .toList();
        List<Long> contestProblemIds = request == null || request.contestProblemIds() == null
                ? List.of()
                : request.contestProblemIds().stream().filter(Objects::nonNull).distinct().toList();
        boolean includeAi = request == null || request.includeAiAnalysis() == null || request.includeAiAnalysis();
        return new PlagiarismJobCreateRequest(contestProblemIds, languages, minimumSimilarity, includeAi);
    }

    private String optionsJson(PlagiarismJobCreateRequest request) {
        try {
            return objectMapper.writeValueAsString(request);
        } catch (JsonProcessingException ex) {
            return "{}";
        }
    }

    private PlagiarismJobCreateRequest requestFromJob(PlagiarismJobEntity job) {
        if (StringUtils.hasText(job.getOptionsJson())) {
            try {
                return normalizeRequest(objectMapper.readValue(job.getOptionsJson(), PlagiarismJobCreateRequest.class));
            } catch (IOException ignored) {
                // Fall through to persisted scalar fields if the stored options cannot be parsed.
            }
        }
        return normalizeRequest(new PlagiarismJobCreateRequest(List.of(), List.of(),
                job.getMinimumSimilarity(), job.getIncludeAiAnalysis()));
    }

    private PlagiarismRiskLevel riskLevel(double similarity) {
        if (similarity >= 0.9) {
            return PlagiarismRiskLevel.CRITICAL;
        }
        if (similarity >= 0.75) {
            return PlagiarismRiskLevel.HIGH;
        }
        if (similarity >= 0.55) {
            return PlagiarismRiskLevel.MEDIUM;
        }
        return PlagiarismRiskLevel.LOW;
    }

    private boolean isHighRisk(PlagiarismRiskLevel riskLevel) {
        return riskLevel == PlagiarismRiskLevel.HIGH || riskLevel == PlagiarismRiskLevel.CRITICAL;
    }

    private String pairKey(PlagiarismPairEntity pair) {
        long left = Math.min(pair.getLeftParticipantId(), pair.getRightParticipantId());
        long right = Math.max(pair.getLeftParticipantId(), pair.getRightParticipantId());
        return left + ":" + right;
    }

    private String normalizeLanguage(String language) {
        if (!StringUtils.hasText(language)) {
            return null;
        }
        String normalized = language.trim().toLowerCase(Locale.ROOT);
        if ("py".equals(normalized) || "python3".equals(normalized)) {
            return "python";
        }
        if ("c++".equals(normalized) || "cc".equals(normalized) || "cxx".equals(normalized)) {
            return "cpp";
        }
        return normalized;
    }

    private String normalizeNote(String note) {
        if (!StringUtils.hasText(note)) {
            return null;
        }
        return note.trim().length() <= 500 ? note.trim() : note.trim().substring(0, 500);
    }

    private String sha256(String value) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            byte[] bytes = digest.digest((value == null ? "" : value).getBytes(StandardCharsets.UTF_8));
            StringBuilder builder = new StringBuilder();
            for (byte current : bytes) {
                builder.append(String.format("%02x", current));
            }
            return builder.toString();
        } catch (NoSuchAlgorithmException ex) {
            throw new IllegalStateException("SHA-256 is not available", ex);
        }
    }

    private double round(double value) {
        return Math.round(value * 10_000.0) / 10_000.0;
    }

    private long normalizePage(long page) {
        return Math.max(page, 1);
    }

    private long normalizePageSize(long pageSize) {
        if (pageSize <= 0) {
            return 20;
        }
        return Math.min(pageSize, PAGE_SIZE_LIMIT);
    }

    private int value(Integer value) {
        return value == null ? 0 : value;
    }

    private double value(Double value, double fallback) {
        return value == null ? fallback : value;
    }

    private String string(Object value) {
        return value == null ? "" : String.valueOf(value);
    }

    private String decimal(double value) {
        return String.format(Locale.ROOT, "%.4f", value);
    }

    private String summarize(String value, int maxChars) {
        if (!StringUtils.hasText(value)) {
            return null;
        }
        String normalized = value.trim();
        return normalized.length() <= maxChars ? normalized : normalized.substring(0, maxChars);
    }

    private List<String> row(String... values) {
        List<String> row = new ArrayList<>(values.length);
        for (String value : values) {
            row.add(value == null ? "" : value);
        }
        return row;
    }

    private byte[] csv(List<List<String>> rows) {
        StringBuilder builder = new StringBuilder();
        for (List<String> row : rows) {
            for (int index = 0; index < row.size(); index++) {
                if (index > 0) {
                    builder.append(',');
                }
                builder.append(csvCell(row.get(index)));
            }
            builder.append("\r\n");
        }
        byte[] body = builder.toString().getBytes(StandardCharsets.UTF_8);
        byte[] result = new byte[UTF8_BOM.length + body.length];
        System.arraycopy(UTF8_BOM, 0, result, 0, UTF8_BOM.length);
        System.arraycopy(body, 0, result, UTF8_BOM.length, body.length);
        return result;
    }

    private String csvCell(String value) {
        String sanitized = sanitizeSpreadsheetValue(value);
        return "\"" + sanitized.replace("\"", "\"\"") + "\"";
    }

    private byte[] xlsx(String sheetName, List<List<String>> rows) {
        try (Workbook workbook = new XSSFWorkbook(); ByteArrayOutputStream output = new ByteArrayOutputStream()) {
            Sheet sheet = workbook.createSheet(sheetName);
            CellStyle textStyle = workbook.createCellStyle();
            textStyle.setDataFormat(workbook.createDataFormat().getFormat("@"));
            for (int rowIndex = 0; rowIndex < rows.size(); rowIndex++) {
                Row sheetRow = sheet.createRow(rowIndex);
                List<String> values = rows.get(rowIndex);
                for (int columnIndex = 0; columnIndex < values.size(); columnIndex++) {
                    Cell cell = sheetRow.createCell(columnIndex);
                    cell.setCellStyle(textStyle);
                    cell.setCellValue(sanitizeSpreadsheetValue(values.get(columnIndex)));
                }
            }
            workbook.write(output);
            return output.toByteArray();
        } catch (IOException ex) {
            throw new DomainException(ErrorCode.INTERNAL_ERROR, "Failed to generate plagiarism XLSX export");
        }
    }

    private String sanitizeSpreadsheetValue(String value) {
        if (value == null) {
            return "";
        }
        if (!value.isEmpty() && "=+-@".indexOf(value.charAt(0)) >= 0) {
            return "'" + value;
        }
        return value;
    }

    private String contentType(ContestExportFormat format) {
        return format == ContestExportFormat.XLSX
                ? "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet"
                : "text/csv;charset=utf-8";
    }

    private String extension(ContestExportFormat format) {
        return format == ContestExportFormat.XLSX ? ".xlsx" : ".csv";
    }

    private <T> Map<Long, T> mapById(Collection<Long> ids, Function<Collection<Long>, Collection<T>> loader,
                                     Function<T, Long> idGetter) {
        if (ids == null || ids.isEmpty()) {
            return Map.of();
        }
        return loader.apply(ids.stream().filter(Objects::nonNull).distinct().toList()).stream()
                .collect(Collectors.toMap(idGetter, Function.identity()));
    }

    private record GroupKey(Long contestProblemId, Long problemId, String language) {
    }

    private record SubmissionSelectionKey(Long contestProblemId, Long participantId, String language) {
    }

    private record RunData(
            Map<Long, PlagiarismJobSubmissionEntity> bySubmissionId,
            Map<GroupKey, List<PlagiarismDetector.DetectionSubmission>> groups,
            List<PlagiarismDetector.DetectionSubmission> detectionSubmissions
    ) {
    }

    private record Lookup(
            Map<Long, ContestProblemEntity> problems,
            Map<Long, PlagiarismJobSubmissionEntity> jobSubmissions
    ) {
    }
}
