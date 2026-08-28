package com.aioj.next.problem.domain;

import com.aioj.next.common.api.PageResponse;
import com.aioj.next.common.api.TraceIds;
import com.aioj.next.common.error.DomainException;
import com.aioj.next.common.error.ErrorCode;
import com.aioj.next.common.security.Role;
import com.aioj.next.common.security.SecuritySupport;
import com.aioj.next.contract.judge.JudgeTaskMessage;
import com.aioj.next.contract.ai.AiProblemContextRequest;
import com.aioj.next.contract.ai.AiSubmissionCaseContext;
import com.aioj.next.contract.ai.AiSubmissionContextRequest;
import com.aioj.next.contract.ai.AiSubmissionContextResponse;
import com.aioj.next.contract.contest.ContestAiPolicyRequest;
import com.aioj.next.contract.contest.ContestAiPolicyResponse;
import com.aioj.next.contract.submission.DailySubmissionStatsResponse;
import com.aioj.next.contract.submission.SubmissionCreateRequest;
import com.aioj.next.contract.submission.SubmissionCaseResultResponse;
import com.aioj.next.contract.submission.SubmissionResponse;
import com.aioj.next.contract.submission.SubmissionScope;
import com.aioj.next.contract.submission.SubmissionStatus;
import com.aioj.next.problem.config.JudgeQueueConfig;
import com.aioj.next.problem.persistence.entity.ProblemEntity;
import com.aioj.next.problem.persistence.entity.SubmissionCaseResultEntity;
import com.aioj.next.problem.persistence.entity.SubmissionEntity;
import com.aioj.next.problem.persistence.mapper.SubmissionCaseResultMapper;
import com.aioj.next.problem.persistence.mapper.SubmissionMapper;
import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.conditions.query.QueryWrapper;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.amqp.rabbit.core.RabbitTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import org.springframework.util.StringUtils;

import java.time.LocalDate;
import java.time.Instant;
import java.time.ZoneId;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.HexFormat;
import java.util.ArrayList;
import java.util.Date;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

@Service
public class SubmissionService {
    private static final Logger log = LoggerFactory.getLogger(SubmissionService.class);
    private static final Set<String> SUPPORTED_LANGUAGES = Set.of("java", "cpp", "python");
    private static final ZoneId ZONE = ZoneId.systemDefault();

    private final ProblemCatalog problemCatalog;
    private final ContestService contestService;
    private final SubmissionMapper submissionMapper;
    private final SubmissionCaseResultMapper caseResultMapper;
    private final SubmissionRequestFingerprintService fingerprintService;
    private final ContestAiPolicyService contestAiPolicyService;
    private final ContestProblemVisibilityService visibilityService;
    private final RabbitTemplate rabbitTemplate;

    public SubmissionService(ProblemCatalog problemCatalog, ContestService contestService,
                             SubmissionMapper submissionMapper, SubmissionCaseResultMapper caseResultMapper,
                             SubmissionRequestFingerprintService fingerprintService,
                             ContestAiPolicyService contestAiPolicyService,
                             ContestProblemVisibilityService visibilityService,
                             RabbitTemplate rabbitTemplate) {
        this.problemCatalog = problemCatalog;
        this.contestService = contestService;
        this.submissionMapper = submissionMapper;
        this.caseResultMapper = caseResultMapper;
        this.fingerprintService = fingerprintService;
        this.contestAiPolicyService = contestAiPolicyService;
        this.visibilityService = visibilityService;
        this.rabbitTemplate = rabbitTemplate;
    }

    @Transactional
    public SubmissionResponse submit(SubmissionCreateRequest request) {
        return submit(request, null);
    }

