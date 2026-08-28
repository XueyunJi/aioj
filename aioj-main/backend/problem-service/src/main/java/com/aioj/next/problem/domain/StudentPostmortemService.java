package com.aioj.next.problem.domain;

import com.aioj.next.common.api.PageResponse;
import com.aioj.next.common.error.DomainException;
import com.aioj.next.common.error.ErrorCode;
import com.aioj.next.common.security.Role;
import com.aioj.next.common.security.SecuritySupport;
import com.aioj.next.contract.ai.StudentPostmortemAnalysisRequest;
import com.aioj.next.contract.ai.StudentPostmortemAnalysisResponse;
import com.aioj.next.contract.ai.StudentPostmortemCodeReference;
import com.aioj.next.contract.ai.StudentPostmortemPracticeSuggestion;
import com.aioj.next.contract.ai.StudentPostmortemWeaknessConfirmRequest;
import com.aioj.next.contract.ai.StudentPostmortemWeaknessConfirmResponse;
import com.aioj.next.contract.ai.StudentPostmortemWeaknessSuggestion;
import com.aioj.next.contract.contest.ContestMode;
import com.aioj.next.contract.contest.ContestParticipantStatus;
import com.aioj.next.contract.contest.ContestPostmortemAiStatus;
import com.aioj.next.contract.contest.ContestPostmortemReportStatus;
import com.aioj.next.contract.contest.ContestStudentPostmortemReportResponse;
import com.aioj.next.contract.contest.ContestStudentPostmortemSummaryResponse;
import com.aioj.next.contract.contest.ContestStudentPostmortemWeaknessCandidateResponse;
import com.aioj.next.contract.contest.ContestStudentPostmortemWeaknessCandidateStatus;
import com.aioj.next.contract.submission.SubmissionStatus;
import com.aioj.next.problem.domain.postmortem.StudentPostmortemAiClient;
import com.aioj.next.problem.persistence.entity.ContestEntity;
import com.aioj.next.problem.persistence.entity.ContestParticipantEntity;
import com.aioj.next.problem.persistence.entity.ContestRunEntity;
import com.aioj.next.problem.persistence.entity.ContestRunProblemSnapshotEntity;
import com.aioj.next.problem.persistence.entity.ContestStudentPostmortemReportEntity;
import com.aioj.next.problem.persistence.entity.ContestStudentPostmortemWeaknessCandidateEntity;
import com.aioj.next.problem.persistence.entity.SubmissionCaseResultEntity;
import com.aioj.next.problem.persistence.entity.SubmissionEntity;
import com.aioj.next.problem.persistence.mapper.ContestMapper;
import com.aioj.next.problem.persistence.mapper.ContestParticipantMapper;
import com.aioj.next.problem.persistence.mapper.ContestRunMapper;
import com.aioj.next.problem.persistence.mapper.ContestRunProblemSnapshotMapper;
import com.aioj.next.problem.persistence.mapper.ContestStudentPostmortemReportMapper;
import com.aioj.next.problem.persistence.mapper.ContestStudentPostmortemWeaknessCandidateMapper;
import com.aioj.next.problem.persistence.mapper.SubmissionCaseResultMapper;
import com.aioj.next.problem.persistence.mapper.SubmissionMapper;
import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.util.StringUtils;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.EnumMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.function.BiConsumer;
import java.util.function.Function;
import java.util.stream.Collectors;

@Service
public class StudentPostmortemService {
    private static final int ACM_CODE_EXCERPT_CHARS = 5000;
    private static final Set<SubmissionStatus> UNFINISHED_STATUSES = Set.of(
            SubmissionStatus.QUEUED,
            SubmissionStatus.RUNNING
    );
    private static final Set<SubmissionStatus> ERROR_STATUSES = Set.of(
            SubmissionStatus.WRONG_ANSWER,
            SubmissionStatus.COMPILE_ERROR,
            SubmissionStatus.RUNTIME_ERROR,
            SubmissionStatus.TIME_LIMIT_EXCEEDED,
            SubmissionStatus.MEMORY_LIMIT_EXCEEDED,
            SubmissionStatus.OUTPUT_LIMIT_EXCEEDED
    );

    private final ContestMapper contestMapper;
    private final ContestRunMapper contestRunMapper;
    private final ContestParticipantMapper participantMapper;
    private final ContestRunProblemSnapshotMapper problemSnapshotMapper;
    private final SubmissionMapper submissionMapper;
    private final SubmissionCaseResultMapper caseResultMapper;
    private final ContestStudentPostmortemReportMapper reportMapper;
    private final ContestStudentPostmortemWeaknessCandidateMapper candidateMapper;
    private final StudentPostmortemAiClient aiClient;
    private final ObjectMapper objectMapper;
    private final ContestProblemVisibilityService visibilityService;

    public StudentPostmortemService(ContestMapper contestMapper,
                                    ContestRunMapper contestRunMapper,
                                    ContestParticipantMapper participantMapper,
                                    ContestRunProblemSnapshotMapper problemSnapshotMapper,
                                    SubmissionMapper submissionMapper,
                                    SubmissionCaseResultMapper caseResultMapper,
                                    ContestStudentPostmortemReportMapper reportMapper,
                                    ContestStudentPostmortemWeaknessCandidateMapper candidateMapper,
                                    StudentPostmortemAiClient aiClient,
                                    ObjectMapper objectMapper,
                                    ContestProblemVisibilityService visibilityService) {
        this.contestMapper = contestMapper;
        this.contestRunMapper = contestRunMapper;
        this.participantMapper = participantMapper;
        this.problemSnapshotMapper = problemSnapshotMapper;
        this.submissionMapper = submissionMapper;
        this.caseResultMapper = caseResultMapper;
        this.reportMapper = reportMapper;
        this.candidateMapper = candidateMapper;
        this.aiClient = aiClient;
        this.objectMapper = objectMapper;
        this.visibilityService = visibilityService;
    }

    public PageResponse<ContestStudentPostmortemReportResponse> listMyReports(Long contestId, Long runId, long page, long pageSize) {
        Long userId = SecuritySupport.currentUserId();
        requireReportableContext(contestId, runId);
        Page<ContestStudentPostmortemReportEntity> result = reportMapper.selectPage(new Page<>(normalizePage(page), normalizePageSize(pageSize)),
                new LambdaQueryWrapper<ContestStudentPostmortemReportEntity>()
                        .eq(ContestStudentPostmortemReportEntity::getContestId, contestId)
                        .eq(ContestStudentPostmortemReportEntity::getContestRunId, runId)
                        .eq(ContestStudentPostmortemReportEntity::getUserId, userId)
                        .orderByDesc(ContestStudentPostmortemReportEntity::getCreatedAt)
                        .orderByDesc(ContestStudentPostmortemReportEntity::getId));
        return new PageResponse<>(result.getRecords().stream().map(this::toResponse).toList(),
                result.getTotal(), result.getCurrent(), result.getSize());
    }

