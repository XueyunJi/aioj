package com.aioj.next.problem.domain;

import com.aioj.next.common.api.PageResponse;
import com.aioj.next.common.error.DomainException;
import com.aioj.next.common.error.ErrorCode;
import com.aioj.next.common.security.Role;
import com.aioj.next.common.security.SecuritySupport;
import com.aioj.next.contract.ai.ContestPostmortemAnalysisRequest;
import com.aioj.next.contract.ai.ContestPostmortemAnalysisResponse;
import com.aioj.next.contract.contest.ContestMode;
import com.aioj.next.contract.contest.ContestParticipantStatus;
import com.aioj.next.contract.contest.ContestPostmortemAiStatus;
import com.aioj.next.contract.contest.ContestPostmortemReportResponse;
import com.aioj.next.contract.contest.ContestPostmortemReportStatus;
import com.aioj.next.contract.contest.ContestRunStatus;
import com.aioj.next.contract.contest.PlagiarismJobStatus;
import com.aioj.next.contract.contest.PlagiarismReviewStatus;
import com.aioj.next.contract.contest.PlagiarismRiskLevel;
import com.aioj.next.contract.submission.SubmissionStatus;
import com.aioj.next.problem.domain.postmortem.ContestPostmortemAiAnalysisClient;
import com.aioj.next.problem.persistence.entity.ContestEntity;
import com.aioj.next.problem.persistence.entity.ContestParticipantEntity;
import com.aioj.next.problem.persistence.entity.ContestPostmortemReportEntity;
import com.aioj.next.problem.persistence.entity.ContestRunEntity;
import com.aioj.next.problem.persistence.entity.ContestRunProblemSnapshotEntity;
import com.aioj.next.problem.persistence.entity.PlagiarismJobEntity;
import com.aioj.next.problem.persistence.entity.PlagiarismPairEntity;
import com.aioj.next.problem.persistence.entity.SubmissionCaseResultEntity;
import com.aioj.next.problem.persistence.entity.SubmissionEntity;
import com.aioj.next.problem.persistence.mapper.ContestMapper;
import com.aioj.next.problem.persistence.mapper.ContestParticipantMapper;
import com.aioj.next.problem.persistence.mapper.ContestPostmortemReportMapper;
import com.aioj.next.problem.persistence.mapper.ContestRunMapper;
import com.aioj.next.problem.persistence.mapper.ContestRunProblemSnapshotMapper;
import com.aioj.next.problem.persistence.mapper.PlagiarismJobMapper;
import com.aioj.next.problem.persistence.mapper.PlagiarismPairMapper;
import com.aioj.next.problem.persistence.mapper.SubmissionCaseResultMapper;
import com.aioj.next.problem.persistence.mapper.SubmissionMapper;
import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.EnumMap;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.function.Function;
import java.util.stream.Collectors;