    @Transactional
    public SubmissionResponse submit(SubmissionCreateRequest request, SubmissionRequestMetadata metadata) {
        if (request.code() != null && request.code().length() > SubmissionCreateRequest.MAX_SOURCE_CODE_CHARS) {
            throw new DomainException(ErrorCode.PAYLOAD_TOO_LARGE, "Submitted source code exceeds the size limit");
        }
        String language = normalizeLanguage(request.language());
        if (!SUPPORTED_LANGUAGES.contains(language)) {
            throw new DomainException(ErrorCode.BAD_REQUEST, "Unsupported language: " + request.language());
        }
        ProblemEntity problem = problemCatalog.findActive(request.problemId())
                .orElseThrow(() -> new DomainException(ErrorCode.NOT_FOUND, "Problem not found"));
        if (request.contestId() == null && visibilityService.isPrivate(problem)) {
            // Private problems are only submittable inside an active contest run window.
            throw new DomainException(ErrorCode.NOT_FOUND, "Problem not found");
        }

        Long userId = SecuritySupport.currentUserId();
        Instant now = Instant.now();
        ContestSubmissionContext contestContext = null;
        if (request.contestId() != null) {
            contestContext = contestService.resolveSubmissionContext(request.contestId(), request.contestRunId(),
                    request.contestProblemId(),
                    request.problemId(), userId, now);
        } else if (request.contestProblemId() != null) {
            throw new DomainException(ErrorCode.BAD_REQUEST, "Contest is required for contest problem submissions");
        }
        SubmissionEntity submission = new SubmissionEntity();
        submission.setProblemId(request.problemId());
        submission.setUserId(userId);
        if (contestContext != null) {
            submission.setContestId(contestContext.contest().getId());
            submission.setContestRunId(contestContext.contestRun() == null ? null : contestContext.contestRun().getId());
            submission.setContestProblemId(contestContext.contestProblem().getId());
            submission.setContestParticipantId(contestContext.participant().getId());
            submission.setSubmittedAtContestMillis(contestContext.submittedAtContestMillis());
        }
        submission.setVisibleToParticipant(true);
        submission.setLanguage(language);
        submission.setCode(request.code());
        submission.setStatus(SubmissionStatus.QUEUED);
        submission.setJudgeMessage("Queued for judging");
        submission.setRetryCount(0);
        submission.setCreatedAt(now);
        submission.setUpdatedAt(now);
        submissionMapper.insert(submission);
        fingerprintService.record(submission, metadata, now);

        Long memoryLimitKb = problem.getMemoryLimitKb() == null ? null : problem.getMemoryLimitKb().longValue();
        int effectiveTimeLimitMillis = problemCatalog.effectiveTimeLimitMillis(problem, language);
        publishAfterCommit(new JudgeTaskMessage(submission.getId(), request.problemId(), userId,
                contestContext == null ? null : contestContext.contest().getId(),
                contestContext == null || contestContext.contestRun() == null ? null : contestContext.contestRun().getId(),
                contestContext == null ? null : contestContext.contestProblem().getId(),
                contestContext == null ? null : contestContext.participant().getId(),
                contestContext == null ? null : contestContext.contest().getMode(),
                language, TraceIds.current(), effectiveTimeLimitMillis, memoryLimitKb));
        return toResponse(submission, false, false);
    }

    public SubmissionResponse get(Long id) {
        SubmissionEntity submission = submissionMapper.selectById(id);
        if (submission == null) {
            throw new DomainException(ErrorCode.NOT_FOUND, "Submission not found");
        }
        assertCanRead(submission);
        return toResponse(submission, submission.getUserId().equals(SecuritySupport.currentUserId()), true);
    }

    public AiSubmissionContextResponse aiSubmissionContext(AiSubmissionContextRequest request) {
        if (request == null || request.requestUserId() == null || request.submissionId() == null) {
            throw new DomainException(ErrorCode.BAD_REQUEST, "Request user and submission id are required");
        }
        SubmissionEntity submission = submissionMapper.selectById(request.submissionId());
        if (submission == null) {
            throw new DomainException(ErrorCode.NOT_FOUND, "Submission not found");
        }
        if (!request.requestUserId().equals(submission.getUserId())) {
            throw new DomainException(ErrorCode.FORBIDDEN, "Cannot analyze another user's submission");
        }
        if (request.problemId() != null && !request.problemId().equals(submission.getProblemId())) {
            throw new DomainException(ErrorCode.BAD_REQUEST, "Submission does not belong to the requested problem");
        }
        if (submission.getStatus() == SubmissionStatus.QUEUED || submission.getStatus() == SubmissionStatus.RUNNING) {
            throw new DomainException(ErrorCode.BAD_REQUEST, "Submission is still being judged");
        }

        ContestAiPolicyResponse policy = contestAiPolicyService.check(new ContestAiPolicyRequest(
                request.requestUserId(),
                submission.getProblemId(),
                firstNonNull(request.contestId(), submission.getContestId()),
                firstNonNull(request.contestRunId(), submission.getContestRunId()),
                firstNonNull(request.contestProblemId(), submission.getContestProblemId())
        ));
        boolean contestActive = policy != null && policy.activeContestProblem();
        boolean codeAllowed = !contestActive || policy.allowOwnSubmissionCodeToAi();
        AiProblemContextRequest problemRequest = new AiProblemContextRequest(
                request.requestUserId(),
                submission.getProblemId(),
                submission.getContestId(),
                submission.getContestRunId(),
                submission.getContestProblemId(),
                "AI_SUBMISSION_ANALYSIS"
        );
        return new AiSubmissionContextResponse(
                submission.getId(),
                submission.getUserId(),
                submission.getProblemId(),
                submission.getContestId(),
                submission.getContestRunId(),
                submission.getContestProblemId(),
                submission.getContestId() == null ? "PRACTICE" : "CONTEST",
                contestActive,
                submission.getLanguage(),
                submission.getStatus() == null ? null : submission.getStatus().name(),
                truncate(submission.getJudgeMessage(), 1200),
                codeAllowed ? truncate(submission.getStdoutExcerpt(), 1200) : null,
                codeAllowed ? truncate(submission.getStderrExcerpt(), 1200) : null,
                submission.getExitStatus(),
                longToInteger(submission.getRunTimeMillis()),
                longToInteger(submission.getMemoryKb()),
                submission.getScore() == null ? null : submission.getScore().doubleValue(),
                submission.getMaxScore() == null ? null : submission.getMaxScore().doubleValue(),
                codeAllowed,
                codeAllowed ? submission.getCode() : null,
                sha256Hex(submission.getCode()),
                caseResults(submission.getId()).stream()
                        .map(result -> new AiSubmissionCaseContext(
                                result.caseIndex(),
                                result.caseName(),
                                result.status() == null ? null : result.status().name(),
                                result.score() == null ? null : result.score().doubleValue(),
                                result.maxScore() == null ? null : result.maxScore().doubleValue(),
                                longToInteger(result.timeMillis()),
                                longToInteger(result.memoryKb()),
                                truncate(result.message(), 800)
                        ))
                        .toList(),
                problemCatalog.aiProblemContext(problemRequest),
                submission.getCreatedAt(),
                submission.getJudgedAt(),
                policy == null ? null : policy.policyMessage()
        );
    }