    public ContestStudentPostmortemReportResponse getReport(Long contestId, Long runId, Long reportId) {
        ContestStudentPostmortemReportEntity report = requireReport(contestId, runId, reportId);
        if (!canViewReport(report)) {
            throw new DomainException(ErrorCode.FORBIDDEN, "Cannot view this student postmortem report");
        }
        return toResponse(report);
    }

    @Transactional
    public ContestStudentPostmortemReportResponse createMyReport(Long contestId, Long runId) {
        Long userId = SecuritySupport.currentUserId();
        ContestEntity contest = requireContest(contestId);
        ContestRunEntity run = requireReportableContext(contestId, runId);
        ContestParticipantEntity participant = requireActiveParticipant(contestId, runId, userId);
        return createReportForParticipant(contest, run, participant, SecuritySupport.currentUserId());
    }

    @Transactional
    public ContestStudentPostmortemReportResponse createParticipantReport(Long contestId, Long runId, Long participantId) {
        ContestEntity contest = requireManagedContest(contestId);
        ContestRunEntity run = requireReportableContext(contestId, runId);
        ContestParticipantEntity participant = participantMapper.selectOne(new LambdaQueryWrapper<ContestParticipantEntity>()
                .eq(ContestParticipantEntity::getId, participantId)
                .eq(ContestParticipantEntity::getContestId, contestId)
                .eq(ContestParticipantEntity::getContestRunId, runId)
                .eq(ContestParticipantEntity::getStatus, ContestParticipantStatus.ACTIVE));
        if (participant == null) {
            throw new DomainException(ErrorCode.NOT_FOUND, "Contest participant not found");
        }
        return createReportForParticipant(contest, run, participant, SecuritySupport.currentUserId());
    }

    @Transactional
    public List<ContestStudentPostmortemReportResponse> createParticipantReports(Long contestId, Long runId,
                                                                                 List<Long> participantIds,
                                                                                 int limit,
                                                                                 BiConsumer<Integer, String> progressCallback) {
        ContestEntity contest = requireManagedContest(contestId);
        ContestRunEntity run = requireReportableContext(contestId, runId);
        int effectiveLimit = Math.max(1, Math.min(limit <= 0 ? 100 : limit, 100));
        LambdaQueryWrapper<ContestParticipantEntity> query = new LambdaQueryWrapper<ContestParticipantEntity>()
                .eq(ContestParticipantEntity::getContestId, contestId)
                .eq(ContestParticipantEntity::getContestRunId, runId)
                .eq(ContestParticipantEntity::getStatus, ContestParticipantStatus.ACTIVE)
                .orderByAsc(ContestParticipantEntity::getAccountSnapshot)
                .orderByAsc(ContestParticipantEntity::getId)
                .last("LIMIT " + effectiveLimit);
        if (participantIds != null && !participantIds.isEmpty()) {
            query.in(ContestParticipantEntity::getId, participantIds);
        }
        List<ContestParticipantEntity> participants = participantMapper.selectList(query);
        List<ContestStudentPostmortemReportResponse> reports = new ArrayList<>();
        int index = 0;
        for (ContestParticipantEntity participant : participants) {
            index++;
            if (progressCallback != null) {
                progressCallback.accept(index, participant.getAccountSnapshot());
            }
            reports.add(createReportForParticipant(contest, run, participant, SecuritySupport.currentUserId()));
        }
        return reports;
    }

    public PageResponse<ContestStudentPostmortemSummaryResponse> summaries(Long contestId, Long runId, long page, long pageSize) {
        requireManagedContest(contestId);
        requireRun(contestId, runId);
        Page<ContestParticipantEntity> result = participantMapper.selectPage(new Page<>(normalizePage(page), normalizePageSize(pageSize)),
                new LambdaQueryWrapper<ContestParticipantEntity>()
                        .eq(ContestParticipantEntity::getContestId, contestId)
                        .eq(ContestParticipantEntity::getContestRunId, runId)
                        .orderByAsc(ContestParticipantEntity::getAccountSnapshot)
                        .orderByAsc(ContestParticipantEntity::getId));
        List<ContestStudentPostmortemSummaryResponse> records = result.getRecords().stream()
                .map(participant -> summary(contestId, runId, participant))
                .toList();
        return new PageResponse<>(records, result.getTotal(), result.getCurrent(), result.getSize());
    }

    @Transactional
    public ContestStudentPostmortemReportResponse retryAi(Long contestId, Long runId, Long reportId) {
        ContestEntity contest = requireContest(contestId);
        ContestRunEntity run = requireRun(contestId, runId);
        ContestStudentPostmortemReportEntity report = requireReport(contestId, runId, reportId);
        if (!canManageContest(contest) && !Objects.equals(report.getUserId(), SecuritySupport.currentUserId())) {
            throw new DomainException(ErrorCode.FORBIDDEN, "Cannot retry this student postmortem report");
        }
        if (!StringUtils.hasText(report.getStatisticsJson())) {
            throw new DomainException(ErrorCode.BAD_REQUEST, "Student postmortem report has no deterministic statistics");
        }
        StudentPostmortemStatistics statistics = parseStatistics(report.getStatisticsJson());
        Instant now = Instant.now();
        report.setStatus(ContestPostmortemReportStatus.RUNNING);
        report.setAiStatus(ContestPostmortemAiStatus.RUNNING);
        report.setErrorMessage(null);
        report.setUpdatedAt(now);
        reportMapper.updateById(report);
        candidateMapper.delete(new LambdaQueryWrapper<ContestStudentPostmortemWeaknessCandidateEntity>()
                .eq(ContestStudentPostmortemWeaknessCandidateEntity::getReportId, report.getId())
                .eq(ContestStudentPostmortemWeaknessCandidateEntity::getStatus,
                        ContestStudentPostmortemWeaknessCandidateStatus.PENDING));
        return toResponse(runAiAnalysis(report, contest, run, statistics, report.getStatisticsJson()));
    }

    @Transactional
    public ContestStudentPostmortemWeaknessCandidateResponse acceptCandidate(Long contestId, Long runId, Long reportId, Long candidateId) {
        ContestStudentPostmortemReportEntity report = requireOwnedReport(contestId, runId, reportId);
        ContestStudentPostmortemWeaknessCandidateEntity candidate = requireCandidate(report, candidateId);
        if (candidate.getStatus() != ContestStudentPostmortemWeaknessCandidateStatus.PENDING) {
            throw new DomainException(ErrorCode.BAD_REQUEST, "Weakness candidate has already been decided");
        }
        StudentPostmortemWeaknessConfirmResponse confirmation = aiClient.confirmWeakness(new StudentPostmortemWeaknessConfirmRequest(
                report.getUserId(),
                contestId,
                runId,
                report.getContestParticipantId(),
                reportId,
                candidateId,
                candidate.getKnowledgeNode(),
                candidate.getSymptom(),
                parseStringList(candidate.getTagsJson()),
                parseStringList(candidate.getEvidenceJson()),
                candidate.getConfidence() == null ? 0.0 : candidate.getConfidence().doubleValue()
        ));
        Instant now = Instant.now();
        candidate.setStatus(ContestStudentPostmortemWeaknessCandidateStatus.ACCEPTED);
        candidate.setMemoryId(confirmation.memoryId());
        candidate.setWeaknessId(confirmation.weaknessId());
        candidate.setDecidedAt(now);
        candidate.setUpdatedAt(now);
        candidateMapper.updateById(candidate);
        return toCandidateResponse(candidate);
    }

