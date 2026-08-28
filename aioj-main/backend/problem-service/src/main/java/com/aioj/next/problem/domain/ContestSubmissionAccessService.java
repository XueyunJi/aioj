package com.aioj.next.problem.domain;

import com.aioj.next.common.api.PageResponse;
import com.aioj.next.common.api.TraceIds;
import com.aioj.next.common.error.DomainException;
import com.aioj.next.common.error.ErrorCode;
import com.aioj.next.common.security.Role;
import com.aioj.next.common.security.SecuritySupport;
import com.aioj.next.contract.contest.ContestSubmissionCodeResponse;
import com.aioj.next.contract.contest.ContestSubmissionResponse;
import com.aioj.next.contract.contest.SubmissionCodeAccessLogResponse;
import com.aioj.next.contract.contest.SubmissionCodeAccessRequest;
import com.aioj.next.contract.submission.SubmissionCaseResultResponse;
import com.aioj.next.contract.submission.SubmissionStatus;
import com.aioj.next.problem.persistence.entity.ContestEntity;
import com.aioj.next.problem.persistence.entity.ContestParticipantEntity;
import com.aioj.next.problem.persistence.entity.ContestProblemEntity;
import com.aioj.next.problem.persistence.entity.SubmissionCaseResultEntity;
import com.aioj.next.problem.persistence.entity.SubmissionCodeAccessLogEntity;
import com.aioj.next.problem.persistence.entity.SubmissionEntity;
import com.aioj.next.problem.persistence.entity.UserAccountEntity;
import com.aioj.next.problem.persistence.mapper.ContestMapper;
import com.aioj.next.problem.persistence.mapper.ContestParticipantMapper;
import com.aioj.next.problem.persistence.mapper.ContestProblemMapper;
import com.aioj.next.problem.persistence.mapper.SubmissionCaseResultMapper;
import com.aioj.next.problem.persistence.mapper.SubmissionCodeAccessLogMapper;
import com.aioj.next.problem.persistence.mapper.SubmissionMapper;
import com.aioj.next.problem.persistence.mapper.UserAccountMapper;
import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.util.StringUtils;

import java.time.Instant;
import java.util.Collection;
import java.util.Collections;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.function.Function;
import java.util.stream.Collectors;

@Service
public class ContestSubmissionAccessService {
    private final ContestMapper contestMapper;
    private final ContestProblemMapper contestProblemMapper;
    private final ContestParticipantMapper contestParticipantMapper;
    private final SubmissionMapper submissionMapper;
    private final SubmissionCaseResultMapper caseResultMapper;
    private final SubmissionCodeAccessLogMapper accessLogMapper;
    private final UserAccountMapper userAccountMapper;
    private final OperationAuditService operationAuditService;

    public ContestSubmissionAccessService(ContestMapper contestMapper,
                                          ContestProblemMapper contestProblemMapper,
                                          ContestParticipantMapper contestParticipantMapper,
                                          SubmissionMapper submissionMapper,
                                          SubmissionCaseResultMapper caseResultMapper,
                                          SubmissionCodeAccessLogMapper accessLogMapper,
                                          UserAccountMapper userAccountMapper,
                                          OperationAuditService operationAuditService) {
        this.contestMapper = contestMapper;
        this.contestProblemMapper = contestProblemMapper;
        this.contestParticipantMapper = contestParticipantMapper;
        this.submissionMapper = submissionMapper;
        this.caseResultMapper = caseResultMapper;
        this.accessLogMapper = accessLogMapper;
        this.userAccountMapper = userAccountMapper;
        this.operationAuditService = operationAuditService;
    }

    public PageResponse<ContestSubmissionResponse> listSubmissions(Long contestId, Long runId, long page, long pageSize,
                                                                   Long contestProblemId, Long participantId,
                                                                   Long userId, SubmissionStatus status,
                                                                   String language) {
        requireManagedContest(contestId);
        String normalizedLanguage = normalizeLanguage(language);
        LambdaQueryWrapper<SubmissionEntity> query = new LambdaQueryWrapper<SubmissionEntity>()
                .eq(SubmissionEntity::getContestId, contestId)
                .eq(runId != null, SubmissionEntity::getContestRunId, runId)
                .eq(contestProblemId != null, SubmissionEntity::getContestProblemId, contestProblemId)
                .eq(participantId != null, SubmissionEntity::getContestParticipantId, participantId)
                .eq(userId != null, SubmissionEntity::getUserId, userId)
                .eq(status != null, SubmissionEntity::getStatus, status)
                .eq(normalizedLanguage != null, SubmissionEntity::getLanguage, normalizedLanguage)
                .orderByDesc(SubmissionEntity::getCreatedAt)
                .orderByDesc(SubmissionEntity::getId);
        Page<SubmissionEntity> result = submissionMapper.selectPage(new Page<>(normalizePage(page), normalizePageSize(pageSize)), query);
        ContestLookup lookup = lookup(contestId);
        return new PageResponse<>(result.getRecords().stream()
                .map(submission -> toResponse(submission, lookup, false, false))
                .toList(), result.getTotal(), result.getCurrent(), result.getSize());
    }