    public PageResponse<SubmissionResponse> list(long page, long pageSize, Long problemId, Long userId,
                                                 SubmissionStatus status, String language, Boolean mine,
                                                 Long contestId, Long contestRunId, Long contestProblemId,
                                                 SubmissionScope scope) {
        Long currentUserId = SecuritySupport.currentUserId();
        boolean privileged = SecuritySupport.hasAnyRole(Role.TEACHER, Role.ADMIN);
        String normalizedLanguage = normalizeOptionalLanguage(language);
        if (scope == SubmissionScope.PRACTICE && (contestId != null || contestRunId != null || contestProblemId != null)) {
            throw new DomainException(ErrorCode.BAD_REQUEST, "Contest filters cannot be used with practice submissions");
        }
        LambdaQueryWrapper<SubmissionEntity> query = new LambdaQueryWrapper<SubmissionEntity>()
                .eq(problemId != null, SubmissionEntity::getProblemId, problemId)
                .eq(contestId != null, SubmissionEntity::getContestId, contestId)
                .eq(contestRunId != null, SubmissionEntity::getContestRunId, contestRunId)
                .eq(contestProblemId != null, SubmissionEntity::getContestProblemId, contestProblemId)
                .eq(status != null, SubmissionEntity::getStatus, status)
                .eq(normalizedLanguage != null, SubmissionEntity::getLanguage, normalizedLanguage)
                .orderByDesc(SubmissionEntity::getCreatedAt)
                .orderByDesc(SubmissionEntity::getId);

        if (scope == SubmissionScope.PRACTICE) {
            query.isNull(SubmissionEntity::getContestId)
                    .isNull(SubmissionEntity::getContestRunId)
                    .isNull(SubmissionEntity::getContestProblemId);
        } else if (scope == SubmissionScope.CONTEST) {
            query.and(wrapper -> wrapper.isNotNull(SubmissionEntity::getContestId)
                    .or()
                    .isNotNull(SubmissionEntity::getContestRunId)
                    .or()
                    .isNotNull(SubmissionEntity::getContestProblemId));
        }

        if (!privileged && userId != null && !userId.equals(currentUserId)) {
            throw new DomainException(ErrorCode.FORBIDDEN, "Cannot query other users' submissions");
        }
        // "My submissions" style queries (including staff accounts on student-facing surfaces)
        // always get visibility masking; only admin review queries for other users bypass it.
        boolean selfView = !privileged || Boolean.TRUE.equals(mine) || (userId != null && userId.equals(currentUserId));
        if (selfView) {
            query.eq(SubmissionEntity::getUserId, currentUserId);
            // Visibility masking is enforced server-side: practice submissions to private
            // problems never surface, and contest submissions to private problems disappear
            // once their run has ended and the problem stayed unpublished.
            // Both masks are written NULL-safe: NOT(...) over conditions involving NULL
            // columns yields NULL (three-valued logic) and would silently drop rows such as
            // every practice submission.
            query.and(mask -> mask.isNotNull(SubmissionEntity::getContestRunId)
                    .or()
                    .notInSql(SubmissionEntity::getProblemId, "SELECT id FROM problems WHERE visibility = 'PRIVATE'"));
            Map<Long, Set<Long>> hiddenPairs = visibilityService.hiddenRunProblemPairs(contestId, contestRunId, Instant.now());
            for (Map.Entry<Long, Set<Long>> entry : hiddenPairs.entrySet()) {
                query.and(mask -> mask.isNull(SubmissionEntity::getContestRunId)
                        .or()
                        .ne(SubmissionEntity::getContestRunId, entry.getKey())
                        .or()
                        .isNull(SubmissionEntity::getContestProblemId)
                        .or()
                        .notIn(SubmissionEntity::getContestProblemId, entry.getValue()));
            }
        } else {
            query.eq(userId != null, SubmissionEntity::getUserId, userId);
        }

        Page<SubmissionEntity> result = submissionMapper.selectPage(new Page<>(normalizePage(page), normalizePageSize(pageSize)), query);
        return new PageResponse<>(result.getRecords().stream().map(submission -> toResponse(submission, false, false)).toList(),
                result.getTotal(), result.getCurrent(), result.getSize());
    }