    @Transactional
    public ContestStudentPostmortemWeaknessCandidateResponse rejectCandidate(Long contestId, Long runId, Long reportId, Long candidateId) {
        ContestStudentPostmortemReportEntity report = requireOwnedReport(contestId, runId, reportId);
        ContestStudentPostmortemWeaknessCandidateEntity candidate = requireCandidate(report, candidateId);
        if (candidate.getStatus() != ContestStudentPostmortemWeaknessCandidateStatus.PENDING) {
            throw new DomainException(ErrorCode.BAD_REQUEST, "Weakness candidate has already been decided");
        }
        Instant now = Instant.now();
        candidate.setStatus(ContestStudentPostmortemWeaknessCandidateStatus.REJECTED);
        candidate.setDecidedAt(now);
        candidate.setUpdatedAt(now);
        candidateMapper.updateById(candidate);
        return toCandidateResponse(candidate);
    }

    private ContestStudentPostmortemReportResponse createReportForParticipant(ContestEntity contest, ContestRunEntity run,
                                                                              ContestParticipantEntity participant, Long generatedBy) {
        assertStudentSubmissionsFinished(contest.getId(), run.getId(), participant.getUserId());
        StudentPostmortemStatistics statistics = buildStatistics(contest, run, participant);
        String statisticsJson = toJson(statistics);
        Instant now = Instant.now();
        ContestStudentPostmortemReportEntity report = new ContestStudentPostmortemReportEntity();
        report.setContestId(contest.getId());
        report.setContestRunId(run.getId());
        report.setContestParticipantId(participant.getId());
        report.setUserId(participant.getUserId());
        report.setStatus(ContestPostmortemReportStatus.RUNNING);
        report.setAiStatus(ContestPostmortemAiStatus.RUNNING);
        report.setGeneratedBy(generatedBy);
        report.setStatisticsJson(statisticsJson);
        report.setPromptTokens(0L);
        report.setCompletionTokens(0L);
        report.setCreatedAt(now);
        report.setUpdatedAt(now);
        reportMapper.insert(report);
        return toResponse(runAiAnalysis(report, contest, run, statistics, statisticsJson));
    }

    private ContestStudentPostmortemReportEntity runAiAnalysis(ContestStudentPostmortemReportEntity report, ContestEntity contest,
                                                               ContestRunEntity run, StudentPostmortemStatistics statistics,
                                                               String statisticsJson) {
        try {
            ContestMode mode = effectiveMode(contest, run);
            String aiStatisticsJson = mode == ContestMode.ACM ? toJson(acmAiStatistics(statistics)) : statisticsJson;
            String aiSummaryText = buildSummaryText(statistics, mode);
            List<StudentPostmortemCodeReference> codeReferences = mode == ContestMode.ACM
                    ? representativeCodeReferences(contest.getId(), run.getId(), report.getContestParticipantId(), report.getUserId(), statistics)
                    : List.of();
            StudentPostmortemAnalysisResponse analysis = aiClient.analyze(new StudentPostmortemAnalysisRequest(
                    SecuritySupport.currentUserId(),
                    report.getUserId(),
                    contest.getId(),
                    run.getId(),
                    report.getContestParticipantId(),
                    statistics.contestTitle(),
                    statistics.runTitle(),
                    statistics.mode(),
                    aiStatisticsJson,
                    aiSummaryText,
                    codeReferences
            ));
            Instant now = Instant.now();
            report.setStatus(ContestPostmortemReportStatus.COMPLETED);
            report.setAiStatus(analysis.success() ? ContestPostmortemAiStatus.COMPLETED : ContestPostmortemAiStatus.FAILED);
            report.setAiMarkdown(analysis.markdown());
            report.setPracticeSuggestionsJson(toJson(analysis.practiceSuggestions() == null ? List.of() : analysis.practiceSuggestions()));
            report.setAiProvider(analysis.provider());
            report.setAiModel(analysis.model());
            report.setPromptTokens(analysis.promptTokens());
            report.setCompletionTokens(analysis.completionTokens());
            report.setErrorMessage(analysis.success() ? null : summarize(analysis.errorMessage(), 480));
            report.setCompletedAt(now);
            report.setUpdatedAt(now);
            reportMapper.updateById(report);
            replacePendingCandidates(report, analysis.weaknessCandidates() == null ? List.of() : analysis.weaknessCandidates());
            return report;
        } catch (DomainException ex) {
            Instant now = Instant.now();
            report.setStatus(ContestPostmortemReportStatus.COMPLETED);
            report.setAiStatus(ContestPostmortemAiStatus.FAILED);
            report.setPracticeSuggestionsJson("[]");
            report.setErrorMessage(summarize(ex.getMessage(), 480));
            report.setCompletedAt(now);
            report.setUpdatedAt(now);
            reportMapper.updateById(report);
            return report;
        }
    }

    private void replacePendingCandidates(ContestStudentPostmortemReportEntity report,
                                          List<StudentPostmortemWeaknessSuggestion> suggestions) {
        candidateMapper.delete(new LambdaQueryWrapper<ContestStudentPostmortemWeaknessCandidateEntity>()
                .eq(ContestStudentPostmortemWeaknessCandidateEntity::getReportId, report.getId())
                .eq(ContestStudentPostmortemWeaknessCandidateEntity::getStatus,
                        ContestStudentPostmortemWeaknessCandidateStatus.PENDING));
        Instant now = Instant.now();
        for (StudentPostmortemWeaknessSuggestion suggestion : suggestions.stream().limit(8).toList()) {
            String node = summarize(normalizeValue(suggestion.knowledgeNode()), 160);
            String symptom = summarize(normalizeValue(suggestion.symptom()), 500);
            if (!StringUtils.hasText(node) || !StringUtils.hasText(symptom)) {
                continue;
            }
            ContestStudentPostmortemWeaknessCandidateEntity candidate = new ContestStudentPostmortemWeaknessCandidateEntity();
            candidate.setReportId(report.getId());
            candidate.setContestId(report.getContestId());
            candidate.setContestRunId(report.getContestRunId());
            candidate.setContestParticipantId(report.getContestParticipantId());
            candidate.setUserId(report.getUserId());
            candidate.setStatus(ContestStudentPostmortemWeaknessCandidateStatus.PENDING);
            candidate.setKnowledgeNode(node);
            candidate.setSymptom(symptom);
            candidate.setTagsJson(toJson(safeList(suggestion.tags())));
            candidate.setEvidenceJson(toJson(safeList(suggestion.evidence())));
            candidate.setConfidence(BigDecimal.valueOf(Math.min(1.0, Math.max(0.0, suggestion.confidence())))
                    .setScale(4, RoundingMode.HALF_UP));
            candidate.setCreatedAt(now);
            candidate.setUpdatedAt(now);
            candidateMapper.insert(candidate);
        }
    }