    public PageResponse<ContestSubmissionResponse> listSubmissions(Long contestId, long page, long pageSize,
                                                                   Long contestProblemId, Long participantId,
                                                                   Long userId, SubmissionStatus status,
                                                                   String language) {
        return listSubmissions(contestId, null, page, pageSize, contestProblemId, participantId, userId, status, language);
    }

    public ContestSubmissionResponse getSubmission(Long contestId, Long submissionId) {
        requireManagedContest(contestId);
        SubmissionEntity submission = requireContestSubmission(contestId, submissionId);
        return toResponse(submission, lookup(contestId), false, true);
    }

    @Transactional
    public ContestSubmissionCodeResponse accessCode(Long contestId, Long submissionId, SubmissionCodeAccessRequest request) {
        requireManagedContest(contestId);
        SubmissionEntity submission = requireContestSubmission(contestId, submissionId);
        SubmissionCodeAccessLogEntity log = new SubmissionCodeAccessLogEntity();
        log.setContestId(contestId);
        log.setContestRunId(submission.getContestRunId());
        log.setSubmissionId(submission.getId());
        log.setViewerUserId(SecuritySupport.currentUserId());
        log.setTargetUserId(submission.getUserId());
        log.setContestParticipantId(submission.getContestParticipantId());
        log.setReason(normalizeReason(request == null ? null : request.reason()));
        log.setTraceId(TraceIds.current());
        log.setCreatedAt(Instant.now());
        accessLogMapper.insert(log);
        operationAuditService.recordCurrentUser("SUBMISSION_CODE_ACCESS", "SUBMISSION", submission.getId(),
                contestId, submission.getContestRunId(), submission.getUserId(), "COMPLETED",
                Map.of("auditLogId", log.getId(), "reasonProvided", StringUtils.hasText(log.getReason())));
        return new ContestSubmissionCodeResponse(log.getId(), toResponse(submission, lookup(contestId), true, true));
    }

    public PageResponse<SubmissionCodeAccessLogResponse> listAccessLogs(Long contestId, Long runId, long page, long pageSize,
                                                                        Long submissionId, Long viewerUserId,
                                                                        Long targetUserId) {
        requireManagedContest(contestId);
        LambdaQueryWrapper<SubmissionCodeAccessLogEntity> query = new LambdaQueryWrapper<SubmissionCodeAccessLogEntity>()
                .eq(SubmissionCodeAccessLogEntity::getContestId, contestId)
                .eq(runId != null, SubmissionCodeAccessLogEntity::getContestRunId, runId)
                .eq(submissionId != null, SubmissionCodeAccessLogEntity::getSubmissionId, submissionId)
                .eq(viewerUserId != null, SubmissionCodeAccessLogEntity::getViewerUserId, viewerUserId)
                .eq(targetUserId != null, SubmissionCodeAccessLogEntity::getTargetUserId, targetUserId)
                .orderByDesc(SubmissionCodeAccessLogEntity::getCreatedAt)
                .orderByDesc(SubmissionCodeAccessLogEntity::getId);
        Page<SubmissionCodeAccessLogEntity> result = accessLogMapper.selectPage(new Page<>(normalizePage(page), normalizePageSize(pageSize)), query);
        ContestLookup contestLookup = lookup(contestId);
        Map<Long, SubmissionEntity> submissions = mapById(result.getRecords().stream()
                .map(SubmissionCodeAccessLogEntity::getSubmissionId)
                .filter(Objects::nonNull)
                .toList(), submissionMapper::selectBatchIds, SubmissionEntity::getId);
        Map<Long, UserAccountEntity> viewers = mapById(result.getRecords().stream()
                .map(SubmissionCodeAccessLogEntity::getViewerUserId)
                .filter(Objects::nonNull)
                .toList(), userAccountMapper::selectBatchIds, UserAccountEntity::getId);
        return new PageResponse<>(result.getRecords().stream()
                .map(log -> toLogResponse(log, submissions.get(log.getSubmissionId()), viewers.get(log.getViewerUserId()), contestLookup))
                .toList(), result.getTotal(), result.getCurrent(), result.getSize());
    }

    public PageResponse<SubmissionCodeAccessLogResponse> listAccessLogs(Long contestId, long page, long pageSize,
                                                                        Long submissionId, Long viewerUserId,
                                                                        Long targetUserId) {
        return listAccessLogs(contestId, null, page, pageSize, submissionId, viewerUserId, targetUserId);
    }

    private ContestEntity requireManagedContest(Long contestId) {
        ContestEntity contest = contestMapper.selectById(contestId);
        if (contest == null || contest.getDeletedAt() != null) {
            throw new DomainException(ErrorCode.NOT_FOUND, "Contest not found");
        }
        if (SecuritySupport.hasRole(Role.ADMIN)
                || (SecuritySupport.hasRole(Role.TEACHER) && contest.getOwnerUserId().equals(SecuritySupport.currentUserId()))) {
            return contest;
        }
        throw new DomainException(ErrorCode.FORBIDDEN, "Cannot manage contest submissions");
    }