    public List<DailySubmissionStatsResponse> dailyStats(int days) {
        int safeDays = Math.min(Math.max(days, 1), 30);
        LocalDate today = LocalDate.now(ZONE);
        LocalDate startDate = today.minusDays(safeDays - 1L);
        Instant start = startDate.atStartOfDay(ZONE).toInstant();
        Map<String, DailySubmissionStatsResponse> rows = dailyStatsRows(submissionMapper.selectMaps(
                new QueryWrapper<SubmissionEntity>()
                        .select(
                                "DATE(created_at) AS day",
                                "COUNT(*) AS total_submissions",
                                "SUM(CASE WHEN status = 'ACCEPTED' THEN 1 ELSE 0 END) AS accepted_submissions"
                        )
                        .ge("created_at", start)
                        .groupBy("DATE(created_at)")
        ));
        List<DailySubmissionStatsResponse> result = new ArrayList<>();
        for (int i = 0; i < safeDays; i++) {
            String day = startDate.plusDays(i).toString();
            result.add(rows.getOrDefault(day, new DailySubmissionStatsResponse(day, 0, 0)));
        }
        return result;
    }

    private String normalizeLanguage(String language) {
        if (!StringUtils.hasText(language)) {
            throw new DomainException(ErrorCode.BAD_REQUEST, "Language is required");
        }
        return language.trim().toLowerCase(Locale.ROOT);
    }

    private String normalizeOptionalLanguage(String language) {
        if (!StringUtils.hasText(language)) {
            return null;
        }
        String normalized = normalizeLanguage(language);
        if (!SUPPORTED_LANGUAGES.contains(normalized)) {
            throw new DomainException(ErrorCode.BAD_REQUEST, "Unsupported language: " + language);
        }
        return normalized;
    }

    private void assertCanRead(SubmissionEntity submission) {
        boolean own = submission.getUserId().equals(SecuritySupport.currentUserId());
        if (!own && !SecuritySupport.hasAnyRole(Role.TEACHER, Role.ADMIN)) {
            throw new DomainException(ErrorCode.FORBIDDEN, "Cannot read other users' submissions");
        }
        // Own submissions to hidden private problems stay unreadable for every identity on
        // student-facing surfaces; staff review of other users' submissions is admin-side.
        if (own && submissionHiddenByVisibility(submission)) {
            throw new DomainException(ErrorCode.NOT_FOUND, "Submission not found");
        }
    }

    private boolean submissionHiddenByVisibility(SubmissionEntity submission) {
        if (submission.getContestRunId() == null) {
            return submission.getProblemId() != null
                    && visibilityService.isProblemPrivate(submission.getProblemId());
        }
        Map<Long, Set<Long>> hiddenPairs = visibilityService.hiddenRunProblemPairs(
                submission.getContestId(), submission.getContestRunId(), Instant.now());
        Set<Long> hiddenContestProblemIds = hiddenPairs.getOrDefault(submission.getContestRunId(), Set.of());
        return submission.getContestProblemId() != null
                && hiddenContestProblemIds.contains(submission.getContestProblemId());
    }