    private StudentPostmortemStatistics buildStatistics(ContestEntity contest, ContestRunEntity run, ContestParticipantEntity participant) {
        ContestMode mode = effectiveMode(contest, run);
        List<ContestRunProblemSnapshotEntity> problems = problemSnapshotMapper.selectList(new LambdaQueryWrapper<ContestRunProblemSnapshotEntity>()
                .eq(ContestRunProblemSnapshotEntity::getContestId, contest.getId())
                .eq(ContestRunProblemSnapshotEntity::getContestRunId, run.getId())
                .orderByAsc(ContestRunProblemSnapshotEntity::getSortOrder)
                .orderByAsc(ContestRunProblemSnapshotEntity::getId));
        List<SubmissionEntity> submissions = submissions(contest.getId(), run.getId(), participant.getId(), participant.getUserId());
        // Private problems that stayed unpublished after the run must not leak through the report.
        Set<Long> hiddenContestProblemIds = visibilityService.hiddenContestProblemIdsForRun(run, Instant.now());
        if (!hiddenContestProblemIds.isEmpty()) {
            problems = problems.stream()
                    .filter(problem -> !hiddenContestProblemIds.contains(problem.getContestProblemId()))
                    .toList();
            submissions = submissions.stream()
                    .filter(submission -> submission.getContestProblemId() == null
                            || !hiddenContestProblemIds.contains(submission.getContestProblemId()))
                    .toList();
        }
        List<SubmissionEntity> visibleSubmissions = submissions;
        List<ContestRunProblemSnapshotEntity> visibleProblems = problems;
        List<SubmissionCaseResultEntity> caseResults = caseResults(contest.getId(), visibleSubmissions.stream().map(SubmissionEntity::getId).toList());
        Map<Long, List<SubmissionEntity>> submissionsByProblem = visibleSubmissions.stream()
                .filter(submission -> submission.getContestProblemId() != null)
                .collect(Collectors.groupingBy(SubmissionEntity::getContestProblemId));
        Map<Long, List<SubmissionCaseResultEntity>> casesBySubmission = caseResults.stream()
                .collect(Collectors.groupingBy(SubmissionCaseResultEntity::getSubmissionId));
        Map<Long, List<SubmissionCaseResultEntity>> casesByProblem = caseResults.stream()
                .filter(result -> result.getContestProblemId() != null)
                .collect(Collectors.groupingBy(SubmissionCaseResultEntity::getContestProblemId));
        List<StudentProblemStats> problemStats = visibleProblems.stream()
                .map(problem -> problemStats(mode, problem, submissionsByProblem.getOrDefault(problem.getContestProblemId(), List.of()),
                        casesByProblem.getOrDefault(problem.getContestProblemId(), List.of())))
                .toList();
        List<StudentSubmissionSummary> timeline = visibleSubmissions.stream()
                .map(submission -> submissionSummary(submission, visibleProblems, casesBySubmission.getOrDefault(submission.getId(), List.of())))
                .toList();
        BigDecimal totalScore = problemStats.stream().map(StudentProblemStats::bestScore).reduce(BigDecimal.ZERO, BigDecimal::add);
        BigDecimal maxScore = problemStats.stream().map(StudentProblemStats::maxScore).reduce(BigDecimal.ZERO, BigDecimal::add);
        return new StudentPostmortemStatistics(
                contest.getId(),
                run.getId(),
                participant.getId(),
                participant.getUserId(),
                effectiveTitle(contest, run),
                run.getTitle(),
                String.valueOf(mode),
                run.getRunKind() == null ? null : run.getRunKind().name(),
                run.getStartAt(),
                run.getEndAt(),
                participant.getAccountSnapshot(),
                participant.getDisplayNameSnapshot(),
                submissions.size(),
                submissions.stream().filter(submission -> submission.getStatus() == SubmissionStatus.ACCEPTED).count(),
                totalScore,
                maxScore,
                distribution(submissions, submission -> safeLower(submission.getLanguage())),
                enumDistribution(submissions, SubmissionEntity::getStatus, SubmissionStatus.class),
                enumDistribution(submissions.stream().filter(submission -> ERROR_STATUSES.contains(submission.getStatus())).toList(),
                        SubmissionEntity::getStatus, SubmissionStatus.class),
                problemStats,
                timeline,
                weaknessSeeds(problemStats, mode)
        );
    }

    private List<SubmissionEntity> submissions(Long contestId, Long runId, Long participantId, Long userId) {
        return submissionMapper.selectList(new LambdaQueryWrapper<SubmissionEntity>()
                .eq(SubmissionEntity::getContestId, contestId)
                .eq(SubmissionEntity::getContestRunId, runId)
                .eq(SubmissionEntity::getUserId, userId)
                .eq(participantId != null, SubmissionEntity::getContestParticipantId, participantId)
                .orderByAsc(SubmissionEntity::getSubmittedAtContestMillis)
                .orderByAsc(SubmissionEntity::getId));
    }

    private List<SubmissionCaseResultEntity> caseResults(Long contestId, List<Long> submissionIds) {
        if (submissionIds.isEmpty()) {
            return List.of();
        }
        return caseResultMapper.selectList(new LambdaQueryWrapper<SubmissionCaseResultEntity>()
                .eq(SubmissionCaseResultEntity::getContestId, contestId)
                .in(SubmissionCaseResultEntity::getSubmissionId, submissionIds));
    }

    private StudentProblemStats problemStats(ContestMode mode, ContestRunProblemSnapshotEntity problem, List<SubmissionEntity> submissions,
                                             List<SubmissionCaseResultEntity> caseResults) {
        SubmissionEntity best = representativeSubmission(mode, submissions);
        BigDecimal bestScore = best == null ? BigDecimal.ZERO : decimal(best.getScore());
        BigDecimal maxScore = submissions.stream()
                .map(SubmissionEntity::getMaxScore)
                .filter(Objects::nonNull)
                .max(BigDecimal::compareTo)
                .orElse(problem.getScore() == null ? BigDecimal.ZERO : BigDecimal.valueOf(problem.getScore()));
        return new StudentProblemStats(
                problem.getContestProblemId(),
                problem.getProblemId(),
                problem.getLabel(),
                problem.getDisplayTitle(),
                problem.getDifficulty() == null ? null : problem.getDifficulty().name(),
                tags(problem.getTags()),
                submissions.size(),
                best == null ? null : best.getId(),
                best == null ? null : best.getStatus(),
                bestScore,
                maxScore,
                caseSummary(caseResults)
        );
    }

    private StudentSubmissionSummary submissionSummary(SubmissionEntity submission, List<ContestRunProblemSnapshotEntity> problems,
                                                       List<SubmissionCaseResultEntity> cases) {
        ContestRunProblemSnapshotEntity problem = problems.stream()
                .filter(item -> Objects.equals(item.getContestProblemId(), submission.getContestProblemId()))
                .findFirst()
                .orElse(null);
        return new StudentSubmissionSummary(
                submission.getId(),
                submission.getContestProblemId(),
                problem == null ? null : problem.getLabel(),
                problem == null ? null : problem.getDisplayTitle(),
                submission.getLanguage(),
                submission.getStatus(),
                decimal(submission.getScore()),
                decimal(submission.getMaxScore()),
                submission.getSubmittedAtContestMillis(),
                submission.getTimeMillis(),
                submission.getMemoryKb(),
                submission.getCreatedAt(),
                submission.getJudgedAt(),
                caseSummary(cases)
        );
    }