    private SubmissionEntity requireContestSubmission(Long contestId, Long submissionId) {
        SubmissionEntity submission = submissionMapper.selectOne(new LambdaQueryWrapper<SubmissionEntity>()
                .eq(SubmissionEntity::getId, submissionId)
                .eq(SubmissionEntity::getContestId, contestId));
        if (submission == null) {
            throw new DomainException(ErrorCode.NOT_FOUND, "Contest submission not found");
        }
        return submission;
    }

    private ContestLookup lookup(Long contestId) {
        Map<Long, ContestProblemEntity> problems = contestProblemMapper.selectList(new LambdaQueryWrapper<ContestProblemEntity>()
                        .eq(ContestProblemEntity::getContestId, contestId))
                .stream()
                .collect(Collectors.toMap(ContestProblemEntity::getId, Function.identity()));
        Map<Long, ContestParticipantEntity> participants = contestParticipantMapper.selectList(new LambdaQueryWrapper<ContestParticipantEntity>()
                        .eq(ContestParticipantEntity::getContestId, contestId))
                .stream()
                .collect(Collectors.toMap(ContestParticipantEntity::getId, Function.identity()));
        return new ContestLookup(problems, participants);
    }

    private ContestSubmissionResponse toResponse(SubmissionEntity submission, ContestLookup lookup, boolean includeCode,
                                                 boolean includeCaseResults) {
        ContestProblemEntity problem = lookup.problems().get(submission.getContestProblemId());
        ContestParticipantEntity participant = lookup.participants().get(submission.getContestParticipantId());
        return new ContestSubmissionResponse(submission.getId(), submission.getContestId(), submission.getContestRunId(),
                submission.getContestProblemId(),
                submission.getProblemId(), problem == null ? null : problem.getLabel(), problem == null ? null : problem.getDisplayTitle(),
                submission.getContestParticipantId(), submission.getUserId(),
                participant == null ? "#" + submission.getUserId() : participant.getAccountSnapshot(),
                participant == null ? "#" + submission.getUserId() : participant.getDisplayNameSnapshot(),
                participant == null ? null : participant.getEmailSnapshot(),
                submission.getLanguage(), submission.getStatus(), submission.getJudgeMessage(), submission.getTimeMillis(),
                submission.getMemoryKb(), submission.getScore(), submission.getMaxScore(),
                includeCaseResults ? caseResults(submission.getId()) : null,
                submission.getSubmittedAtContestMillis(), submission.getCreatedAt(),
                submission.getJudgedAt(), includeCode, includeCode ? submission.getCode() : null,
                includeCode ? submission.getStdoutExcerpt() : null,
                includeCode ? submission.getStderrExcerpt() : null,
                includeCode ? submission.getExitStatus() : null,
                includeCode ? submission.getRunTimeMillis() : null);
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

    private SubmissionCodeAccessLogResponse toLogResponse(SubmissionCodeAccessLogEntity log, SubmissionEntity submission,
                                                          UserAccountEntity viewer, ContestLookup lookup) {
        ContestProblemEntity problem = submission == null ? null : lookup.problems().get(submission.getContestProblemId());
        ContestParticipantEntity participant = submission == null ? null : lookup.participants().get(submission.getContestParticipantId());
        return new SubmissionCodeAccessLogResponse(log.getId(), log.getContestId(), log.getContestRunId(), log.getSubmissionId(),
                log.getViewerUserId(), log.getTargetUserId(), log.getContestParticipantId(),
                viewer == null ? null : viewer.getAccount(), viewer == null ? null : displayName(viewer),
                participant == null ? null : participant.getAccountSnapshot(),
                participant == null ? null : participant.getDisplayNameSnapshot(),
                problem == null ? null : problem.getLabel(), problem == null ? null : problem.getDisplayTitle(),
                log.getReason(), log.getTraceId(), log.getCreatedAt());
    }

    private String displayName(UserAccountEntity user) {
        return StringUtils.hasText(user.getDisplayName()) ? user.getDisplayName() : user.getAccount();
    }

    private String normalizeLanguage(String language) {
        return StringUtils.hasText(language) ? language.trim().toLowerCase(Locale.ROOT) : null;
    }

    private String normalizeReason(String reason) {
        return StringUtils.hasText(reason) ? reason.trim() : null;
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

    private <T> Map<Long, T> mapById(Collection<Long> ids, Function<Collection<Long>, Collection<T>> loader,
                                     Function<T, Long> idGetter) {
        if (ids == null || ids.isEmpty()) {
            return Collections.emptyMap();
        }
        return loader.apply(ids.stream().distinct().toList()).stream()
                .collect(Collectors.toMap(idGetter, Function.identity()));
    }

    private record ContestLookup(
            Map<Long, ContestProblemEntity> problems,
            Map<Long, ContestParticipantEntity> participants
    ) {
    }
}