@Service
public class ContestPostmortemService {
    private static final Set<SubmissionStatus> UNFINISHED_STATUSES = Set.of(
            SubmissionStatus.QUEUED,
            SubmissionStatus.RUNNING,
            SubmissionStatus.SYSTEM_ERROR
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
    private final PlagiarismJobMapper plagiarismJobMapper;
    private final PlagiarismPairMapper plagiarismPairMapper;
    private final ContestPostmortemReportMapper reportMapper;
    private final ContestPostmortemAiAnalysisClient aiAnalysisClient;
    private final ObjectMapper objectMapper;

    public ContestPostmortemService(ContestMapper contestMapper,
                                    ContestRunMapper contestRunMapper,
                                    ContestParticipantMapper participantMapper,
                                    ContestRunProblemSnapshotMapper problemSnapshotMapper,
                                    SubmissionMapper submissionMapper,
                                    SubmissionCaseResultMapper caseResultMapper,
                                    PlagiarismJobMapper plagiarismJobMapper,
                                    PlagiarismPairMapper plagiarismPairMapper,
                                    ContestPostmortemReportMapper reportMapper,
                                    ContestPostmortemAiAnalysisClient aiAnalysisClient,
                                    ObjectMapper objectMapper) {
        this.contestMapper = contestMapper;
        this.contestRunMapper = contestRunMapper;
        this.participantMapper = participantMapper;
        this.problemSnapshotMapper = problemSnapshotMapper;
        this.submissionMapper = submissionMapper;
        this.caseResultMapper = caseResultMapper;
        this.plagiarismJobMapper = plagiarismJobMapper;
        this.plagiarismPairMapper = plagiarismPairMapper;
        this.reportMapper = reportMapper;
        this.aiAnalysisClient = aiAnalysisClient;
        this.objectMapper = objectMapper;
    }

    public PageResponse<ContestPostmortemReportResponse> listReports(Long contestId, Long runId, long page, long pageSize) {
        requireManagedContest(contestId);
        requireRun(contestId, runId);
        Page<ContestPostmortemReportEntity> result = reportMapper.selectPage(new Page<>(normalizePage(page), normalizePageSize(pageSize)),
                new LambdaQueryWrapper<ContestPostmortemReportEntity>()
                        .eq(ContestPostmortemReportEntity::getContestId, contestId)
                        .eq(ContestPostmortemReportEntity::getContestRunId, runId)
                        .orderByDesc(ContestPostmortemReportEntity::getCreatedAt)
                        .orderByDesc(ContestPostmortemReportEntity::getId));
        return new PageResponse<>(result.getRecords().stream().map(this::toResponse).toList(),
                result.getTotal(), result.getCurrent(), result.getSize());
    }

    public ContestPostmortemReportResponse getReport(Long contestId, Long runId, Long reportId) {
        requireManagedContest(contestId);
        requireRun(contestId, runId);
        return toResponse(requireReport(contestId, runId, reportId));
    }

    public ContestPostmortemReportResponse createReport(Long contestId, Long runId) {
        ContestEntity contest = requireManagedContest(contestId);
        ContestRunEntity run = requireRun(contestId, runId);
        assertReportableRun(contestId, run);
        PostmortemStatistics statistics = buildStatistics(contest, run);
        String statisticsJson = toJson(statistics);
        Instant now = Instant.now();
        ContestPostmortemReportEntity report = new ContestPostmortemReportEntity();
        report.setContestId(contestId);
        report.setContestRunId(runId);
        report.setStatus(ContestPostmortemReportStatus.RUNNING);
        report.setAiStatus(ContestPostmortemAiStatus.RUNNING);
        report.setGeneratedBy(SecuritySupport.currentUserId());
        report.setStatisticsJson(statisticsJson);
        report.setPromptTokens(0L);
        report.setCompletionTokens(0L);
        report.setCreatedAt(now);
        report.setUpdatedAt(now);
        reportMapper.insert(report);
        return toResponse(runAiAnalysis(report, contest, run, statistics, statisticsJson));
    }

    public ContestPostmortemReportResponse retryAi(Long contestId, Long runId, Long reportId) {
        ContestEntity contest = requireManagedContest(contestId);
        ContestRunEntity run = requireRun(contestId, runId);
        ContestPostmortemReportEntity report = requireReport(contestId, runId, reportId);
        if (!StringUtils.hasText(report.getStatisticsJson())) {
            throw new DomainException(ErrorCode.BAD_REQUEST, "Postmortem report has no deterministic statistics to analyze");
        }
        PostmortemStatistics statistics = parseStatistics(report.getStatisticsJson());
        Instant now = Instant.now();
        report.setStatus(ContestPostmortemReportStatus.RUNNING);
        report.setAiStatus(ContestPostmortemAiStatus.RUNNING);
        report.setErrorMessage(null);
        report.setUpdatedAt(now);
        reportMapper.updateById(report);
        return toResponse(runAiAnalysis(report, contest, run, statistics, report.getStatisticsJson()));
    }

    private ContestPostmortemReportEntity runAiAnalysis(ContestPostmortemReportEntity report, ContestEntity contest,
                                                        ContestRunEntity run, PostmortemStatistics statistics,
                                                        String statisticsJson) {
        try {
            ContestPostmortemAnalysisResponse analysis = aiAnalysisClient.analyze(new ContestPostmortemAnalysisRequest(
                    SecuritySupport.currentUserId(),
                    contest.getId(),
                    run.getId(),
                    statistics.contestTitle(),
                    statistics.runTitle(),
                    statistics.mode(),
                    statisticsJson,
                    buildSummaryText(statistics)
            ));
            Instant now = Instant.now();
            report.setStatus(ContestPostmortemReportStatus.COMPLETED);
            report.setAiStatus(analysis.success() ? ContestPostmortemAiStatus.COMPLETED : ContestPostmortemAiStatus.FAILED);
            report.setAiMarkdown(analysis.markdown());
            report.setAiProvider(analysis.provider());
            report.setAiModel(analysis.model());
            report.setPromptTokens(analysis.promptTokens());
            report.setCompletionTokens(analysis.completionTokens());
            report.setErrorMessage(analysis.success() ? null : summarize(analysis.errorMessage(), 480));
            report.setCompletedAt(now);
            report.setUpdatedAt(now);
            reportMapper.updateById(report);
            return report;
        } catch (DomainException ex) {
            Instant now = Instant.now();
            report.setStatus(ContestPostmortemReportStatus.COMPLETED);
            report.setAiStatus(ContestPostmortemAiStatus.FAILED);
            report.setErrorMessage(summarize(ex.getMessage(), 480));
            report.setCompletedAt(now);
            report.setUpdatedAt(now);
            reportMapper.updateById(report);
            return report;
        }
    }

    private PostmortemStatistics buildStatistics(ContestEntity contest, ContestRunEntity run) {
        List<ContestParticipantEntity> participants = participantMapper.selectList(new LambdaQueryWrapper<ContestParticipantEntity>()
                .select(ContestParticipantEntity::getId, ContestParticipantEntity::getContestId,
                        ContestParticipantEntity::getContestRunId, ContestParticipantEntity::getUserId,
                        ContestParticipantEntity::getStatus, ContestParticipantEntity::getAccountSnapshot,
                        ContestParticipantEntity::getDisplayNameSnapshot, ContestParticipantEntity::getGroupNameSnapshot)
                .eq(ContestParticipantEntity::getContestId, contest.getId())
                .eq(ContestParticipantEntity::getContestRunId, run.getId()));
        List<ContestRunProblemSnapshotEntity> problems = problemSnapshotMapper.selectList(new LambdaQueryWrapper<ContestRunProblemSnapshotEntity>()
                .select(ContestRunProblemSnapshotEntity::getId, ContestRunProblemSnapshotEntity::getContestId,
                        ContestRunProblemSnapshotEntity::getContestRunId, ContestRunProblemSnapshotEntity::getContestProblemId,
                        ContestRunProblemSnapshotEntity::getProblemId, ContestRunProblemSnapshotEntity::getLabel,
                        ContestRunProblemSnapshotEntity::getDisplayTitle, ContestRunProblemSnapshotEntity::getTags,
                        ContestRunProblemSnapshotEntity::getDifficulty, ContestRunProblemSnapshotEntity::getScore,
                        ContestRunProblemSnapshotEntity::getSortOrder)
                .eq(ContestRunProblemSnapshotEntity::getContestId, contest.getId())
                .eq(ContestRunProblemSnapshotEntity::getContestRunId, run.getId())
                .orderByAsc(ContestRunProblemSnapshotEntity::getSortOrder)
                .orderByAsc(ContestRunProblemSnapshotEntity::getId));
        List<SubmissionEntity> submissions = submissions(contest.getId(), run.getId());
        Map<Long, List<SubmissionEntity>> submissionsByProblem = submissions.stream()
                .filter(submission -> submission.getContestProblemId() != null)
                .collect(Collectors.groupingBy(SubmissionEntity::getContestProblemId));
        Map<Long, ContestRunProblemSnapshotEntity> problemsByContestProblemId = problems.stream()
                .filter(problem -> problem.getContestProblemId() != null)
                .collect(Collectors.toMap(ContestRunProblemSnapshotEntity::getContestProblemId, Function.identity(), (left, right) -> left));
        List<SubmissionCaseResultEntity> caseResults = caseResults(contest.getId(), submissions.stream().map(SubmissionEntity::getId).toList());
        Map<Long, List<SubmissionCaseResultEntity>> caseResultsByProblem = caseResults.stream()
                .filter(result -> result.getContestProblemId() != null)
                .collect(Collectors.groupingBy(SubmissionCaseResultEntity::getContestProblemId));

        List<ProblemPostmortemStats> problemStats = problems.stream()
                .map(problem -> problemStats(problem, submissionsByProblem.getOrDefault(problem.getContestProblemId(), List.of()),
                        caseResultsByProblem.getOrDefault(problem.getContestProblemId(), List.of())))
                .toList();
        PlagiarismPostmortemStats plagiarismStats = plagiarismStats(contest.getId(), run.getId());
        List<String> weaknessCandidates = weaknessCandidates(problemStats);
        return new PostmortemStatistics(
                contest.getId(),
                run.getId(),
                effectiveTitle(contest, run),
                run.getTitle(),
                String.valueOf(effectiveMode(contest, run)),
                run.getRunKind() == null ? null : run.getRunKind().name(),
                run.getStartAt(),
                run.getEndAt(),
                participants.size(),
                participants.stream().filter(participant -> participant.getStatus() == ContestParticipantStatus.ACTIVE).count(),
                submissions.size(),
                distribution(submissions, submission -> safeLower(submission.getLanguage())),
                enumDistribution(submissions, SubmissionEntity::getStatus, SubmissionStatus.class),
                enumDistribution(submissions.stream().filter(submission -> ERROR_STATUSES.contains(submission.getStatus())).toList(),
                        SubmissionEntity::getStatus, SubmissionStatus.class),
                problemStats,
                caseScoreSummary(caseResults, problemsByContestProblemId),
                plagiarismStats,
                weaknessCandidates
        );
    }

    private List<SubmissionEntity> submissions(Long contestId, Long runId) {
        return submissionMapper.selectList(new LambdaQueryWrapper<SubmissionEntity>()
                .select(SubmissionEntity::getId, SubmissionEntity::getContestId, SubmissionEntity::getContestRunId,
                        SubmissionEntity::getContestProblemId, SubmissionEntity::getContestParticipantId,
                        SubmissionEntity::getUserId, SubmissionEntity::getSubmittedAtContestMillis,
                        SubmissionEntity::getLanguage, SubmissionEntity::getStatus, SubmissionEntity::getJudgeMessage,
                        SubmissionEntity::getTimeMillis, SubmissionEntity::getMemoryKb, SubmissionEntity::getScore,
                        SubmissionEntity::getMaxScore, SubmissionEntity::getCreatedAt, SubmissionEntity::getJudgedAt)
                .eq(SubmissionEntity::getContestId, contestId)
                .eq(SubmissionEntity::getContestRunId, runId)
                .orderByAsc(SubmissionEntity::getSubmittedAtContestMillis)
                .orderByAsc(SubmissionEntity::getId));
    }

    private List<SubmissionCaseResultEntity> caseResults(Long contestId, List<Long> submissionIds) {
        if (submissionIds.isEmpty()) {
            return List.of();
        }
        return caseResultMapper.selectList(new LambdaQueryWrapper<SubmissionCaseResultEntity>()
                .select(SubmissionCaseResultEntity::getId, SubmissionCaseResultEntity::getSubmissionId,
                        SubmissionCaseResultEntity::getContestId, SubmissionCaseResultEntity::getContestProblemId,
                        SubmissionCaseResultEntity::getContestParticipantId, SubmissionCaseResultEntity::getCaseIndex,
                        SubmissionCaseResultEntity::getCaseName, SubmissionCaseResultEntity::getSubtaskKey,
                        SubmissionCaseResultEntity::getStatus, SubmissionCaseResultEntity::getScore,
                        SubmissionCaseResultEntity::getMaxScore, SubmissionCaseResultEntity::getTimeMillis,
                        SubmissionCaseResultEntity::getMemoryKb, SubmissionCaseResultEntity::getCreatedAt)
                .eq(SubmissionCaseResultEntity::getContestId, contestId)
                .in(SubmissionCaseResultEntity::getSubmissionId, submissionIds));
    }

    private ProblemPostmortemStats problemStats(ContestRunProblemSnapshotEntity problem, List<SubmissionEntity> submissions,
                                                List<SubmissionCaseResultEntity> caseResults) {
        Set<Long> attemptedParticipants = submissions.stream()
                .map(SubmissionEntity::getContestParticipantId)
                .filter(Objects::nonNull)
                .collect(Collectors.toSet());
        Set<Long> acceptedParticipants = submissions.stream()
                .filter(submission -> submission.getStatus() == SubmissionStatus.ACCEPTED)
                .map(SubmissionEntity::getContestParticipantId)
                .filter(Objects::nonNull)
                .collect(Collectors.toSet());
        Long firstAcceptedAt = submissions.stream()
                .filter(submission -> submission.getStatus() == SubmissionStatus.ACCEPTED)
                .map(SubmissionEntity::getSubmittedAtContestMillis)
                .filter(Objects::nonNull)
                .min(Long::compareTo)
                .orElse(null);
        BigDecimal bestScore = submissions.stream()
                .map(SubmissionEntity::getScore)
                .filter(Objects::nonNull)
                .max(BigDecimal::compareTo)
                .orElse(BigDecimal.ZERO);
        BigDecimal maxScore = submissions.stream()
                .map(SubmissionEntity::getMaxScore)
                .filter(Objects::nonNull)
                .max(BigDecimal::compareTo)
                .orElse(problem.getScore() == null ? BigDecimal.ZERO : BigDecimal.valueOf(problem.getScore()));
        return new ProblemPostmortemStats(
                problem.getContestProblemId(),
                problem.getProblemId(),
                problem.getLabel(),
                problem.getDisplayTitle(),
                problem.getDifficulty() == null ? null : problem.getDifficulty().name(),
                tags(problem.getTags()),
                submissions.size(),
                attemptedParticipants.size(),
                acceptedParticipants.size(),
                percent(acceptedParticipants.size(), Math.max(1, attemptedParticipants.size())),
                firstAcceptedAt,
                enumDistribution(submissions, SubmissionEntity::getStatus, SubmissionStatus.class),
                caseScoreSummary(caseResults, Map.of(problem.getContestProblemId(), problem)),
                decimal(bestScore),
                decimal(maxScore)
        );
    }

    private PlagiarismPostmortemStats plagiarismStats(Long contestId, Long runId) {
        List<PlagiarismJobEntity> jobs = plagiarismJobMapper.selectList(new LambdaQueryWrapper<PlagiarismJobEntity>()
                .select(PlagiarismJobEntity::getId, PlagiarismJobEntity::getContestId, PlagiarismJobEntity::getContestRunId,
                        PlagiarismJobEntity::getStatus, PlagiarismJobEntity::getTotalSubmissions,
                        PlagiarismJobEntity::getTotalPairs, PlagiarismJobEntity::getHighRiskPairs,
                        PlagiarismJobEntity::getCreatedAt, PlagiarismJobEntity::getCompletedAt)
                .eq(PlagiarismJobEntity::getContestId, contestId)
                .eq(PlagiarismJobEntity::getContestRunId, runId));
        if (jobs.isEmpty()) {
            return new PlagiarismPostmortemStats(0, 0, 0, 0, Map.of(), Map.of());
        }
        List<Long> jobIds = jobs.stream().map(PlagiarismJobEntity::getId).toList();
        List<PlagiarismPairEntity> pairs = plagiarismPairMapper.selectList(new LambdaQueryWrapper<PlagiarismPairEntity>()
                .select(PlagiarismPairEntity::getId, PlagiarismPairEntity::getJobId, PlagiarismPairEntity::getContestId,
                        PlagiarismPairEntity::getRiskLevel, PlagiarismPairEntity::getReviewStatus)
                .eq(PlagiarismPairEntity::getContestId, contestId)
                .in(PlagiarismPairEntity::getJobId, jobIds));
        long completedJobs = jobs.stream().filter(job -> job.getStatus() == PlagiarismJobStatus.COMPLETED).count();
        long highRiskPairs = pairs.stream()
                .filter(pair -> pair.getRiskLevel() == PlagiarismRiskLevel.HIGH || pair.getRiskLevel() == PlagiarismRiskLevel.CRITICAL)
                .count();
        return new PlagiarismPostmortemStats(
                jobs.size(),
                completedJobs,
                pairs.size(),
                highRiskPairs,
                enumDistribution(pairs, PlagiarismPairEntity::getRiskLevel, PlagiarismRiskLevel.class),
                enumDistribution(pairs, PlagiarismPairEntity::getReviewStatus, PlagiarismReviewStatus.class)
        );
    }

    private CaseScoreSummary caseScoreSummary(List<SubmissionCaseResultEntity> caseResults,
                                              Map<Long, ContestRunProblemSnapshotEntity> problemsByContestProblemId) {
        if (caseResults == null || caseResults.isEmpty()) {
            return new CaseScoreSummary(0, Map.of(), Map.of(), BigDecimal.ZERO, BigDecimal.ZERO);
        }
        BigDecimal totalScore = caseResults.stream()
                .map(SubmissionCaseResultEntity::getScore)
                .filter(Objects::nonNull)
                .reduce(BigDecimal.ZERO, BigDecimal::add);
        BigDecimal totalMaxScore = caseResults.stream()
                .map(SubmissionCaseResultEntity::getMaxScore)
                .filter(Objects::nonNull)
                .reduce(BigDecimal.ZERO, BigDecimal::add);
        Map<String, Long> subtaskDistribution = caseResults.stream()
                .map(result -> StringUtils.hasText(result.getSubtaskKey()) ? result.getSubtaskKey() : "none")
                .collect(Collectors.groupingBy(Function.identity(), LinkedHashMap::new, Collectors.counting()));
        return new CaseScoreSummary(
                caseResults.size(),
                enumDistribution(caseResults, SubmissionCaseResultEntity::getStatus, SubmissionStatus.class),
                subtaskDistribution,
                totalScore,
                totalMaxScore
        );
    }

    private List<String> weaknessCandidates(List<ProblemPostmortemStats> problemStats) {
        List<String> candidates = new ArrayList<>();
        problemStats.stream()
                .filter(problem -> problem.attemptedParticipants() > 0)
                .sorted(Comparator.comparingDouble(ProblemPostmortemStats::acceptedRate))
                .limit(5)
                .forEach(problem -> {
                    String tags = problem.tags().isEmpty() ? "未标注标签" : String.join(", ", problem.tags());
                    if (problem.acceptedRate() < 0.5 || highErrorCount(problem.statusDistribution()) >= 3) {
                        candidates.add("%s %s：通过率 %.1f%%，标签 %s".formatted(
                                safe(problem.label()), safe(problem.title()), problem.acceptedRate() * 100.0, tags));
                    }
                });
        return candidates;
    }

    private long highErrorCount(Map<String, Long> statusDistribution) {
        return statusDistribution.entrySet().stream()
                .filter(entry -> ERROR_STATUSES.stream().anyMatch(status -> status.name().equals(entry.getKey())))
                .mapToLong(Map.Entry::getValue)
                .sum();
    }

    private void assertReportableRun(Long contestId, ContestRunEntity run) {
        Instant now = Instant.now();
        if (ContestRunStatePolicy.isDeleted(run) || ContestRunStatePolicy.isArchived(run)) {
            throw new DomainException(ErrorCode.BAD_REQUEST, "Archived or deleted contest run cannot generate postmortem report");
        }
        if (!ContestRunStatePolicy.isAiOperationsEligible(run, now)) {
            throw new DomainException(ErrorCode.BAD_REQUEST, "Contest run must end before generating postmortem report");
        }
        long unfinished = submissionMapper.selectCount(new LambdaQueryWrapper<SubmissionEntity>()
                .eq(SubmissionEntity::getContestId, contestId)
                .eq(SubmissionEntity::getContestRunId, run.getId())
                .in(SubmissionEntity::getStatus, UNFINISHED_STATUSES));
        if (unfinished > 0) {
            throw new DomainException(ErrorCode.BAD_REQUEST, "Contest run has unfinished or system-error submissions");
        }
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
        throw new DomainException(ErrorCode.FORBIDDEN, "Cannot manage contest postmortem reports");
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

    private ContestPostmortemReportEntity requireReport(Long contestId, Long runId, Long reportId) {
        ContestPostmortemReportEntity report = reportMapper.selectOne(new LambdaQueryWrapper<ContestPostmortemReportEntity>()
                .eq(ContestPostmortemReportEntity::getId, reportId)
                .eq(ContestPostmortemReportEntity::getContestId, contestId)
                .eq(ContestPostmortemReportEntity::getContestRunId, runId));
        if (report == null) {
            throw new DomainException(ErrorCode.NOT_FOUND, "Postmortem report not found");
        }
        return report;
    }

    private ContestMode effectiveMode(ContestEntity contest, ContestRunEntity run) {
        return run.getModeSnapshot() == null ? contest.getMode() : run.getModeSnapshot();
    }

    private String effectiveTitle(ContestEntity contest, ContestRunEntity run) {
        return StringUtils.hasText(run.getContestTitleSnapshot()) ? run.getContestTitleSnapshot() : contest.getTitle();
    }

    private String toJson(PostmortemStatistics statistics) {
        try {
            return objectMapper.writeValueAsString(statistics);
        } catch (JsonProcessingException ex) {
            throw new DomainException(ErrorCode.INTERNAL_ERROR, "Could not serialize postmortem statistics");
        }
    }

    private PostmortemStatistics parseStatistics(String statisticsJson) {
        try {
            return objectMapper.readValue(statisticsJson, PostmortemStatistics.class);
        } catch (Exception ex) {
            throw new DomainException(ErrorCode.BAD_REQUEST, "Postmortem statistics could not be parsed");
        }
    }

    private ContestPostmortemReportResponse toResponse(ContestPostmortemReportEntity report) {
        return new ContestPostmortemReportResponse(report.getId(), report.getContestId(), report.getContestRunId(),
                report.getStatus(), report.getAiStatus(), report.getGeneratedBy(), report.getStatisticsJson(),
                report.getAiMarkdown(), report.getAiProvider(), report.getAiModel(), value(report.getPromptTokens()),
                value(report.getCompletionTokens()), report.getErrorMessage(), report.getCreatedAt(), report.getUpdatedAt(),
                report.getCompletedAt());
    }

    private String buildSummaryText(PostmortemStatistics statistics) {
        String problems = statistics.problems().stream()
                .map(problem -> "- %s %s：提交 %d，通过人数 %d，错误分布 %s".formatted(
                        safe(problem.label()), safe(problem.title()), problem.submissionCount(),
                        problem.acceptedParticipants(), problem.statusDistribution()))
                .collect(Collectors.joining("\n"));
        return """
                比赛：%s / %s
                赛制：%s
                参赛人数：%d active / %d total
                提交数：%d
                状态分布：%s
                语言分布：%s
                查重概览：任务 %d，风险对 %d，高风险 %d
                薄弱候选：%s
                题目概览：
                %s
                """.formatted(
                statistics.contestTitle(), statistics.runTitle(), statistics.mode(),
                statistics.activeParticipants(), statistics.participantCount(), statistics.submissionCount(),
                statistics.statusDistribution(), statistics.languageDistribution(),
                statistics.plagiarism().jobCount(), statistics.plagiarism().pairCount(), statistics.plagiarism().highRiskPairCount(),
                statistics.weaknessCandidates(), problems);
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

    private double percent(long numerator, long denominator) {
        if (denominator <= 0) {
            return 0.0;
        }
        return BigDecimal.valueOf(numerator)
                .divide(BigDecimal.valueOf(denominator), 4, RoundingMode.HALF_UP)
                .doubleValue();
    }

    private BigDecimal decimal(BigDecimal value) {
        return value == null ? BigDecimal.ZERO : value;
    }

    private String safeLower(String value) {
        return StringUtils.hasText(value) ? value.trim().toLowerCase(Locale.ROOT) : "";
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

    public record PostmortemStatistics(
            Long contestId,
            Long contestRunId,
            String contestTitle,
            String runTitle,
            String mode,
            String runKind,
            Instant startAt,
            Instant endAt,
            int participantCount,
            long activeParticipants,
            int submissionCount,
            Map<String, Long> languageDistribution,
            Map<String, Long> statusDistribution,
            Map<String, Long> errorDistribution,
            List<ProblemPostmortemStats> problems,
            CaseScoreSummary caseScoreSummary,
            PlagiarismPostmortemStats plagiarism,
            List<String> weaknessCandidates
    ) {}

    public record ProblemPostmortemStats(
            Long contestProblemId,
            Long problemId,
            String label,
            String title,
            String difficulty,
            List<String> tags,
            int submissionCount,
            int attemptedParticipants,
            int acceptedParticipants,
            double acceptedRate,
            Long firstAcceptedAtContestMillis,
            Map<String, Long> statusDistribution,
            CaseScoreSummary caseScoreSummary,
            BigDecimal bestScore,
            BigDecimal maxScore
    ) {}

    public record CaseScoreSummary(
            int caseResultCount,
            Map<String, Long> statusDistribution,
            Map<String, Long> subtaskDistribution,
            BigDecimal totalScore,
            BigDecimal totalMaxScore
    ) {}

    public record PlagiarismPostmortemStats(
            int jobCount,
            long completedJobCount,
            int pairCount,
            long highRiskPairCount,
            Map<String, Long> riskDistribution,
            Map<String, Long> reviewDistribution
    ) {}
}