    private CaseScoreSummary caseSummary(List<SubmissionCaseResultEntity> cases) {
        if (cases == null || cases.isEmpty()) {
            return new CaseScoreSummary(0, Map.of(), Map.of(), BigDecimal.ZERO, BigDecimal.ZERO, List.of());
        }
        BigDecimal score = cases.stream().map(SubmissionCaseResultEntity::getScore).filter(Objects::nonNull)
                .reduce(BigDecimal.ZERO, BigDecimal::add);
        BigDecimal maxScore = cases.stream().map(SubmissionCaseResultEntity::getMaxScore).filter(Objects::nonNull)
                .reduce(BigDecimal.ZERO, BigDecimal::add);
        List<CaseResultSummary> details = cases.stream()
                .sorted(Comparator.comparing(result -> result.getCaseIndex() == null ? Integer.MAX_VALUE : result.getCaseIndex()))
                .limit(120)
                .map(result -> new CaseResultSummary(result.getCaseIndex(), result.getCaseName(), result.getSubtaskKey(),
                        result.getStatus(), decimal(result.getScore()), decimal(result.getMaxScore()), result.getTimeMillis(),
                        result.getMemoryKb(), summarize(result.getMessage(), 160)))
                .toList();
        return new CaseScoreSummary(
                cases.size(),
                enumDistribution(cases, SubmissionCaseResultEntity::getStatus, SubmissionStatus.class),
                cases.stream()
                        .map(result -> StringUtils.hasText(result.getSubtaskKey()) ? result.getSubtaskKey() : "none")
                        .collect(Collectors.groupingBy(Function.identity(), LinkedHashMap::new, Collectors.counting())),
                score,
                maxScore,
                details
        );
    }

    private SubmissionEntity representativeSubmission(ContestMode mode, List<SubmissionEntity> submissions) {
        if (submissions == null || submissions.isEmpty()) {
            return null;
        }
        if (mode == ContestMode.ACM) {
            return submissions.stream()
                    .filter(submission -> submission.getStatus() == SubmissionStatus.ACCEPTED)
                    .max(this::compareSubmissionTime)
                    .orElseGet(() -> submissions.stream().max(this::compareSubmissionTime).orElse(null));
        }
        return submissions.stream()
                .max(Comparator.comparing((SubmissionEntity submission) -> decimal(submission.getScore()))
                        .thenComparing(submission -> submission.getSubmittedAtContestMillis() == null ? Long.MAX_VALUE : -submission.getSubmittedAtContestMillis()))
                .orElse(null);
    }

    private int compareSubmissionTime(SubmissionEntity left, SubmissionEntity right) {
        long leftMillis = left.getSubmittedAtContestMillis() == null ? Long.MIN_VALUE : left.getSubmittedAtContestMillis();
        long rightMillis = right.getSubmittedAtContestMillis() == null ? Long.MIN_VALUE : right.getSubmittedAtContestMillis();
        int byMillis = Long.compare(leftMillis, rightMillis);
        if (byMillis != 0) {
            return byMillis;
        }
        return Long.compare(left.getId() == null ? Long.MIN_VALUE : left.getId(), right.getId() == null ? Long.MIN_VALUE : right.getId());
    }

    private List<String> weaknessSeeds(List<StudentProblemStats> problems, ContestMode mode) {
        List<String> seeds = new ArrayList<>();
        for (StudentProblemStats problem : problems) {
            if (problem.submissionCount() == 0) {
                continue;
            }
            boolean notAccepted = problem.bestStatus() != SubmissionStatus.ACCEPTED;
            if (mode == ContestMode.ACM) {
                if (notAccepted || highErrorCount(problem.caseSummary().statusDistribution()) > 0) {
                    seeds.add("%s %s：最佳状态 %s，提交 %d 次，标签 %s".formatted(
                            safe(problem.label()), safe(problem.title()), problem.bestStatus(), problem.submissionCount(),
                            problem.tags().isEmpty() ? "未标注" : String.join(", ", problem.tags())));
                }
                continue;
            }
            boolean lowScore = problem.maxScore().compareTo(BigDecimal.ZERO) > 0
                    && problem.bestScore().divide(problem.maxScore(), 4, RoundingMode.HALF_UP).doubleValue() < 0.7;
            if (notAccepted || lowScore || highErrorCount(problem.caseSummary().statusDistribution()) > 0) {
                seeds.add("%s %s：最佳状态 %s，得分 %s/%s，标签 %s".formatted(
                        safe(problem.label()), safe(problem.title()), problem.bestStatus(),
                        problem.bestScore().stripTrailingZeros().toPlainString(),
                        problem.maxScore().stripTrailingZeros().toPlainString(),
                        problem.tags().isEmpty() ? "未标注" : String.join(", ", problem.tags())));
            }
        }
        return seeds.stream().limit(8).toList();
    }

    private long highErrorCount(Map<String, Long> distribution) {
        return distribution.entrySet().stream()
                .filter(entry -> ERROR_STATUSES.stream().anyMatch(status -> status.name().equals(entry.getKey())))
                .mapToLong(Map.Entry::getValue)
                .sum();
    }

    private ContestStudentPostmortemSummaryResponse summary(Long contestId, Long runId, ContestParticipantEntity participant) {
        ContestStudentPostmortemReportEntity report = reportMapper.selectOne(new LambdaQueryWrapper<ContestStudentPostmortemReportEntity>()
                .eq(ContestStudentPostmortemReportEntity::getContestId, contestId)
                .eq(ContestStudentPostmortemReportEntity::getContestRunId, runId)
                .eq(ContestStudentPostmortemReportEntity::getContestParticipantId, participant.getId())
                .orderByDesc(ContestStudentPostmortemReportEntity::getCreatedAt)
                .orderByDesc(ContestStudentPostmortemReportEntity::getId)
                .last("LIMIT 1"));
        int candidateCount = 0;
        int pendingCount = 0;
        StudentPostmortemStatistics statistics = null;
        if (report != null) {
            candidateCount = Math.toIntExact(candidateMapper.selectCount(new LambdaQueryWrapper<ContestStudentPostmortemWeaknessCandidateEntity>()
                    .eq(ContestStudentPostmortemWeaknessCandidateEntity::getReportId, report.getId())));
            pendingCount = Math.toIntExact(candidateMapper.selectCount(new LambdaQueryWrapper<ContestStudentPostmortemWeaknessCandidateEntity>()
                    .eq(ContestStudentPostmortemWeaknessCandidateEntity::getReportId, report.getId())
                    .eq(ContestStudentPostmortemWeaknessCandidateEntity::getStatus,
                            ContestStudentPostmortemWeaknessCandidateStatus.PENDING)));
            statistics = parseStatisticsOrNull(report.getStatisticsJson());
        }
        return new ContestStudentPostmortemSummaryResponse(
                participant.getId(),
                participant.getUserId(),
                participant.getAccountSnapshot(),
                participant.getDisplayNameSnapshot(),
                participant.getEmailSnapshot(),
                report == null ? null : report.getId(),
                report == null ? null : report.getStatus(),
                report == null ? null : report.getAiStatus(),
                statistics == null ? 0 : statistics.submissionCount(),
                statistics == null ? 0 : Math.toIntExact(statistics.acceptedCount()),
                statistics == null ? BigDecimal.ZERO : statistics.totalScore(),
                statistics == null ? BigDecimal.ZERO : statistics.maxScore(),
                candidateCount,
                pendingCount,
                report == null ? null : report.getCreatedAt()
        );
    }