    private SubmissionResponse toResponse(SubmissionEntity submission, boolean includeCode, boolean includeCaseResults) {
        return new SubmissionResponse(submission.getId(), submission.getProblemId(), submission.getUserId(),
                submission.getContestId(), submission.getContestRunId(), submission.getContestProblemId(), submission.getContestParticipantId(),
                submission.getSubmittedAtContestMillis(), !Boolean.FALSE.equals(submission.getVisibleToParticipant()),
                submission.getLanguage(), includeCode ? submission.getCode() : null,
                submission.getStatus(), submission.getJudgeMessage(),
                submission.getTimeMillis(), submission.getMemoryKb(),
                includeCode ? submission.getStdoutExcerpt() : null,
                includeCode ? submission.getStderrExcerpt() : null,
                includeCode ? submission.getExitStatus() : null,
                includeCode ? submission.getRunTimeMillis() : null,
                submission.getScore(), submission.getMaxScore(),
                includeCaseResults ? caseResults(submission.getId()) : null,
                submission.getCreatedAt(), submission.getJudgedAt());
    }

    private java.util.List<SubmissionCaseResultResponse> caseResults(Long submissionId) {
        return caseResultMapper.selectList(new LambdaQueryWrapper<SubmissionCaseResultEntity>()
                        .eq(SubmissionCaseResultEntity::getSubmissionId, submissionId)
                        .orderByAsc(SubmissionCaseResultEntity::getCaseIndex)
                        .orderByAsc(SubmissionCaseResultEntity::getId))
                .stream()
                .map(result -> new SubmissionCaseResultResponse(result.getId(), result.getSubmissionId(),
                        result.getContestId(), result.getContestProblemId(), result.getContestParticipantId(),
                        result.getTestcasePackageId(), result.getCaseId(), result.getCaseIndex(),
                        result.getCaseName(), result.getSubtaskKey(), result.getStatus(), result.getScore(),
                        result.getMaxScore(), result.getTimeMillis(), result.getMemoryKb(), result.getMessage(),
                        result.getCreatedAt()))
                .toList();
    }

    private void publishAfterCommit(JudgeTaskMessage message) {
        if (!TransactionSynchronizationManager.isSynchronizationActive()) {
            publishJudgeTask(message);
            return;
        }
        TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
            @Override
            public void afterCommit() {
                publishJudgeTask(message);
            }
        });
    }

    private void publishJudgeTask(JudgeTaskMessage message) {
        rabbitTemplate.convertAndSend(JudgeQueueConfig.JUDGE_EXCHANGE, JudgeQueueConfig.JUDGE_ROUTING_KEY, message,
                amqpMessage -> {
                    amqpMessage.getMessageProperties().setTimestamp(Date.from(Instant.now()));
                    return amqpMessage;
                });
        log.info("Published judge task submission={} problem={}", message.submissionId(), message.problemId());
    }

    private long normalizePage(long page) {
        return Math.max(page, 1);
    }

    private Long firstNonNull(Long first, Long second) {
        return first == null ? second : first;
    }

    private Integer longToInteger(Long value) {
        if (value == null) {
            return null;
        }
        return value > Integer.MAX_VALUE ? Integer.MAX_VALUE : value.intValue();
    }

    private String truncate(String value, int maxLength) {
        if (!StringUtils.hasText(value)) {
            return value;
        }
        String normalized = value.trim();
        return normalized.length() <= maxLength ? normalized : normalized.substring(0, maxLength) + "…";
    }

    private String sha256Hex(String value) {
        if (value == null) {
            return null;
        }
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            return HexFormat.of().formatHex(digest.digest(value.getBytes(StandardCharsets.UTF_8)));
        } catch (Exception ex) {
            return null;
        }
    }

    private long normalizePageSize(long pageSize) {
        if (pageSize <= 0) {
            return 20;
        }
        return Math.min(pageSize, 100);
    }

    private Map<String, DailySubmissionStatsResponse> dailyStatsRows(List<Map<String, Object>> rows) {
        Map<String, DailySubmissionStatsResponse> result = new LinkedHashMap<>();
        for (Map<String, Object> row : rows) {
            String day = day(row.get("day"));
            if (day == null) {
                continue;
            }
            result.put(day, new DailySubmissionStatsResponse(
                    day,
                    number(row.get("total_submissions")),
                    number(row.get("accepted_submissions"))
            ));
        }
        return result;
    }

    private String day(Object value) {
        if (value == null) {
            return null;
        }
        String day = String.valueOf(value);
        return day.length() > 10 ? day.substring(0, 10) : day;
    }

    private long number(Object value) {
        return value instanceof Number number ? number.longValue() : 0L;
    }
}