    private ContestRunEntity requireReportableContext(Long contestId, Long runId) {
        ContestRunEntity run = requireRun(contestId, runId);
        if (ContestRunStatePolicy.isDeleted(run)) {
            throw new DomainException(ErrorCode.NOT_FOUND, "Contest run not found");
        }
        if (run.getEndAt() == null || Instant.now().isBefore(run.getEndAt())) {
            throw new DomainException(ErrorCode.BAD_REQUEST, "Student postmortem is available after the run ends");
        }
        return run;
    }

    private ContestEntity requireContest(Long contestId) {
        ContestEntity contest = contestMapper.selectById(contestId);
        if (contest == null || contest.getDeletedAt() != null) {
            throw new DomainException(ErrorCode.NOT_FOUND, "Contest not found");
        }
        return contest;
    }

    private ContestEntity requireManagedContest(Long contestId) {
        ContestEntity contest = requireContest(contestId);
        if (canManageContest(contest)) {
            return contest;
        }
        throw new DomainException(ErrorCode.FORBIDDEN, "Cannot manage student postmortem reports");
    }

    private boolean canManageContest(ContestEntity contest) {
        return SecuritySupport.hasRole(Role.ADMIN)
                || (SecuritySupport.hasRole(Role.TEACHER) && Objects.equals(contest.getOwnerUserId(), SecuritySupport.currentUserId()));
    }

    private ContestRunEntity requireRun(Long contestId, Long runId) {
        ContestRunEntity run = contestRunMapper.selectOne(new LambdaQueryWrapper<ContestRunEntity>()
                .eq(ContestRunEntity::getId, runId)
                .eq(ContestRunEntity::getContestId, contestId)
                .isNull(ContestRunEntity::getDeletedAt));
        if (run == null) {
            throw new DomainException(ErrorCode.NOT_FOUND, "Contest run not found");
        }
        return run;
    }

    private ContestParticipantEntity requireActiveParticipant(Long contestId, Long runId, Long userId) {
        ContestParticipantEntity participant = participantMapper.selectOne(new LambdaQueryWrapper<ContestParticipantEntity>()
                .eq(ContestParticipantEntity::getContestId, contestId)
                .eq(ContestParticipantEntity::getContestRunId, runId)
                .eq(ContestParticipantEntity::getUserId, userId)
                .eq(ContestParticipantEntity::getStatus, ContestParticipantStatus.ACTIVE));
        if (participant == null) {
            throw new DomainException(ErrorCode.FORBIDDEN, "Only participants can generate student postmortem reports");
        }
        return participant;
    }

    private void assertStudentSubmissionsFinished(Long contestId, Long runId, Long userId) {
        long unfinished = submissionMapper.selectCount(new LambdaQueryWrapper<SubmissionEntity>()
                .eq(SubmissionEntity::getContestId, contestId)
                .eq(SubmissionEntity::getContestRunId, runId)
                .eq(SubmissionEntity::getUserId, userId)
                .in(SubmissionEntity::getStatus, UNFINISHED_STATUSES));
        if (unfinished > 0) {
            throw new DomainException(ErrorCode.BAD_REQUEST, "Your contest submissions are still being judged");
        }
    }

    private ContestStudentPostmortemReportEntity requireReport(Long contestId, Long runId, Long reportId) {
        ContestStudentPostmortemReportEntity report = reportMapper.selectOne(new LambdaQueryWrapper<ContestStudentPostmortemReportEntity>()
                .eq(ContestStudentPostmortemReportEntity::getId, reportId)
                .eq(ContestStudentPostmortemReportEntity::getContestId, contestId)
                .eq(ContestStudentPostmortemReportEntity::getContestRunId, runId));
        if (report == null) {
            throw new DomainException(ErrorCode.NOT_FOUND, "Student postmortem report not found");
        }
        return report;
    }

    private ContestStudentPostmortemReportEntity requireOwnedReport(Long contestId, Long runId, Long reportId) {
        ContestStudentPostmortemReportEntity report = requireReport(contestId, runId, reportId);
        if (!Objects.equals(report.getUserId(), SecuritySupport.currentUserId())) {
            throw new DomainException(ErrorCode.FORBIDDEN, "Cannot update another student's postmortem candidate");
        }
        return report;
    }

    private ContestStudentPostmortemWeaknessCandidateEntity requireCandidate(ContestStudentPostmortemReportEntity report, Long candidateId) {
        ContestStudentPostmortemWeaknessCandidateEntity candidate = candidateMapper.selectOne(new LambdaQueryWrapper<ContestStudentPostmortemWeaknessCandidateEntity>()
                .eq(ContestStudentPostmortemWeaknessCandidateEntity::getId, candidateId)
                .eq(ContestStudentPostmortemWeaknessCandidateEntity::getReportId, report.getId())
                .eq(ContestStudentPostmortemWeaknessCandidateEntity::getUserId, report.getUserId()));
        if (candidate == null) {
            throw new DomainException(ErrorCode.NOT_FOUND, "Weakness candidate not found");
        }
        return candidate;
    }

    private boolean canViewReport(ContestStudentPostmortemReportEntity report) {
        if (Objects.equals(report.getUserId(), SecuritySupport.currentUserId())) {
            return true;
        }
        ContestEntity contest = contestMapper.selectById(report.getContestId());
        return contest != null && canManageContest(contest);
    }

    private ContestMode effectiveMode(ContestEntity contest, ContestRunEntity run) {
        return run.getModeSnapshot() == null ? contest.getMode() : run.getModeSnapshot();
    }

    private String effectiveTitle(ContestEntity contest, ContestRunEntity run) {
        return StringUtils.hasText(run.getContestTitleSnapshot()) ? run.getContestTitleSnapshot() : contest.getTitle();
    }

    private ContestStudentPostmortemReportResponse toResponse(ContestStudentPostmortemReportEntity report) {
        List<ContestStudentPostmortemWeaknessCandidateResponse> candidates = candidateMapper.selectList(new LambdaQueryWrapper<ContestStudentPostmortemWeaknessCandidateEntity>()
                        .eq(ContestStudentPostmortemWeaknessCandidateEntity::getReportId, report.getId())
                        .orderByAsc(ContestStudentPostmortemWeaknessCandidateEntity::getCreatedAt)
                        .orderByAsc(ContestStudentPostmortemWeaknessCandidateEntity::getId))
                .stream()
                .map(this::toCandidateResponse)
                .toList();
        return new ContestStudentPostmortemReportResponse(
                report.getId(), report.getContestId(), report.getContestRunId(), report.getContestParticipantId(),
                report.getUserId(), report.getStatus(), report.getAiStatus(), report.getGeneratedBy(),
                report.getStatisticsJson(), report.getAiMarkdown(), report.getPracticeSuggestionsJson(),
                report.getAiProvider(), report.getAiModel(), value(report.getPromptTokens()), value(report.getCompletionTokens()),
                report.getErrorMessage(), report.getCreatedAt(), report.getUpdatedAt(), report.getCompletedAt(),
                candidates
        );
    }

    private ContestStudentPostmortemWeaknessCandidateResponse toCandidateResponse(ContestStudentPostmortemWeaknessCandidateEntity candidate) {
        return new ContestStudentPostmortemWeaknessCandidateResponse(
                candidate.getId(), candidate.getReportId(), candidate.getContestId(), candidate.getContestRunId(),
                candidate.getContestParticipantId(), candidate.getUserId(), candidate.getStatus(),
                candidate.getKnowledgeNode(), candidate.getSymptom(), parseStringList(candidate.getTagsJson()),
                parseStringList(candidate.getEvidenceJson()), candidate.getConfidence(), candidate.getMemoryId(),
                candidate.getWeaknessId(), candidate.getCreatedAt(), candidate.getUpdatedAt(), candidate.getDecidedAt()
        );
    }

    private Map<String, Object> acmAiStatistics(StudentPostmortemStatistics statistics) {
        Map<String, Object> value = new LinkedHashMap<>();
        value.put("contestId", statistics.contestId());
        value.put("contestRunId", statistics.contestRunId());
        value.put("contestParticipantId", statistics.contestParticipantId());
        value.put("userId", statistics.userId());
        value.put("contestTitle", statistics.contestTitle());
        value.put("runTitle", statistics.runTitle());
        value.put("mode", statistics.mode());
        value.put("runKind", statistics.runKind());
        value.put("startAt", statistics.startAt());
        value.put("endAt", statistics.endAt());
        value.put("accountSnapshot", statistics.accountSnapshot());
        value.put("displayNameSnapshot", statistics.displayNameSnapshot());
        value.put("submissionCount", statistics.submissionCount());
        value.put("acceptedCount", statistics.acceptedCount());
        value.put("languageDistribution", statistics.languageDistribution());
        value.put("statusDistribution", statistics.statusDistribution());
        value.put("errorDistribution", statistics.errorDistribution());
        value.put("weaknessSeeds", statistics.weaknessSeeds());
        value.put("problems", statistics.problems().stream().map(problem -> {
            Map<String, Object> item = new LinkedHashMap<>();
            item.put("contestProblemId", problem.contestProblemId());
            item.put("problemId", problem.problemId());
            item.put("label", problem.label());
            item.put("title", problem.title());
            item.put("difficulty", problem.difficulty());
            item.put("tags", problem.tags());
            item.put("submissionCount", problem.submissionCount());
            item.put("bestSubmissionId", problem.bestSubmissionId());
            item.put("bestStatus", problem.bestStatus());
            return item;
        }).toList());
        value.put("submissionTimeline", statistics.submissionTimeline().stream().map(submission -> {
            Map<String, Object> item = new LinkedHashMap<>();
            item.put("submissionId", submission.submissionId());
            item.put("contestProblemId", submission.contestProblemId());
            item.put("problemLabel", submission.problemLabel());
            item.put("problemTitle", submission.problemTitle());
            item.put("language", submission.language());
            item.put("status", submission.status());
            item.put("submittedAtContestMillis", submission.submittedAtContestMillis());
            item.put("timeMillis", submission.timeMillis());
            item.put("memoryKb", submission.memoryKb());
            item.put("createdAt", submission.createdAt());
            item.put("judgedAt", submission.judgedAt());
            return item;
        }).toList());
        return value;
    }

    private List<StudentPostmortemCodeReference> representativeCodeReferences(Long contestId, Long runId, Long participantId,
                                                                               Long userId, StudentPostmortemStatistics statistics) {
        List<SubmissionEntity> submissions = submissionMapper.selectList(new LambdaQueryWrapper<SubmissionEntity>()
                .eq(SubmissionEntity::getContestId, contestId)
                .eq(SubmissionEntity::getContestRunId, runId)
                .eq(SubmissionEntity::getUserId, userId)
                .eq(participantId != null, SubmissionEntity::getContestParticipantId, participantId)
                .orderByAsc(SubmissionEntity::getSubmittedAtContestMillis)
                .orderByAsc(SubmissionEntity::getId));
        Map<Long, List<SubmissionEntity>> byProblem = submissions.stream()
                .filter(submission -> submission.getContestProblemId() != null)
                .collect(Collectors.groupingBy(SubmissionEntity::getContestProblemId));
        return statistics.problems().stream()
                .map(problem -> {
                    SubmissionEntity submission = representativeSubmission(ContestMode.ACM,
                            byProblem.getOrDefault(problem.contestProblemId(), List.of()));
                    if (submission == null) {
                        return null;
                    }
                    String code = submission.getCode();
                    return new StudentPostmortemCodeReference(
                            submission.getId(),
                            submission.getContestProblemId(),
                            problem.label(),
                            problem.title(),
                            submission.getLanguage(),
                            submission.getStatus() == null ? null : submission.getStatus().name(),
                            submission.getSubmittedAtContestMillis(),
                            code == null ? 0 : code.length(),
                            summarizeCode(code)
                    );
                })
                .filter(Objects::nonNull)
                .toList();
    }

    private String summarizeCode(String code) {
        if (!StringUtils.hasText(code)) {
            return "";
        }
        String normalized = code.trim();
        if (normalized.length() <= ACM_CODE_EXCERPT_CHARS) {
            return normalized;
        }
        return normalized.substring(0, ACM_CODE_EXCERPT_CHARS) + "\n... [truncated]";
    }

    private String buildSummaryText(StudentPostmortemStatistics statistics, ContestMode mode) {
        if (mode == ContestMode.ACM) {
            String problems = statistics.problems().stream()
                    .map(problem -> "- %s %s：提交 %d，最佳状态 %s，标签 %s".formatted(
                            safe(problem.label()), safe(problem.title()), problem.submissionCount(), problem.bestStatus(),
                            problem.tags()))
                    .collect(Collectors.joining("\n"));
            long failedProblems = statistics.problems().stream()
                    .filter(problem -> problem.submissionCount() > 0 && problem.bestStatus() != SubmissionStatus.ACCEPTED)
                    .count();
            return """
                    学生：%s (#%s)
                    比赛：%s / %s
                    赛制：ACM
                    提交数：%d
                    通过题数：%d
                    未通过题数：%d
                    状态分布：%s
                    错误分布：%s
                    薄弱种子：%s
                    题目表现：
                    %s
                    """.formatted(
                    statistics.displayNameSnapshot(), statistics.userId(), statistics.contestTitle(), statistics.runTitle(),
                    statistics.submissionCount(), statistics.acceptedCount(), failedProblems,
                    statistics.statusDistribution(), statistics.errorDistribution(), statistics.weaknessSeeds(), problems);
        }
        String problems = statistics.problems().stream()
                .map(problem -> "- %s %s：提交 %d，最佳 %s，得分 %s/%s，标签 %s".formatted(
                        safe(problem.label()), safe(problem.title()), problem.submissionCount(), problem.bestStatus(),
                        problem.bestScore(), problem.maxScore(), problem.tags()))
                .collect(Collectors.joining("\n"));
        return """
                学生：%s (#%s)
                比赛：%s / %s
                赛制：%s
                提交数：%d
                AC 数：%d
                总分：%s/%s
                状态分布：%s
                错误分布：%s
                薄弱种子：%s
                题目表现：
                %s
                """.formatted(
                statistics.displayNameSnapshot(), statistics.userId(), statistics.contestTitle(), statistics.runTitle(),
                statistics.mode(), statistics.submissionCount(), statistics.acceptedCount(), statistics.totalScore(),
                statistics.maxScore(), statistics.statusDistribution(), statistics.errorDistribution(),
                statistics.weaknessSeeds(), problems);
    }

    private String toJson(Object value) {
        try {
            return objectMapper.writeValueAsString(value);
        } catch (JsonProcessingException ex) {
            throw new DomainException(ErrorCode.INTERNAL_ERROR, "Could not serialize student postmortem data");
        }
    }

    private StudentPostmortemStatistics parseStatistics(String json) {
        try {
            return objectMapper.readValue(json, StudentPostmortemStatistics.class);
        } catch (Exception ex) {
            throw new DomainException(ErrorCode.BAD_REQUEST, "Student postmortem statistics could not be parsed");
        }
    }

    private StudentPostmortemStatistics parseStatisticsOrNull(String json) {
        try {
            return StringUtils.hasText(json) ? objectMapper.readValue(json, StudentPostmortemStatistics.class) : null;
        } catch (Exception ignored) {
            return null;
        }
    }

    private List<String> parseStringList(String json) {
        if (!StringUtils.hasText(json)) {
            return List.of();
        }
        try {
            return objectMapper.readValue(json,
                    objectMapper.getTypeFactory().constructCollectionType(List.class, String.class));
        } catch (Exception ignored) {
            return List.of();
        }
    }

    private List<String> safeList(List<String> values) {
        if (values == null) {
            return List.of();
        }
        return values.stream().filter(StringUtils::hasText).map(value -> summarize(value.trim(), 240)).distinct().limit(12).toList();
    }

    private List<String> tags(String value) {
        if (!StringUtils.hasText(value)) {
            return List.of();
        }
        String normalized = value.trim();
        if (normalized.startsWith("[") && normalized.endsWith("]")) {
            try {
                return objectMapper.readValue(normalized,
                        objectMapper.getTypeFactory().constructCollectionType(List.class, String.class));
            } catch (Exception ignored) {
                return List.of(normalized);
            }
        }
        return List.of(normalized.split("[,，\\s]+")).stream()
                .map(String::trim)
                .filter(StringUtils::hasText)
                .distinct()
                .toList();
    }

    private <T, E extends Enum<E>> Map<String, Long> enumDistribution(List<T> values, Function<T, E> classifier,
                                                                      Class<E> enumType) {
        Map<E, Long> counts = new EnumMap<>(enumType);
        for (T value : values) {
            E key = classifier.apply(value);
            if (key != null) {
                counts.merge(key, 1L, Long::sum);
            }
        }
        Map<String, Long> result = new LinkedHashMap<>();
        for (E key : enumType.getEnumConstants()) {
            Long count = counts.get(key);
            if (count != null && count > 0) {
                result.put(key.name(), count);
            }
        }
        return result;
    }

    private <T> Map<String, Long> distribution(List<T> values, Function<T, String> classifier) {
        return values.stream()
                .map(classifier)
                .filter(StringUtils::hasText)
                .collect(Collectors.groupingBy(Function.identity(), LinkedHashMap::new, Collectors.counting()));
    }

    private BigDecimal decimal(BigDecimal value) {
        return value == null ? BigDecimal.ZERO : value;
    }

    private String safeLower(String value) {
        return StringUtils.hasText(value) ? value.trim().toLowerCase(Locale.ROOT) : "";
    }

    private String normalizeValue(String value) {
        return value == null ? "" : value.replace("\r", " ").replace("\n", " ").trim();
    }

    private String safe(String value) {
        return value == null ? "" : value;
    }

    private long value(Long value) {
        return value == null ? 0L : value;
    }

    private long normalizePage(long page) {
        return Math.max(1, page);
    }

    private long normalizePageSize(long pageSize) {
        return Math.min(100, Math.max(1, pageSize));
    }

    private String summarize(String value, int maxLength) {
        if (!StringUtils.hasText(value)) {
            return null;
        }
        String trimmed = value.trim();
        return trimmed.length() <= maxLength ? trimmed : trimmed.substring(0, maxLength);
    }

    public record StudentPostmortemStatistics(
            Long contestId,
            Long contestRunId,
            Long contestParticipantId,
            Long userId,
            String contestTitle,
            String runTitle,
            String mode,
            String runKind,
            Instant startAt,
            Instant endAt,
            String accountSnapshot,
            String displayNameSnapshot,
            int submissionCount,
            long acceptedCount,
            BigDecimal totalScore,
            BigDecimal maxScore,
            Map<String, Long> languageDistribution,
            Map<String, Long> statusDistribution,
            Map<String, Long> errorDistribution,
            List<StudentProblemStats> problems,
            List<StudentSubmissionSummary> submissionTimeline,
            List<String> weaknessSeeds
    ) {}

    public record StudentProblemStats(
            Long contestProblemId,
            Long problemId,
            String label,
            String title,
            String difficulty,
            List<String> tags,
            int submissionCount,
            Long bestSubmissionId,
            SubmissionStatus bestStatus,
            BigDecimal bestScore,
            BigDecimal maxScore,
            CaseScoreSummary caseSummary
    ) {}

    public record StudentSubmissionSummary(
            Long submissionId,
            Long contestProblemId,
            String problemLabel,
            String problemTitle,
            String language,
            SubmissionStatus status,
            BigDecimal score,
            BigDecimal maxScore,
            Long submittedAtContestMillis,
            Long timeMillis,
            Long memoryKb,
            Instant createdAt,
            Instant judgedAt,
            CaseScoreSummary caseSummary
    ) {}

    public record CaseScoreSummary(
            int caseResultCount,
            Map<String, Long> statusDistribution,
            Map<String, Long> subtaskDistribution,
            BigDecimal totalScore,
            BigDecimal totalMaxScore,
            List<CaseResultSummary> cases
    ) {}

    public record CaseResultSummary(
            Integer caseIndex,
            String caseName,
            String subtaskKey,
            SubmissionStatus status,
            BigDecimal score,
            BigDecimal maxScore,
            Long timeMillis,
            Long memoryKb,
            String message
    ) {}
}
