package com.aioj.next.problem.domain;

import com.aioj.next.common.api.PageResponse;
import com.aioj.next.common.error.DomainException;
import com.aioj.next.common.error.ErrorCode;
import com.aioj.next.common.security.Role;
import com.aioj.next.common.security.SecuritySupport;
import com.aioj.next.contract.contest.ContestAiPolicyMode;
import com.aioj.next.contract.contest.ContestInvitationBatchItemStatus;
import com.aioj.next.contract.contest.ContestInvitationBatchRequest;
import com.aioj.next.contract.contest.ContestInvitationBatchResponse;
import com.aioj.next.contract.contest.ContestInvitationBatchResultItem;
import com.aioj.next.contract.contest.ContestMode;
import com.aioj.next.contract.contest.ContestOpenRunResponse;
import com.aioj.next.contract.contest.ContestParticipantAddRequest;
import com.aioj.next.contract.contest.ContestParticipantResponse;
import com.aioj.next.contract.contest.ContestParticipantStatus;
import com.aioj.next.contract.contest.ContestParticipantType;
import com.aioj.next.contract.contest.ContestProblemScoringMode;
import com.aioj.next.contract.contest.ContestRegistrationAccess;
import com.aioj.next.contract.contest.ContestRegistrationPolicy;
import com.aioj.next.contract.contest.ContestRegistrationResponse;
import com.aioj.next.contract.contest.ContestRegistrationStatus;
import com.aioj.next.contract.contest.ContestRunCreateRequest;
import com.aioj.next.contract.contest.ContestRunKind;
import com.aioj.next.contract.contest.ContestRunListPurpose;
import com.aioj.next.contract.contest.ContestRunProblemSnapshotResponse;
import com.aioj.next.contract.contest.ContestRunResponse;
import com.aioj.next.contract.contest.ContestRunStatus;
import com.aioj.next.contract.contest.ContestRunUpdateRequest;
import com.aioj.next.contract.contest.ContestStatus;
import com.aioj.next.contract.contest.ContestVisibility;
import com.aioj.next.contract.learning.LearningGroupMemberRole;
import com.aioj.next.contract.problem.ProblemVisibility;
import com.aioj.next.contract.problem.TestcasePackageStatus;
import com.aioj.next.problem.persistence.entity.ContestEntity;
import com.aioj.next.problem.persistence.entity.ContestParticipantEntity;
import com.aioj.next.problem.persistence.entity.ContestParticipantSnapshotEntity;
import com.aioj.next.problem.persistence.entity.ContestProblemEntity;
import com.aioj.next.problem.persistence.entity.ContestProblemScoringRuleEntity;
import com.aioj.next.problem.persistence.entity.ContestRegistrationEntity;
import com.aioj.next.problem.persistence.entity.ContestRunAllowedGroupEntity;
import com.aioj.next.problem.persistence.entity.ContestRunEntity;
import com.aioj.next.problem.persistence.entity.ContestRunProblemSnapshotEntity;
import com.aioj.next.problem.persistence.entity.LearningGroupEntity;
import com.aioj.next.problem.persistence.entity.LearningGroupMemberEntity;
import com.aioj.next.problem.persistence.entity.ProblemEntity;
import com.aioj.next.problem.persistence.entity.ProblemSubtaskEntity;
import com.aioj.next.problem.persistence.entity.TestcasePackageCaseEntity;
import com.aioj.next.problem.persistence.entity.TestcasePackageEntity;
import com.aioj.next.problem.persistence.entity.UserAccountEntity;
import com.aioj.next.problem.persistence.mapper.ContestMapper;
import com.aioj.next.problem.persistence.mapper.ContestParticipantMapper;
import com.aioj.next.problem.persistence.mapper.ContestParticipantSnapshotMapper;
import com.aioj.next.problem.persistence.mapper.ContestProblemMapper;
import com.aioj.next.problem.persistence.mapper.ContestProblemScoringRuleMapper;
import com.aioj.next.problem.persistence.mapper.ContestRegistrationMapper;
import com.aioj.next.problem.persistence.mapper.ContestRunAllowedGroupMapper;
import com.aioj.next.problem.persistence.mapper.ContestRunMapper;
import com.aioj.next.problem.persistence.mapper.ContestRunProblemSnapshotMapper;
import com.aioj.next.problem.persistence.mapper.LearningGroupMapper;
import com.aioj.next.problem.persistence.mapper.LearningGroupMemberMapper;
import com.aioj.next.problem.persistence.mapper.ProblemSubtaskMapper;
import com.aioj.next.problem.persistence.mapper.TestcasePackageCaseMapper;
import com.aioj.next.problem.persistence.mapper.TestcasePackageMapper;
import com.aioj.next.problem.persistence.mapper.UserAccountMapper;
import com.aioj.next.problem.domain.notification.UserNotificationService;
import com.aioj.next.problem.domain.notification.ContestInvitationNotificationRequestedEvent;
import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.util.StringUtils;

import java.time.Instant;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collection;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.function.Function;
import java.util.stream.Collectors;

@Service
public class ContestRunService {
    private static final TypeReference<List<String>> STRING_LIST = new TypeReference<>() {
    };

    private final ContestMapper contestMapper;
    private final ContestRunMapper contestRunMapper;
    private final ContestRegistrationMapper registrationMapper;
    private final ContestRunAllowedGroupMapper allowedGroupMapper;
    private final ContestProblemMapper contestProblemMapper;
    private final ContestProblemScoringRuleMapper scoringRuleMapper;
    private final ContestRunProblemSnapshotMapper problemSnapshotMapper;
    private final ContestParticipantMapper participantMapper;
    private final ContestParticipantSnapshotMapper participantSnapshotMapper;
    private final LearningGroupMapper learningGroupMapper;
    private final LearningGroupMemberMapper learningGroupMemberMapper;
    private final TestcasePackageMapper testcasePackageMapper;
    private final TestcasePackageCaseMapper testcaseCaseMapper;
    private final ProblemSubtaskMapper subtaskMapper;
    private final UserAccountMapper userAccountMapper;
    private final ProblemCatalog problemCatalog;
    private final ContestProblemVisibilityService visibilityService;
    private final UserNotificationService notificationService;
    private final ApplicationEventPublisher eventPublisher;
    private final ObjectMapper objectMapper;

    public ContestRunService(ContestMapper contestMapper,
                             ContestRunMapper contestRunMapper,
                             ContestRegistrationMapper registrationMapper,
                             ContestRunAllowedGroupMapper allowedGroupMapper,
                             ContestProblemMapper contestProblemMapper,
                             ContestProblemScoringRuleMapper scoringRuleMapper,
                             ContestRunProblemSnapshotMapper problemSnapshotMapper,
                             ContestParticipantMapper participantMapper,
                             ContestParticipantSnapshotMapper participantSnapshotMapper,
                             LearningGroupMapper learningGroupMapper,
                             LearningGroupMemberMapper learningGroupMemberMapper,
                             TestcasePackageMapper testcasePackageMapper,
                             TestcasePackageCaseMapper testcaseCaseMapper,
                             ProblemSubtaskMapper subtaskMapper,
                             UserAccountMapper userAccountMapper,
                             ProblemCatalog problemCatalog,
                             ContestProblemVisibilityService visibilityService,
                             UserNotificationService notificationService,
                             ApplicationEventPublisher eventPublisher,
                             ObjectMapper objectMapper) {
        this.contestMapper = contestMapper;
        this.contestRunMapper = contestRunMapper;
        this.registrationMapper = registrationMapper;
        this.allowedGroupMapper = allowedGroupMapper;
        this.contestProblemMapper = contestProblemMapper;
        this.scoringRuleMapper = scoringRuleMapper;
        this.problemSnapshotMapper = problemSnapshotMapper;
        this.participantMapper = participantMapper;
        this.participantSnapshotMapper = participantSnapshotMapper;
        this.learningGroupMapper = learningGroupMapper;
        this.learningGroupMemberMapper = learningGroupMemberMapper;
        this.testcasePackageMapper = testcasePackageMapper;
        this.testcaseCaseMapper = testcaseCaseMapper;
        this.subtaskMapper = subtaskMapper;
        this.userAccountMapper = userAccountMapper;
        this.problemCatalog = problemCatalog;
        this.visibilityService = visibilityService;
        this.notificationService = notificationService;
        this.eventPublisher = eventPublisher;
        this.objectMapper = objectMapper;
    }

    public PageResponse<ContestRunResponse> listRuns(Long contestId, ContestRunStatus status, String keyword,
                                                     Instant from, Instant to, ContestRunListPurpose purpose,
                                                     long page, long pageSize) {
        ContestEntity contest = requireManagedContest(contestId);
        assertContestConfirmed(contest);
        Instant now = Instant.now();
        LambdaQueryWrapper<ContestRunEntity> query = new LambdaQueryWrapper<ContestRunEntity>()
                .eq(ContestRunEntity::getContestId, contest.getId())
                .isNull(ContestRunEntity::getDeletedAt)
                .like(StringUtils.hasText(keyword), ContestRunEntity::getTitle, keyword == null ? null : keyword.trim())
                .ge(from != null, ContestRunEntity::getStartAt, from)
                .le(to != null, ContestRunEntity::getStartAt, to)
                .orderByDesc(ContestRunEntity::getStartAt)
                .orderByDesc(ContestRunEntity::getId);
        applyRunStatusFilter(query, status, now);
        applyRunPurposeFilter(query, purpose, now);
        Page<ContestRunEntity> result = contestRunMapper.selectPage(new Page<>(normalizePage(page), normalizePageSize(pageSize)), query);
        return new PageResponse<>(result.getRecords().stream().map(this::toRunResponse).toList(),
                result.getTotal(), result.getCurrent(), result.getSize());
    }

    public ContestRunResponse getRun(Long contestId, Long runId) {
        ContestEntity contest = requireContest(contestId);
        assertContestConfirmed(contest);
        ContestRunEntity run = requireRun(contestId, runId);
        if (!canManage(contest) && !canViewOpenRun(contest, run)) {
            throw new DomainException(ErrorCode.FORBIDDEN, "Cannot access contest run");
        }
        return toRunResponse(run);
    }

    @Transactional
    public ContestRunResponse createRun(Long contestId, ContestRunCreateRequest request) {
        ContestEntity contest = requireManagedContest(contestId);
        assertContestConfirmed(contest);
        validateRunTimes(request.startAt(), request.endAt(), request.freezeAt());
        validateRegistrationWindow(request.registrationStartAt(), request.registrationEndAt(), request.startAt());
        validateSourceRun(contestId, request.sourceRunId());
        RegistrationSettings registration = registrationSettings(request.registrationAccess(),
                request.approvalRequired(), request.registrationPolicy(), request.allowedGroupIds());
        validateAllowedGroups(registration.access(), registration.allowedGroupIds());
        Instant now = Instant.now();
        String title = requireUniqueRunTitle(null, request.title());
        ContestRunEntity run = new ContestRunEntity();
        run.setContestId(contest.getId());
        run.setRunKind(request.runKind() == null ? ContestRunKind.FORMAL : request.runKind());
        run.setTitle(title);
        run.setStatus(ContestRunStatus.DRAFT);
        run.setStartAt(request.startAt());
        run.setEndAt(request.endAt());
        run.setFreezeAt(request.freezeAt());
        run.setSourceRunId(request.sourceRunId());
        run.setCreatedBy(SecuritySupport.currentUserId());
        run.setRegistrationAccess(registration.access());
        run.setApprovalRequired(registration.approvalRequired());
        run.setRegistrationPolicy(toLegacyPolicy(registration.access(), registration.approvalRequired()));
        run.setRegistrationStartAt(request.registrationStartAt());
        run.setRegistrationEndAt(request.registrationEndAt());
        run.setMaxParticipants(normalizeMaxParticipants(request.maxParticipants()));
        run.setCreatedAt(now);
        run.setUpdatedAt(now);
        try {
            contestRunMapper.insert(run);
        } catch (DuplicateKeyException duplicate) {
            throw new DomainException(ErrorCode.CONFLICT, "Contest run title already exists");
        }
        replaceAllowedGroups(contest.getId(), run.getId(), registration.allowedGroupIds(), now);
        return toRunResponse(run);
    }

    @Transactional
    public ContestRunResponse updateRun(Long contestId, Long runId, ContestRunUpdateRequest request) {
        ContestEntity contest = requireManagedContest(contestId);
        assertContestConfirmed(contest);
        ContestRunEntity run = requireRun(contestId, runId);
        assertRunIsNotExpiredDraft(run);
        if (run.getStatus() != ContestRunStatus.DRAFT) {
            throw new DomainException(ErrorCode.BAD_REQUEST, "Only draft contest runs can be updated");
        }
        Instant startAt = request.startAt() == null ? run.getStartAt() : request.startAt();
        Instant endAt = request.endAt() == null ? run.getEndAt() : request.endAt();
        Instant freezeAt = request.freezeAt() == null ? run.getFreezeAt() : request.freezeAt();
        validateRunTimes(startAt, endAt, freezeAt);
        validateRegistrationWindow(request.registrationStartAt() == null ? run.getRegistrationStartAt() : request.registrationStartAt(),
                request.registrationEndAt() == null ? run.getRegistrationEndAt() : request.registrationEndAt(), startAt);
        RegistrationSettings registration = registrationSettings(
                request.registrationAccess() == null ? effectiveRegistrationAccess(run) : request.registrationAccess(),
                request.approvalRequired() == null ? Boolean.TRUE.equals(run.getApprovalRequired()) : request.approvalRequired(),
                request.registrationPolicy(),
                request.allowedGroupIds() == null ? allowedGroupIds(run.getId()) : request.allowedGroupIds());
        validateAllowedGroups(registration.access(), registration.allowedGroupIds());
        if (StringUtils.hasText(request.title())) {
            run.setTitle(requireUniqueRunTitle(runId, request.title()));
        }
        run.setStartAt(startAt);
        run.setEndAt(endAt);
        run.setFreezeAt(freezeAt);
        run.setRegistrationAccess(registration.access());
        run.setApprovalRequired(registration.approvalRequired());
        run.setRegistrationPolicy(toLegacyPolicy(registration.access(), registration.approvalRequired()));
        if (request.registrationStartAt() != null) {
            run.setRegistrationStartAt(request.registrationStartAt());
        }
        if (request.registrationEndAt() != null) {
            run.setRegistrationEndAt(request.registrationEndAt());
        }
        if (request.maxParticipants() != null) {
            run.setMaxParticipants(normalizeMaxParticipants(request.maxParticipants()));
        }
        run.setUpdatedAt(Instant.now());
        try {
            contestRunMapper.updateById(run);
        } catch (DuplicateKeyException duplicate) {
            throw new DomainException(ErrorCode.CONFLICT, "Contest run title already exists");
        }
        replaceAllowedGroups(contestId, runId, registration.allowedGroupIds(), Instant.now());
        return toRunResponse(run);
    }

    @Transactional
    public ContestRunResponse publishRun(Long contestId, Long runId) {
        ContestEntity contest = requireManagedContest(contestId);
        assertContestConfirmed(contest);
        ContestRunEntity run = requireRun(contestId, runId);
        assertRunIsNotExpiredDraft(run);
        if (run.getStatus() != ContestRunStatus.DRAFT) {
            throw new DomainException(ErrorCode.BAD_REQUEST, "Only draft contest runs can be published");
        }
        requireUniqueRunTitle(runId, run.getTitle());
        List<ContestProblemEntity> problems = contestProblems(contestId);
        if (problems.isEmpty()) {
            throw new DomainException(ErrorCode.BAD_REQUEST, "Contest run must have at least one problem");
        }
        validateScoringRules(problems);
        createProblemSnapshots(contest, run, problems);
        run.setContestTitleSnapshot(contest.getTitle());
        run.setContestDescriptionSnapshot(contest.getDescription());
        run.setModeSnapshot(contest.getMode());
        run.setPenaltyMinutesSnapshot(contest.getPenaltyMinutes() == null ? 20 : contest.getPenaltyMinutes());
        run.setCePenaltySnapshot(Boolean.TRUE.equals(contest.getCePenalty()));
        run.setAiPolicyModeSnapshot(contest.getAiPolicyMode() == null ? ContestAiPolicyMode.DEFAULT : contest.getAiPolicyMode());
        run.setAiPolicyNotesSnapshot(contest.getAiPolicyNotes());
        run.setStatus(ContestRunStatus.SCHEDULED);
        run.setUpdatedAt(Instant.now());
        contestRunMapper.updateById(run);
        eventPublisher.publishEvent(new ContestInvitationNotificationRequestedEvent(run.getId()));
        return toRunResponse(run);
    }

    @Transactional
    public ContestRunResponse archiveRun(Long contestId, Long runId, String reason) {
        ContestEntity contest = requireManagedContest(contestId);
        assertContestConfirmed(contest);
        ContestRunEntity run = requireRun(contestId, runId);
        if (ContestRunStatePolicy.isArchived(run)) {
            return toRunResponse(run);
        }
        Instant now = Instant.now();
        ContestRunStatus statusBeforeArchive = ContestRunStatePolicy.effectiveStatus(run, now);
        run.setStatusBeforeArchive(statusBeforeArchive == ContestRunStatus.EXPIRED
                ? ContestRunStatus.DRAFT : statusBeforeArchive);
        run.setStatus(ContestRunStatus.ARCHIVED);
        run.setArchivedAt(now);
        run.setArchiveReason(StringUtils.hasText(reason) ? reason.trim() : null);
        run.setUpdatedAt(now);
        contestRunMapper.updateById(run);
        return toRunResponse(run);
    }

    @Transactional
    public ContestRunResponse restoreRun(Long contestId, Long runId) {
        ContestEntity contest = requireManagedContest(contestId);
        assertContestConfirmed(contest);
        ContestRunEntity run = requireRun(contestId, runId);
        if (!ContestRunStatePolicy.isArchived(run) && run.getArchivedAt() == null) {
            throw new DomainException(ErrorCode.BAD_REQUEST, "Only archived contest runs can be restored");
        }
        requireUniqueRunTitle(runId, run.getTitle());
        Instant now = Instant.now();
        if ((run.getStatusBeforeArchive() == ContestRunStatus.DRAFT
                || run.getStatusBeforeArchive() == ContestRunStatus.EXPIRED)
                && ContestRunStatePolicy.hasElapsedEndAt(run, now)) {
            throw new DomainException(ErrorCode.BAD_REQUEST, "Expired contest runs can only be archived");
        }
        run.setStatus(ContestRunStatePolicy.restoredStatus(run, now));
        run.setStatusBeforeArchive(null);
        run.setArchivedAt(null);
        run.setArchiveReason(null);
        run.setUpdatedAt(now);
        try {
            contestRunMapper.updateById(run);
        } catch (DuplicateKeyException duplicate) {
            throw new DomainException(ErrorCode.CONFLICT, "Contest run title already exists");
        }
        return toRunResponse(run);
    }

    @Transactional
    public ContestRunResponse deleteRun(Long contestId, Long runId) {
        ContestEntity contest = requireManagedContest(contestId);
        assertContestConfirmed(contest);
        ContestRunEntity run = requireRun(contestId, runId);
        if (!ContestRunStatePolicy.isArchived(run)) {
            throw new DomainException(ErrorCode.BAD_REQUEST, "Only archived contest runs can be deleted");
        }
        Instant now = Instant.now();
        run.setDeletedAt(now);
        run.setDeletedBy(SecuritySupport.currentUserId());
        run.setUpdatedAt(now);
        contestRunMapper.updateById(run);
        return toRunResponse(run);
    }

    public PageResponse<ContestOpenRunResponse> openRuns(ContestRunStatus status, String keyword, ContestMode mode,
                                                         ContestRegistrationStatus registrationStatus,
                                                         long page, long pageSize) {
        Long userId = SecuritySupport.currentUserId();
        Instant now = Instant.now();
        LambdaQueryWrapper<ContestRunEntity> query = new LambdaQueryWrapper<ContestRunEntity>()
                .isNull(ContestRunEntity::getDeletedAt)
                .ne(ContestRunEntity::getStatus, ContestRunStatus.DRAFT)
                .ne(ContestRunEntity::getStatus, ContestRunStatus.ARCHIVED)
                .like(StringUtils.hasText(keyword), ContestRunEntity::getTitle, keyword == null ? null : keyword.trim())
                .orderByDesc(ContestRunEntity::getCreatedAt)
                .orderByDesc(ContestRunEntity::getId);
        applyRunStatusFilter(query, status, now);
        Page<ContestRunEntity> result = contestRunMapper.selectPage(new Page<>(normalizePage(page), normalizePageSize(pageSize)), query);
        Map<Long, ContestEntity> contests = mapById(result.getRecords().stream().map(ContestRunEntity::getContestId).toList(),
                contestMapper::selectBatchIds, ContestEntity::getId);
        List<ContestOpenRunResponse> records = result.getRecords().stream()
                .map(run -> openRunResponse(contests.get(run.getContestId()), run, userId))
                .filter(Objects::nonNull)
                .filter(response -> response.contest() != null)
                .filter(response -> mode == null || response.contest().mode() == mode)
                .filter(response -> registrationStatus == null
                        || (response.registration() != null && response.registration().status() == registrationStatus))
                .toList();
        return new PageResponse<>(records, result.getTotal(), result.getCurrent(), result.getSize());
    }

    @Transactional
    public ContestOpenRunResponse openRun(Long contestId, Long runId) {
        Long userId = SecuritySupport.currentUserId();
        ContestEntity contest = requireContest(contestId);
        ContestRunEntity run = requireRun(contestId, runId);
        ContestOpenRunResponse response = openRunResponse(contest, run, userId);
        if (response == null) {
            throw new DomainException(ErrorCode.FORBIDDEN, "Cannot access contest run");
        }
        return response;
    }

    @Transactional
    public ContestRegistrationResponse register(Long contestId, Long runId) {
        ContestEntity contest = requireContest(contestId);
        ContestRunEntity run = requireRun(contestId, runId);
        Long userId = SecuritySupport.currentUserId();
        assertRunSelfRegistrationAllowed(contest, run, userId);
        ContestRegistrationEntity registration = registrationMapper.selectOne(new LambdaQueryWrapper<ContestRegistrationEntity>()
                .eq(ContestRegistrationEntity::getContestRunId, runId)
                .eq(ContestRegistrationEntity::getUserId, userId));
        Instant now = Instant.now();
        if (registration == null) {
            registration = new ContestRegistrationEntity();
            registration.setContestId(contestId);
            registration.setContestRunId(runId);
            registration.setUserId(userId);
            registration.setRequestedAt(now);
            registration.setCreatedAt(now);
        } else if (registration.getStatus() == ContestRegistrationStatus.APPROVED
                || registration.getStatus() == ContestRegistrationStatus.PENDING) {
            return toRegistrationResponse(registration, userAccountMapper.selectById(userId));
        }
        ContestRegistrationStatus status = Boolean.TRUE.equals(run.getApprovalRequired())
                ? ContestRegistrationStatus.PENDING
                : ContestRegistrationStatus.APPROVED;
        registration.setRequestedAt(now);
        registration.setStatus(status);
        registration.setCancelledAt(null);
        registration.setRejectedAt(null);
        registration.setRejectReason(null);
        registration.setApprovedAt(null);
        registration.setReviewedBy(null);
        registration.setUpdatedAt(now);
        if (status == ContestRegistrationStatus.APPROVED) {
            registration.setApprovedAt(now);
        }
        if (registration.getId() == null) {
            registrationMapper.insert(registration);
        } else {
            registrationMapper.updateById(registration);
        }
        if (status == ContestRegistrationStatus.APPROVED) {
            upsertParticipant(contest, run, userAccountMapper.selectById(userId), now, "SELF_REGISTER");
        }
        return toRegistrationResponse(registration, userAccountMapper.selectById(userId));
    }

    @Transactional
    public ContestRegistrationResponse cancelMyRegistration(Long contestId, Long runId) {
        ContestRunEntity run = requireRun(contestId, runId);
        if (!Instant.now().isBefore(run.getStartAt())) {
            throw new DomainException(ErrorCode.BAD_REQUEST, "Registration cannot be cancelled after the run starts");
        }
        Long userId = SecuritySupport.currentUserId();
        ContestRegistrationEntity registration = registrationMapper.selectOne(new LambdaQueryWrapper<ContestRegistrationEntity>()
                .eq(ContestRegistrationEntity::getContestRunId, runId)
                .eq(ContestRegistrationEntity::getUserId, userId));
        if (registration == null) {
            throw new DomainException(ErrorCode.NOT_FOUND, "Contest registration not found");
        }
        Instant now = Instant.now();
        registration.setStatus(ContestRegistrationStatus.CANCELLED);
        registration.setCancelledAt(now);
        registration.setUpdatedAt(now);
        registrationMapper.updateById(registration);
        ContestParticipantEntity participant = participantMapper.selectOne(new LambdaQueryWrapper<ContestParticipantEntity>()
                .eq(ContestParticipantEntity::getContestRunId, runId)
                .eq(ContestParticipantEntity::getUserId, userId));
        if (participant != null) {
            participant.setStatus(ContestParticipantStatus.WITHDRAWN);
            participant.setUpdatedAt(now);
            participantMapper.updateById(participant);
            writeParticipantSnapshot(participant, "REGISTRATION_CANCELLED", now);
        }
        return toRegistrationResponse(registration, userAccountMapper.selectById(userId));
    }

    public PageResponse<ContestRegistrationResponse> listRegistrations(Long contestId, Long runId,
                                                                       ContestRegistrationStatus status, String keyword,
                                                                       long page, long pageSize) {
        ContestEntity contest = requireManagedContest(contestId);
        assertContestConfirmed(contest);
        requireRun(contestId, runId);
        LambdaQueryWrapper<ContestRegistrationEntity> query = new LambdaQueryWrapper<ContestRegistrationEntity>()
                .eq(ContestRegistrationEntity::getContestRunId, runId)
                .eq(status != null, ContestRegistrationEntity::getStatus, status)
                .orderByDesc(ContestRegistrationEntity::getRequestedAt)
                .orderByDesc(ContestRegistrationEntity::getId);
        Page<ContestRegistrationEntity> result = registrationMapper.selectPage(new Page<>(normalizePage(page), normalizePageSize(pageSize)), query);
        Map<Long, UserAccountEntity> users = mapById(result.getRecords().stream().map(ContestRegistrationEntity::getUserId).toList(),
                userAccountMapper::selectBatchIds, UserAccountEntity::getId);
        List<ContestRegistrationResponse> records = result.getRecords().stream()
                .map(registration -> toRegistrationResponse(registration, users.get(registration.getUserId())))
                .filter(response -> !StringUtils.hasText(keyword)
                        || contains(response.account(), keyword)
                        || contains(response.displayName(), keyword)
                        || contains(response.email(), keyword))
                .toList();
        return new PageResponse<>(records, result.getTotal(), result.getCurrent(), result.getSize());
    }

    @Transactional
    public ContestRegistrationResponse approve(Long contestId, Long runId, Long registrationId) {
        ContestEntity contest = requireManagedContest(contestId);
        assertContestConfirmed(contest);
        ContestRunEntity run = requireRun(contestId, runId);
        assertRunIsNotExpiredDraft(run);
        assertParticipantCapacity(run);
        ContestRegistrationEntity registration = requireRegistration(runId, registrationId);
        UserAccountEntity user = requireEnabledUser(registration.getUserId());
        Instant now = Instant.now();
        registration.setStatus(ContestRegistrationStatus.APPROVED);
        registration.setReviewedBy(SecuritySupport.currentUserId());
        registration.setApprovedAt(now);
        registration.setRejectedAt(null);
        registration.setRejectReason(null);
        registration.setUpdatedAt(now);
        registrationMapper.updateById(registration);
        upsertParticipant(contest, run, user, now, "REGISTRATION_APPROVED");
        return toRegistrationResponse(registration, user);
    }

    @Transactional
    public ContestRegistrationResponse reject(Long contestId, Long runId, Long registrationId, String reason) {
        ContestEntity contest = requireManagedContest(contestId);
        assertContestConfirmed(contest);
        ContestRunEntity run = requireRun(contestId, runId);
        assertRunIsNotExpiredDraft(run);
        ContestRegistrationEntity registration = requireRegistration(runId, registrationId);
        Instant now = Instant.now();
        registration.setStatus(ContestRegistrationStatus.REJECTED);
        registration.setReviewedBy(SecuritySupport.currentUserId());
        registration.setRejectedAt(now);
        registration.setRejectReason(StringUtils.hasText(reason) ? reason.trim() : null);
        registration.setUpdatedAt(now);
        registrationMapper.updateById(registration);
        return toRegistrationResponse(registration, userAccountMapper.selectById(registration.getUserId()));
    }

    @Transactional
    public ContestRegistrationResponse invite(Long contestId, Long runId, ContestParticipantAddRequest request) {
        ContestEntity contest = requireManagedContest(contestId);
        assertContestConfirmed(contest);
        ContestRunEntity run = requireRun(contestId, runId);
        assertRunIsNotExpiredDraft(run);
        assertRunCanReceiveInvitations(run);
        UserAccountEntity user = resolveParticipantUser(request);
        ContestParticipantEntity existingParticipant = participantFor(runId, user.getId());
        if (existingParticipant == null || existingParticipant.getStatus() != ContestParticipantStatus.ACTIVE) {
            assertParticipantCapacity(run);
        }
        Instant now = Instant.now();
        // Invitations no longer add the user as a participant directly: the student
        // must accept the INVITED registration before a participant row is created.
        InvitedRegistrationResult invitation = upsertInvitedRegistration(contestId, runId, user, now);
        ContestRegistrationEntity registration = invitation.registration();
        if (registration.getStatus() == ContestRegistrationStatus.INVITED
                && isInvitationNotificationDispatchable(contest, run, now)) {
            eventPublisher.publishEvent(new ContestInvitationNotificationRequestedEvent(runId));
        }
        return toRegistrationResponse(registration, user);
    }

    @Transactional
    public ContestInvitationBatchResponse inviteBatch(Long contestId, Long runId, ContestInvitationBatchRequest request) {
        ContestEntity contest = requireManagedContest(contestId);
        assertContestConfirmed(contest);
        ContestRunEntity run = requireRun(contestId, runId);
        assertRunIsNotExpiredDraft(run);
        assertRunCanReceiveInvitations(run);
        List<Long> userIds = normalizeInvitationTargets(request);
        Instant now = Instant.now();
        List<ContestInvitationBatchResultItem> results = new ArrayList<>();
        boolean dispatchRequested = false;
        for (Long userId : userIds) {
            try {
                UserAccountEntity user = requireEnabledUser(userId);
                ContestParticipantEntity existingParticipant = participantFor(runId, user.getId());
                if (existingParticipant == null || existingParticipant.getStatus() != ContestParticipantStatus.ACTIVE) {
                    assertParticipantCapacity(run);
                }
                InvitedRegistrationResult invitation = upsertInvitedRegistration(contestId, runId, user, now);
                ContestInvitationBatchItemStatus status = invitationStatus(contest, run, invitation.registration(), now);
                dispatchRequested |= status == ContestInvitationBatchItemStatus.QUEUED_FOR_NOTIFICATION;
                results.add(new ContestInvitationBatchResultItem(user.getId(), user.getAccount(), user.getDisplayName(),
                        status, invitationMessage(status)));
            } catch (DomainException exception) {
                results.add(new ContestInvitationBatchResultItem(userId, null, null,
                        ContestInvitationBatchItemStatus.FAILED, safeInvitationFailureMessage(exception)));
            }
        }
        if (dispatchRequested) {
            eventPublisher.publishEvent(new ContestInvitationNotificationRequestedEvent(runId));
        }
        int failed = (int) results.stream()
                .filter(result -> result.status() == ContestInvitationBatchItemStatus.FAILED)
                .count();
        return new ContestInvitationBatchResponse(results.size(), results.size() - failed, failed, results);
    }

    public PageResponse<ContestRegistrationResponse> listMyInvitations(long page, long pageSize) {
        Long userId = SecuritySupport.currentUserId();
        Page<ContestRegistrationEntity> result = registrationMapper.selectVisibleInvitedPage(
                new Page<>(normalizePage(page), normalizePageSize(pageSize)), userId, Instant.now());
        UserAccountEntity user = userAccountMapper.selectById(userId);
        return new PageResponse<>(result.getRecords().stream()
                .map(registration -> toRegistrationResponse(registration, user))
                .toList(), result.getTotal(), result.getCurrent(), result.getSize());
    }

    @Transactional
    public ContestRegistrationResponse acceptInvitation(Long contestId, Long runId) {
        ContestEntity contest = requireContest(contestId);
        ContestRunEntity run = requireRun(contestId, runId);
        Long userId = SecuritySupport.currentUserId();
        ContestRegistrationEntity registration = requireMyPendingInvitation(runId, userId);
        assertRunAcceptsInvitation(contest, run);
        assertParticipantCapacity(run);
        UserAccountEntity user = requireEnabledUser(userId);
        Instant now = Instant.now();
        registration.setStatus(ContestRegistrationStatus.APPROVED);
        registration.setReviewedBy(null);
        registration.setApprovedAt(now);
        registration.setRejectedAt(null);
        registration.setRejectReason(null);
        registration.setCancelledAt(null);
        registration.setUpdatedAt(now);
        registrationMapper.updateById(registration);
        upsertParticipant(contest, run, user, now, "INVITE_ACCEPT");
        notificationService.markContestInvitationRead(userId, registration.getId());
        return toRegistrationResponse(registration, user);
    }

    @Transactional
    public ContestRegistrationResponse declineInvitation(Long contestId, Long runId) {
        Long userId = SecuritySupport.currentUserId();
        ContestRegistrationEntity registration = requireMyPendingInvitation(runId, userId);
        Instant now = Instant.now();
        registration.setStatus(ContestRegistrationStatus.DECLINED);
        registration.setCancelledAt(now);
        registration.setUpdatedAt(now);
        registrationMapper.updateById(registration);
        notificationService.markContestInvitationRead(userId, registration.getId());
        return toRegistrationResponse(registration, userAccountMapper.selectById(userId));
    }

    private ContestRegistrationEntity requireMyPendingInvitation(Long runId, Long userId) {
        ContestRegistrationEntity registration = registrationFor(runId, userId);
        if (registration == null) {
            throw new DomainException(ErrorCode.NOT_FOUND, "Contest invitation not found");
        }
        if (registration.getStatus() != ContestRegistrationStatus.INVITED) {
            throw new DomainException(ErrorCode.CONFLICT, "Contest invitation is no longer pending");
        }
        return registration;
    }

    private void assertRunAcceptsInvitation(ContestEntity contest, ContestRunEntity run) {
        if (contest.getStatus() != ContestStatus.PUBLISHED || run.getStatus() == ContestRunStatus.DRAFT) {
            throw new DomainException(ErrorCode.BAD_REQUEST, "Contest run is not published");
        }
        if (run.getStatus() == ContestRunStatus.ARCHIVED) {
            throw new DomainException(ErrorCode.BAD_REQUEST, "Archived contest run no longer accepts invitations");
        }
        if (run.getEndAt() != null && !Instant.now().isBefore(run.getEndAt())) {
            throw new DomainException(ErrorCode.BAD_REQUEST, "Contest run has already ended");
        }
    }

    public List<ContestRunProblemSnapshotResponse> problemSnapshots(Long contestId, Long runId) {
        ContestEntity contest = requireContest(contestId);
        ContestRunEntity run = requireRun(contestId, runId);
        Long userId = SecuritySupport.currentUserId();
        if (!canManage(contest) && !canViewProblems(run, userId)) {
            throw new DomainException(ErrorCode.FORBIDDEN, "Cannot access contest run problems");
        }
        if (!canManage(contest) && Instant.now().isBefore(run.getStartAt())) {
            throw new DomainException(ErrorCode.FORBIDDEN, "Contest run problems are not visible yet");
        }
        // Hidden problems stay hidden for every identity on student-facing surfaces; staff
        // review of private problems belongs to the admin console, not the student app.
        Set<Long> hiddenProblemIds = visibilityService.hiddenProblemIdsForRun(run, Instant.now());
        // Always report the real problem visibility so clients can hide contest-specific
        // affordances (such as AI assistance) for private problems, even for managers.
        Map<Long, ProblemVisibility> visibilityByProblem = visibilityService.problemVisibilityMap(run.getContestId());
        return problemSnapshotMapper.selectList(new LambdaQueryWrapper<ContestRunProblemSnapshotEntity>()
                .eq(ContestRunProblemSnapshotEntity::getContestRunId, runId)
                .orderByAsc(ContestRunProblemSnapshotEntity::getSortOrder)
                .orderByAsc(ContestRunProblemSnapshotEntity::getId))
                .stream()
                .filter(snapshot -> !hiddenProblemIds.contains(snapshot.getProblemId()))
                .map(snapshot -> toProblemSnapshotResponse(snapshot,
                        visibilityByProblem.getOrDefault(snapshot.getProblemId(), ProblemVisibility.PUBLIC)))
                .toList();
    }

    ContestRunEntity requireRun(Long contestId, Long runId) {
        ContestRunEntity run = contestRunMapper.selectById(runId);
        if (run == null || run.getDeletedAt() != null || !Objects.equals(run.getContestId(), contestId)) {
            throw new DomainException(ErrorCode.NOT_FOUND, "Contest run not found");
        }
        return run;
    }

    boolean canManage(ContestEntity contest) {
        return SecuritySupport.hasRole(Role.ADMIN)
                || (SecuritySupport.hasRole(Role.TEACHER) && contest.getOwnerUserId().equals(SecuritySupport.currentUserId()));
    }

    ContestEntity requireContest(Long contestId) {
        ContestEntity contest = contestMapper.selectById(contestId);
        if (contest == null || contest.getDeletedAt() != null) {
            throw new DomainException(ErrorCode.NOT_FOUND, "Contest not found");
        }
        return contest;
    }

    private ContestEntity requireManagedContest(Long contestId) {
        ContestEntity contest = requireContest(contestId);
        if (!canManage(contest)) {
            throw new DomainException(ErrorCode.FORBIDDEN, "Cannot manage contest runs");
        }
        return contest;
    }

    private void assertContestConfirmed(ContestEntity contest) {
        if (contest.getStatus() == ContestStatus.DRAFT) {
            throw new DomainException(ErrorCode.BAD_REQUEST, "Contest blueprint is not confirmed");
        }
        if (contest.getStatus() == ContestStatus.ARCHIVED) {
            throw new DomainException(ErrorCode.BAD_REQUEST, "Archived contest cannot be managed");
        }
    }

    private boolean canViewOpenRun(ContestEntity contest, ContestRunEntity run) {
        if (contest.getDeletedAt() != null || run.getDeletedAt() != null
                || contest.getStatus() != ContestStatus.PUBLISHED || run.getStatus() == ContestRunStatus.DRAFT
                || run.getStatus() == ContestRunStatus.ARCHIVED) {
            return false;
        }
        Long userId = SecuritySupport.currentUserId();
        return effectiveRegistrationAccess(run) == ContestRegistrationAccess.PUBLIC
                || isAllowedGroupStudent(run.getId(), userId)
                || registrationFor(run.getId(), userId) != null
                || participantFor(run.getId(), userId) != null;
    }

    private ContestOpenRunResponse openRunResponse(ContestEntity contest, ContestRunEntity run, Long userId) {
        if (contest == null || contest.getStatus() != ContestStatus.PUBLISHED) {
            return null;
        }
        if (!canViewOpenRun(contest, run)) {
            return null;
        }
        ContestRegistrationEntity registration = registrationFor(run.getId(), userId);
        ContestParticipantEntity participant = participantFor(run.getId(), userId);
        boolean full = isFull(run);
        boolean canRegister = canRegister(contest, run, userId, full, registration);
        boolean participantActive = participant != null && participant.getStatus() == ContestParticipantStatus.ACTIVE;
        boolean canSubmit = participantActive && runAcceptsSubmissions(run, Instant.now());
        boolean canViewProblems = participantActive && runContentIsVisible(run, Instant.now());
        boolean canViewScoreboard = canViewPublicScoreboard(run, userId, Instant.now());
        return new ContestOpenRunResponse(toContestResponse(contest), toRunResponse(run), canRegister, canSubmit,
                canViewProblems, canViewScoreboard, full,
                registration == null ? null : toPublicRegistrationResponse(registration, userAccountMapper.selectById(userId)),
                participant == null ? null : toPublicParticipantResponse(participant));
    }

    private boolean canRegister(ContestEntity contest, ContestRunEntity run, Long userId, boolean full,
                                ContestRegistrationEntity registration) {
        if (full || run.getStatus() == ContestRunStatus.DRAFT || run.getStatus() == ContestRunStatus.ARCHIVED) {
            return false;
        }
        if (registration != null && (registration.getStatus() == ContestRegistrationStatus.APPROVED
                || registration.getStatus() == ContestRegistrationStatus.PENDING)) {
            return false;
        }
        return isRegistrationWindowOpen(run, Instant.now()) && switch (effectiveRegistrationAccess(run)) {
            case PUBLIC -> true;
            case GROUPS -> isAllowedGroupStudent(run.getId(), userId);
            case INVITE_ONLY -> false;
        };
    }

    private void assertRunSelfRegistrationAllowed(ContestEntity contest, ContestRunEntity run, Long userId) {
        if (!canRegister(contest, run, userId, isFull(run), registrationFor(run.getId(), userId))) {
            throw new DomainException(ErrorCode.FORBIDDEN, "Contest run is not open for self registration");
        }
        assertParticipantCapacity(run);
    }

    private void assertParticipantCapacity(ContestRunEntity run) {
        if (run.getMaxParticipants() == null || run.getMaxParticipants() <= 0) {
            return;
        }
        long active = participantMapper.selectCount(new LambdaQueryWrapper<ContestParticipantEntity>()
                .eq(ContestParticipantEntity::getContestRunId, run.getId())
                .eq(ContestParticipantEntity::getStatus, ContestParticipantStatus.ACTIVE));
        if (active >= run.getMaxParticipants()) {
            throw new DomainException(ErrorCode.BAD_REQUEST, "Contest run participant limit reached");
        }
    }

    private boolean isFull(ContestRunEntity run) {
        if (run.getMaxParticipants() == null || run.getMaxParticipants() <= 0) {
            return false;
        }
        return participantMapper.selectCount(new LambdaQueryWrapper<ContestParticipantEntity>()
                .eq(ContestParticipantEntity::getContestRunId, run.getId())
                .eq(ContestParticipantEntity::getStatus, ContestParticipantStatus.ACTIVE)) >= run.getMaxParticipants();
    }

    private boolean canSubmit(ContestRunEntity run, Long userId) {
        ContestParticipantEntity participant = participantFor(run.getId(), userId);
        return participant != null && participant.getStatus() == ContestParticipantStatus.ACTIVE
                && runAcceptsSubmissions(run, Instant.now());
    }

    private boolean canViewProblems(ContestRunEntity run, Long userId) {
        ContestParticipantEntity participant = participantFor(run.getId(), userId);
        return participant != null && participant.getStatus() == ContestParticipantStatus.ACTIVE
                && runContentIsVisible(run, Instant.now());
    }

    private boolean canViewPublicScoreboard(ContestRunEntity run, Long userId, Instant now) {
        if (!runContentIsVisible(run, now)) {
            return false;
        }
        ContestParticipantEntity participant = participantFor(run.getId(), userId);
        boolean ended = !now.isBefore(run.getEndAt());
        if (!ended) {
            return participant != null && participant.getStatus() == ContestParticipantStatus.ACTIVE;
        }
        return switch (effectiveRegistrationAccess(run)) {
            case PUBLIC -> true;
            case GROUPS -> isAllowedGroupStudent(run.getId(), userId) || participant != null;
            case INVITE_ONLY -> participant != null;
        };
    }

    private boolean runContentIsVisible(ContestRunEntity run, Instant now) {
        return run.getStatus() != ContestRunStatus.DRAFT
                && run.getStatus() != ContestRunStatus.ARCHIVED
                && !now.isBefore(run.getStartAt());
    }

    private boolean runAcceptsSubmissions(ContestRunEntity run, Instant now) {
        return runContentIsVisible(run, now)
                && !now.isBefore(run.getStartAt())
                && now.isBefore(run.getEndAt());
    }

    private ContestRegistrationEntity registrationFor(Long runId, Long userId) {
        return registrationMapper.selectOne(new LambdaQueryWrapper<ContestRegistrationEntity>()
                .eq(ContestRegistrationEntity::getContestRunId, runId)
                .eq(ContestRegistrationEntity::getUserId, userId));
    }

    private ContestParticipantEntity participantFor(Long runId, Long userId) {
        return participantMapper.selectOne(new LambdaQueryWrapper<ContestParticipantEntity>()
                .eq(ContestParticipantEntity::getContestRunId, runId)
                .eq(ContestParticipantEntity::getUserId, userId));
    }

    private InvitedRegistrationResult upsertInvitedRegistration(Long contestId, Long runId,
                                                                 UserAccountEntity user, Instant now) {
        ContestRegistrationEntity registration = registrationFor(runId, user.getId());
        if (registration != null && (registration.getStatus() == ContestRegistrationStatus.APPROVED
                || registration.getStatus() == ContestRegistrationStatus.PENDING
                || registration.getStatus() == ContestRegistrationStatus.INVITED)) {
            // Already approved, awaiting self-registration approval, or already invited: keep as-is.
            return new InvitedRegistrationResult(registration, false);
        }
        boolean insert = registration == null;
        if (registration == null) {
            registration = new ContestRegistrationEntity();
            registration.setContestId(contestId);
            registration.setContestRunId(runId);
            registration.setUserId(user.getId());
            registration.setCreatedAt(now);
        }
        markRegistrationInvited(registration, now);
        try {
            if (insert) {
                registrationMapper.insert(registration);
            } else {
                registrationMapper.updateById(registration);
            }
        } catch (DuplicateKeyException duplicate) {
            ContestRegistrationEntity existing = registrationFor(runId, user.getId());
            if (existing == null) {
                throw new DomainException(ErrorCode.CONFLICT, "Contest registration already exists");
            }
            return new InvitedRegistrationResult(existing, false);
        }
        return new InvitedRegistrationResult(registration, true);
    }

    private record InvitedRegistrationResult(ContestRegistrationEntity registration, boolean notificationRequired) {
    }

    private void markRegistrationInvited(ContestRegistrationEntity registration, Instant now) {
        registration.setStatus(ContestRegistrationStatus.INVITED);
        registration.setRequestedAt(now);
        registration.setReviewedBy(null);
        registration.setApprovedAt(null);
        registration.setRejectedAt(null);
        registration.setRejectReason(null);
        registration.setCancelledAt(null);
        long nextNotificationVersion = Math.max(0L, valueOrZero(registration.getInvitationNotificationVersion())) + 1L;
        registration.setInvitationNotificationVersion(nextNotificationVersion);
        registration.setInvitationNotificationDeliveredVersion(0L);
        registration.setUpdatedAt(now);
    }

    private List<Long> normalizeInvitationTargets(ContestInvitationBatchRequest request) {
        if (request == null || request.userIds() == null || request.userIds().isEmpty()) {
            throw new DomainException(ErrorCode.BAD_REQUEST, "Invitation user selection is required");
        }
        List<Long> targets = new ArrayList<>(new LinkedHashSet<>(request.userIds().stream()
                .filter(Objects::nonNull)
                .toList()));
        if (targets.isEmpty()) {
            throw new DomainException(ErrorCode.BAD_REQUEST, "Invitation user selection is required");
        }
        if (targets.size() > 100) {
            throw new DomainException(ErrorCode.BAD_REQUEST, "Too many invitation targets");
        }
        return targets;
    }

    private ContestInvitationBatchItemStatus invitationStatus(ContestEntity contest, ContestRunEntity run,
                                                               ContestRegistrationEntity registration, Instant now) {
        if (registration == null || registration.getStatus() != ContestRegistrationStatus.INVITED) {
            return ContestInvitationBatchItemStatus.UNCHANGED;
        }
        if (!isInvitationNotificationDispatchable(contest, run, now)) {
            return ContestInvitationBatchItemStatus.SAVED_FOR_PUBLISH;
        }
        return valueOrZero(registration.getInvitationNotificationDeliveredVersion())
                < valueOrZero(registration.getInvitationNotificationVersion())
                ? ContestInvitationBatchItemStatus.QUEUED_FOR_NOTIFICATION
                : ContestInvitationBatchItemStatus.UNCHANGED;
    }

    private boolean isInvitationNotificationDispatchable(ContestEntity contest, ContestRunEntity run, Instant now) {
        return contest.getStatus() == ContestStatus.PUBLISHED
                && run.getStatus() != ContestRunStatus.DRAFT
                && run.getStatus() != ContestRunStatus.ARCHIVED
                && run.getEndAt() != null
                && now.isBefore(run.getEndAt());
    }

    private void assertRunCanReceiveInvitations(ContestRunEntity run) {
        if (run.getStatus() == ContestRunStatus.ARCHIVED) {
            throw new DomainException(ErrorCode.BAD_REQUEST, "Archived contest run cannot receive invitations");
        }
        if (run.getEndAt() != null && !Instant.now().isBefore(run.getEndAt())) {
            throw new DomainException(ErrorCode.BAD_REQUEST, "Contest run has already ended");
        }
    }

    private void assertRunIsNotExpiredDraft(ContestRunEntity run) {
        if (run.getStatus() == ContestRunStatus.EXPIRED
                || ContestRunStatePolicy.isExpiredDraft(run, Instant.now())) {
            throw new DomainException(ErrorCode.BAD_REQUEST, "Expired contest runs can only be archived");
        }
    }

    private String invitationMessage(ContestInvitationBatchItemStatus status) {
        return switch (status) {
            case SAVED_FOR_PUBLISH -> "Invitation saved and will be sent after publication";
            case QUEUED_FOR_NOTIFICATION -> "Invitation queued for notification";
            case UNCHANGED -> "Invitation is already current";
            case FAILED -> "User cannot be invited";
        };
    }

    private String safeInvitationFailureMessage(DomainException exception) {
        if (exception.errorCode() == ErrorCode.NOT_FOUND) {
            return "User is unavailable";
        }
        if (exception.errorCode() == ErrorCode.BAD_REQUEST) {
            return "User cannot be invited for this run";
        }
        return "Invitation could not be created";
    }

    private long valueOrZero(Long value) {
        return value == null ? 0L : value;
    }

    private UserAccountEntity resolveParticipantUser(ContestParticipantAddRequest request) {
        if (request == null) {
            throw new DomainException(ErrorCode.BAD_REQUEST, "Participant user is required");
        }
        if (request.userId() != null) {
            UserAccountEntity user = userAccountMapper.selectById(request.userId());
            if (user != null) {
                return requireEnabledUser(user.getId());
            }
            UserAccountEntity accountMatched = userAccountMapper.selectOne(new LambdaQueryWrapper<UserAccountEntity>()
                    .eq(UserAccountEntity::getAccount, String.valueOf(request.userId())));
            if (accountMatched != null) {
                return requireEnabledUser(accountMatched.getId());
            }
        }
        if (StringUtils.hasText(request.account())) {
            UserAccountEntity user = userAccountMapper.selectOne(new LambdaQueryWrapper<UserAccountEntity>()
                    .eq(UserAccountEntity::getAccount, request.account().trim()));
            if (user != null) {
                return requireEnabledUser(user.getId());
            }
        }
        throw new DomainException(ErrorCode.NOT_FOUND, "User not found");
    }

    private void createProblemSnapshots(ContestEntity contest, ContestRunEntity run, List<ContestProblemEntity> problems) {
        problemSnapshotMapper.delete(new LambdaQueryWrapper<ContestRunProblemSnapshotEntity>()
                .eq(ContestRunProblemSnapshotEntity::getContestRunId, run.getId()));
        Instant now = Instant.now();
        for (ContestProblemEntity contestProblem : problems) {
            ProblemEntity problem = problemCatalog.findActive(contestProblem.getProblemId())
                    .orElseThrow(() -> new DomainException(ErrorCode.NOT_FOUND, "Problem not found"));
            ContestRunProblemSnapshotEntity snapshot = new ContestRunProblemSnapshotEntity();
            snapshot.setContestId(contest.getId());
            snapshot.setContestRunId(run.getId());
            snapshot.setContestProblemId(contestProblem.getId());
            snapshot.setProblemId(problem.getId());
            snapshot.setLabel(contestProblem.getLabel());
            snapshot.setDisplayTitle(contestProblem.getDisplayTitle());
            snapshot.setStatement(problem.getStatement());
            snapshot.setNotes(problem.getNotes());
            snapshot.setTags(problem.getTags());
            snapshot.setDifficulty(problem.getDifficulty());
            snapshot.setTimeLimitMillis(problem.getTimeLimitMillis());
            snapshot.setMemoryLimitKb(problem.getMemoryLimitKb());
            snapshot.setScore(contestProblem.getScore());
            snapshot.setScoringMode(scoringMode(contestProblem.getId()));
            snapshot.setVisibility(problem.getVisibility() == null ? ProblemVisibility.PUBLIC : problem.getVisibility());
            snapshot.setSortOrder(contestProblem.getSortOrder());
            snapshot.setCreatedAt(now);
            problemSnapshotMapper.insert(snapshot);
        }
    }

    private void validateScoringRules(List<ContestProblemEntity> problems) {
        for (ContestProblemEntity problem : problems) {
            if (scoringMode(problem.getId()) != ContestProblemScoringMode.SUBTASK_MIN_CASE_MAX_OVER_SUBMISSIONS) {
                continue;
            }
            TestcasePackageEntity testcasePackage = activeReadyPackage(problem.getProblemId());
            if (testcasePackage == null) {
                throw new DomainException(ErrorCode.BAD_REQUEST, "Subtask scoring requires an active READY testcase package");
            }
            List<ProblemSubtaskEntity> subtasks = subtaskMapper.selectList(new LambdaQueryWrapper<ProblemSubtaskEntity>()
                    .eq(ProblemSubtaskEntity::getTestcasePackageId, testcasePackage.getId()));
            if (subtasks.isEmpty()) {
                throw new DomainException(ErrorCode.BAD_REQUEST, "Subtask scoring requires testcase subtasks");
            }
            Set<String> subtaskKeys = subtasks.stream().map(ProblemSubtaskEntity::getSubtaskKey).collect(Collectors.toSet());
            List<TestcasePackageCaseEntity> cases = testcaseCaseMapper.selectList(new LambdaQueryWrapper<TestcasePackageCaseEntity>()
                    .eq(TestcasePackageCaseEntity::getPackageId, testcasePackage.getId()));
            for (TestcasePackageCaseEntity testcaseCase : cases) {
                if (!StringUtils.hasText(testcaseCase.getSubtaskKey()) || !subtaskKeys.contains(testcaseCase.getSubtaskKey())) {
                    throw new DomainException(ErrorCode.BAD_REQUEST, "Subtask scoring requires every testcase case to reference a valid subtask");
                }
            }
        }
    }

    private TestcasePackageEntity activeReadyPackage(Long problemId) {
        return testcasePackageMapper.selectOne(new LambdaQueryWrapper<TestcasePackageEntity>()
                .eq(TestcasePackageEntity::getProblemId, problemId)
                .eq(TestcasePackageEntity::getActive, true)
                .eq(TestcasePackageEntity::getStatus, TestcasePackageStatus.READY)
                .orderByDesc(TestcasePackageEntity::getActivatedAt)
                .orderByDesc(TestcasePackageEntity::getId)
                .last("LIMIT 1"));
    }

    private ContestProblemScoringMode scoringMode(Long contestProblemId) {
        ContestProblemScoringRuleEntity rule = scoringRuleMapper.selectOne(new LambdaQueryWrapper<ContestProblemScoringRuleEntity>()
                .eq(ContestProblemScoringRuleEntity::getContestProblemId, contestProblemId)
                .last("LIMIT 1"));
        return rule == null || rule.getScoringMode() == null
                ? ContestProblemScoringMode.CASE_SUM_BEST_SUBMISSION
                : rule.getScoringMode();
    }

    private ContestParticipantEntity upsertParticipant(ContestEntity contest, ContestRunEntity run, UserAccountEntity user,
                                                       Instant now, String reason) {
        if (user == null || Boolean.FALSE.equals(user.getEnabled())) {
            throw new DomainException(ErrorCode.BAD_REQUEST, "User account is disabled");
        }
        ContestParticipantEntity participant = participantMapper.selectOne(new LambdaQueryWrapper<ContestParticipantEntity>()
                .eq(ContestParticipantEntity::getContestId, contest.getId())
                .eq(ContestParticipantEntity::getContestRunId, run.getId())
                .eq(ContestParticipantEntity::getUserId, user.getId()));
        if (participant == null) {
            participant = new ContestParticipantEntity();
            participant.setContestId(contest.getId());
            participant.setContestRunId(run.getId());
            participant.setUserId(user.getId());
            participant.setParticipantType(ContestParticipantType.INDIVIDUAL);
            participant.setRegisteredAt(now);
            participant.setCreatedAt(now);
        }
        LearningGroupEntity group = participantGroupFor(run, user.getId());
        participant.setStatus(ContestParticipantStatus.ACTIVE);
        participant.setAccountSnapshot(snapshotAccount(user));
        participant.setDisplayNameSnapshot(snapshotDisplayName(user));
        participant.setEmailSnapshot(user.getEmail());
        participant.setScopeGroupId(group == null ? null : group.getId());
        participant.setGroupNameSnapshot(group == null ? null : group.getName());
        participant.setUpdatedAt(now);
        if (participant.getId() == null) {
            try {
                participantMapper.insert(participant);
            } catch (DuplicateKeyException duplicate) {
                ContestParticipantEntity existing = participantFor(run.getId(), user.getId());
                if (existing == null) {
                    throw new DomainException(ErrorCode.CONFLICT, "Contest participant already exists");
                }
                participant = existing;
                participant.setStatus(ContestParticipantStatus.ACTIVE);
                participant.setAccountSnapshot(snapshotAccount(user));
                participant.setDisplayNameSnapshot(snapshotDisplayName(user));
                participant.setEmailSnapshot(user.getEmail());
                participant.setScopeGroupId(group == null ? null : group.getId());
                participant.setGroupNameSnapshot(group == null ? null : group.getName());
                participant.setUpdatedAt(now);
                participantMapper.updateById(participant);
            }
        } else {
            participantMapper.updateById(participant);
        }
        writeParticipantSnapshot(participant, reason, now);
        return participant;
    }

    private void writeParticipantSnapshot(ContestParticipantEntity participant, String reason, Instant now) {
        ContestParticipantSnapshotEntity snapshot = new ContestParticipantSnapshotEntity();
        snapshot.setContestId(participant.getContestId());
        snapshot.setContestRunId(participant.getContestRunId());
        snapshot.setParticipantId(participant.getId());
        snapshot.setUserId(participant.getUserId());
        snapshot.setAccountSnapshot(participant.getAccountSnapshot());
        snapshot.setDisplayNameSnapshot(participant.getDisplayNameSnapshot());
        snapshot.setEmailSnapshot(participant.getEmailSnapshot());
        snapshot.setScopeGroupId(participant.getScopeGroupId());
        snapshot.setGroupNameSnapshot(participant.getGroupNameSnapshot());
        snapshot.setParticipantStatus(participant.getStatus());
        snapshot.setSnapshotReason(reason);
        snapshot.setCreatedAt(now);
        participantSnapshotMapper.insert(snapshot);
    }

    private ContestRegistrationEntity requireRegistration(Long runId, Long registrationId) {
        ContestRegistrationEntity registration = registrationMapper.selectOne(new LambdaQueryWrapper<ContestRegistrationEntity>()
                .eq(ContestRegistrationEntity::getId, registrationId)
                .eq(ContestRegistrationEntity::getContestRunId, runId));
        if (registration == null) {
            throw new DomainException(ErrorCode.NOT_FOUND, "Contest registration not found");
        }
        return registration;
    }

    private UserAccountEntity requireEnabledUser(Long userId) {
        UserAccountEntity user = userAccountMapper.selectById(userId);
        if (user == null || Boolean.FALSE.equals(user.getEnabled())) {
            throw new DomainException(ErrorCode.NOT_FOUND, "User not found");
        }
        return user;
    }

    private void validateSourceRun(Long contestId, Long sourceRunId) {
        if (sourceRunId == null) {
            return;
        }
        requireRun(contestId, sourceRunId);
    }

    private List<ContestProblemEntity> contestProblems(Long contestId) {
        return contestProblemMapper.selectList(new LambdaQueryWrapper<ContestProblemEntity>()
                .eq(ContestProblemEntity::getContestId, contestId)
                .orderByAsc(ContestProblemEntity::getSortOrder)
                .orderByAsc(ContestProblemEntity::getId));
    }

    private RegistrationSettings registrationSettings(ContestRegistrationAccess access, Boolean approvalRequired,
                                                      ContestRegistrationPolicy legacyPolicy,
                                                      List<Long> allowedGroupIds) {
        ContestRegistrationAccess effectiveAccess = access;
        boolean effectiveApproval = Boolean.TRUE.equals(approvalRequired);
        if (effectiveAccess == null && legacyPolicy != null) {
            effectiveAccess = switch (legacyPolicy) {
                case PUBLIC_SELF_REGISTER -> ContestRegistrationAccess.PUBLIC;
                case GROUP_SELF_REGISTER -> ContestRegistrationAccess.GROUPS;
                case APPROVAL_REQUIRED -> ContestRegistrationAccess.PUBLIC;
                case INVITE_ONLY -> ContestRegistrationAccess.INVITE_ONLY;
            };
            effectiveApproval = legacyPolicy == ContestRegistrationPolicy.APPROVAL_REQUIRED;
        }
        if (effectiveAccess == null) {
            effectiveAccess = ContestRegistrationAccess.INVITE_ONLY;
        }
        List<Long> groups = allowedGroupIds == null
                ? List.of()
                : allowedGroupIds.stream().filter(Objects::nonNull).distinct().toList();
        if (effectiveAccess != ContestRegistrationAccess.GROUPS) {
            groups = List.of();
        }
        return new RegistrationSettings(effectiveAccess, effectiveApproval, groups);
    }

    private ContestRegistrationAccess effectiveRegistrationAccess(ContestRunEntity run) {
        if (run.getRegistrationAccess() != null) {
            return run.getRegistrationAccess();
        }
        return switch (run.getRegistrationPolicy() == null ? ContestRegistrationPolicy.INVITE_ONLY : run.getRegistrationPolicy()) {
            case PUBLIC_SELF_REGISTER, APPROVAL_REQUIRED -> ContestRegistrationAccess.PUBLIC;
            case GROUP_SELF_REGISTER -> ContestRegistrationAccess.GROUPS;
            case INVITE_ONLY -> ContestRegistrationAccess.INVITE_ONLY;
        };
    }

    private ContestRegistrationPolicy toLegacyPolicy(ContestRegistrationAccess access, boolean approvalRequired) {
        if (access == ContestRegistrationAccess.INVITE_ONLY) {
            return ContestRegistrationPolicy.INVITE_ONLY;
        }
        if (approvalRequired) {
            return ContestRegistrationPolicy.APPROVAL_REQUIRED;
        }
        if (access == ContestRegistrationAccess.GROUPS) {
            return ContestRegistrationPolicy.GROUP_SELF_REGISTER;
        }
        return ContestRegistrationPolicy.PUBLIC_SELF_REGISTER;
    }

    private void validateAllowedGroups(ContestRegistrationAccess access, List<Long> groupIds) {
        if (access != ContestRegistrationAccess.GROUPS) {
            return;
        }
        if (groupIds == null || groupIds.isEmpty()) {
            throw new DomainException(ErrorCode.BAD_REQUEST, "At least one allowed user group is required");
        }
        List<LearningGroupEntity> groups = learningGroupMapper.selectBatchIds(groupIds);
        Set<Long> activeIds = groups.stream()
                .filter(group -> group.getStatus() == com.aioj.next.contract.learning.LearningGroupStatus.ACTIVE)
                .map(LearningGroupEntity::getId)
                .collect(Collectors.toSet());
        if (activeIds.size() != new HashSet<>(groupIds).size()) {
            throw new DomainException(ErrorCode.NOT_FOUND, "Allowed user group not found");
        }
    }

    private void replaceAllowedGroups(Long contestId, Long runId, List<Long> groupIds, Instant now) {
        allowedGroupMapper.delete(new LambdaQueryWrapper<ContestRunAllowedGroupEntity>()
                .eq(ContestRunAllowedGroupEntity::getContestRunId, runId));
        if (groupIds == null || groupIds.isEmpty()) {
            return;
        }
        for (Long groupId : groupIds) {
            ContestRunAllowedGroupEntity entity = new ContestRunAllowedGroupEntity();
            entity.setContestId(contestId);
            entity.setContestRunId(runId);
            entity.setGroupId(groupId);
            entity.setCreatedAt(now);
            allowedGroupMapper.insert(entity);
        }
    }

    private List<Long> allowedGroupIds(Long runId) {
        return allowedGroupMapper.selectList(new LambdaQueryWrapper<ContestRunAllowedGroupEntity>()
                        .eq(ContestRunAllowedGroupEntity::getContestRunId, runId)
                        .orderByAsc(ContestRunAllowedGroupEntity::getId))
                .stream()
                .map(ContestRunAllowedGroupEntity::getGroupId)
                .toList();
    }

    private boolean isAllowedGroupStudent(Long runId, Long userId) {
        if (runId == null || userId == null) {
            return false;
        }
        List<Long> groupIds = allowedGroupIds(runId);
        if (groupIds.isEmpty()) {
            return false;
        }
        return learningGroupMemberMapper.selectCount(new LambdaQueryWrapper<LearningGroupMemberEntity>()
                .in(LearningGroupMemberEntity::getGroupId, groupIds)
                .eq(LearningGroupMemberEntity::getUserId, userId)
                .eq(LearningGroupMemberEntity::getRole, LearningGroupMemberRole.STUDENT)) > 0;
    }

    private LearningGroupEntity participantGroupFor(ContestRunEntity run, Long userId) {
        List<Long> groupIds = allowedGroupIds(run.getId());
        if (groupIds.isEmpty() || userId == null) {
            return null;
        }
        LearningGroupMemberEntity member = learningGroupMemberMapper.selectOne(new LambdaQueryWrapper<LearningGroupMemberEntity>()
                .in(LearningGroupMemberEntity::getGroupId, groupIds)
                .eq(LearningGroupMemberEntity::getUserId, userId)
                .eq(LearningGroupMemberEntity::getRole, LearningGroupMemberRole.STUDENT)
                .last("LIMIT 1"));
        return member == null ? null : learningGroupMapper.selectById(member.getGroupId());
    }

    private boolean isRegistrationWindowOpen(ContestRunEntity run, Instant now) {
        Instant start = run.getRegistrationStartAt();
        Instant end = run.getRegistrationEndAt();
        if (start == null && end == null) {
            return !now.isBefore(run.getStartAt()) && now.isBefore(run.getEndAt());
        }
        if (start != null && now.isBefore(start)) {
            return false;
        }
        if (end != null && !now.isBefore(end)) {
            return false;
        }
        return now.isBefore(run.getStartAt());
    }

    private void validateRunTimes(Instant startAt, Instant endAt, Instant freezeAt) {
        if (startAt == null || endAt == null || !startAt.isBefore(endAt)) {
            throw new DomainException(ErrorCode.BAD_REQUEST, "Contest run start time must be before end time");
        }
        if (freezeAt != null && (freezeAt.isBefore(startAt) || !freezeAt.isBefore(endAt))) {
            throw new DomainException(ErrorCode.BAD_REQUEST, "Contest run freeze time must be inside run time range");
        }
    }

    private void validateRegistrationWindow(Instant startAt, Instant endAt, Instant runStartAt) {
        if ((startAt == null) != (endAt == null)) {
            throw new DomainException(ErrorCode.BAD_REQUEST, "Registration start and end time must be filled together");
        }
        if (startAt != null && endAt != null && !startAt.isBefore(endAt)) {
            throw new DomainException(ErrorCode.BAD_REQUEST, "Registration start time must be before end time");
        }
        if (endAt != null && runStartAt != null && endAt.isAfter(runStartAt)) {
            throw new DomainException(ErrorCode.BAD_REQUEST, "Registration must close before the contest run starts");
        }
    }

    private Integer normalizeMaxParticipants(Integer value) {
        if (value == null) {
            return null;
        }
        if (value <= 0 || value > 100000) {
            throw new DomainException(ErrorCode.BAD_REQUEST, "Max participants must be positive");
        }
        return value;
    }

    private String requireTitle(String title) {
        if (!StringUtils.hasText(title)) {
            throw new DomainException(ErrorCode.BAD_REQUEST, "Contest run title is required");
        }
        return title.trim();
    }

    private String requireUniqueRunTitle(Long runId, String title) {
        String normalized = requireTitle(title);
        LambdaQueryWrapper<ContestRunEntity> query = new LambdaQueryWrapper<ContestRunEntity>()
                .eq(ContestRunEntity::getTitle, normalized)
                .isNull(ContestRunEntity::getDeletedAt);
        if (runId != null) {
            query.ne(ContestRunEntity::getId, runId);
        }
        Long duplicates = contestRunMapper.selectCount(query);
        if (duplicates != null && duplicates > 0) {
            throw new DomainException(ErrorCode.CONFLICT, "Contest run title already exists");
        }
        return normalized;
    }

    private ContestRunResponse toRunResponse(ContestRunEntity run) {
        return new ContestRunResponse(run.getId(), run.getContestId(), run.getRunKind(), run.getTitle(), effectiveRunStatus(run, Instant.now()),
                run.getStartAt(), run.getEndAt(), run.getFreezeAt(), run.getSourceRunId(), run.getRegistrationPolicy(),
                effectiveRegistrationAccess(run), Boolean.TRUE.equals(run.getApprovalRequired()), allowedGroupIds(run.getId()),
                run.getRegistrationStartAt(), run.getRegistrationEndAt(), run.getMaxParticipants(),
                run.getContestTitleSnapshot(), run.getContestDescriptionSnapshot(), run.getModeSnapshot(),
                run.getPenaltyMinutesSnapshot(), run.getCePenaltySnapshot(),
                run.getArchivedAt(), run.getArchiveReason(), run.getStatusBeforeArchive(), run.getDeletedAt(),
                run.getDeletedBy(), run.getPublicScoreboardUnfrozenAt(), run.getPublicScoreboardUnfrozenBy(),
                run.getAiPolicyModeSnapshot(), run.getAiPolicyNotesSnapshot(),
                run.getCreatedBy(), run.getCreatedAt(), run.getUpdatedAt());
    }

    private ContestRegistrationResponse toRegistrationResponse(ContestRegistrationEntity registration, UserAccountEntity user) {
        return new ContestRegistrationResponse(registration.getId(), registration.getContestId(), registration.getContestRunId(),
                registration.getUserId(), registration.getStatus(), registration.getRequestedAt(), registration.getReviewedBy(),
                registration.getApprovedAt(), registration.getRejectedAt(), registration.getCancelledAt(),
                registration.getRejectReason(), user == null ? null : user.getAccount(),
                user == null ? null : snapshotDisplayName(user), user == null ? null : user.getEmail(),
                registration.getCreatedAt(), registration.getUpdatedAt());
    }

    private ContestRegistrationResponse toPublicRegistrationResponse(ContestRegistrationEntity registration, UserAccountEntity user) {
        return new ContestRegistrationResponse(registration.getId(), registration.getContestId(), registration.getContestRunId(),
                registration.getUserId(), registration.getStatus(), registration.getRequestedAt(), registration.getReviewedBy(),
                registration.getApprovedAt(), registration.getRejectedAt(), registration.getCancelledAt(),
                registration.getRejectReason(), user == null ? null : user.getAccount(),
                user == null ? null : snapshotDisplayName(user), null,
                registration.getCreatedAt(), registration.getUpdatedAt());
    }

    private ContestRunProblemSnapshotResponse toProblemSnapshotResponse(ContestRunProblemSnapshotEntity snapshot,
                                                                        ProblemVisibility visibility) {
        return new ContestRunProblemSnapshotResponse(snapshot.getId(), snapshot.getContestId(), snapshot.getContestRunId(),
                snapshot.getContestProblemId(), snapshot.getProblemId(), snapshot.getLabel(), snapshot.getDisplayTitle(),
                snapshot.getStatement(), snapshot.getNotes(), tagsFromJson(snapshot.getTags()), snapshot.getDifficulty(),
                snapshot.getTimeLimitMillis(), snapshot.getMemoryLimitKb(), snapshot.getScore(), snapshot.getScoringMode(),
                snapshot.getSortOrder(), visibility, snapshot.getCreatedAt());
    }

    private List<String> tagsFromJson(String tags) {
        if (!StringUtils.hasText(tags)) {
            return List.of();
        }
        try {
            return objectMapper.readValue(tags, STRING_LIST).stream()
                    .filter(StringUtils::hasText)
                    .map(String::trim)
                    .distinct()
                    .toList();
        } catch (JsonProcessingException ignored) {
            return Arrays.stream(tags.split(","))
                    .map(String::trim)
                    .filter(StringUtils::hasText)
                    .distinct()
                    .toList();
        }
    }

    private ContestParticipantResponse toParticipantResponse(ContestParticipantEntity participant) {
        return new ContestParticipantResponse(participant.getId(), participant.getContestId(), participant.getContestRunId(),
                participant.getUserId(),
                participant.getParticipantType(), participant.getStatus(), participant.getAccountSnapshot(),
                participant.getDisplayNameSnapshot(), participant.getEmailSnapshot(), participant.getScopeGroupId(),
                participant.getGroupNameSnapshot(), participant.getRegisteredAt(), participant.getCreatedAt(),
                participant.getUpdatedAt());
    }

    private ContestParticipantResponse toPublicParticipantResponse(ContestParticipantEntity participant) {
        return new ContestParticipantResponse(participant.getId(), participant.getContestId(), participant.getContestRunId(),
                participant.getUserId(),
                participant.getParticipantType(), participant.getStatus(), participant.getAccountSnapshot(),
                participant.getDisplayNameSnapshot(), null, participant.getScopeGroupId(),
                participant.getGroupNameSnapshot(), participant.getRegisteredAt(), participant.getCreatedAt(),
                participant.getUpdatedAt());
    }

    private com.aioj.next.contract.contest.ContestResponse toContestResponse(ContestEntity contest) {
        long problemCount = contestProblemMapper.selectCount(new LambdaQueryWrapper<ContestProblemEntity>()
                .eq(ContestProblemEntity::getContestId, contest.getId()));
        return new com.aioj.next.contract.contest.ContestResponse(contest.getId(), contest.getOwnerUserId(), contest.getScopeGroupId(),
                contest.getTitle(), contest.getDescription(), contest.getMode(), contest.getStatus(),
                contest.getVisibility(), contest.getStartAt(), contest.getEndAt(), contest.getFreezeAt(),
                contest.getPenaltyMinutes() == null ? 20 : contest.getPenaltyMinutes(),
                Boolean.TRUE.equals(contest.getCePenalty()), problemCount,
                contest.getCreatedAt(), contest.getUpdatedAt(), contest.getPublishedAt(), contest.getArchivedAt(),
                contest.getDeletedAt(), contest.getDeletedBy(),
                contest.getAiPolicyMode() == null ? ContestAiPolicyMode.DEFAULT : contest.getAiPolicyMode(),
                contest.getAiPolicyNotes());
    }

    private void applyRunStatusFilter(LambdaQueryWrapper<ContestRunEntity> query, ContestRunStatus status, Instant now) {
        if (status == null) {
            return;
        }
        switch (status) {
            case DRAFT -> query.eq(ContestRunEntity::getStatus, ContestRunStatus.DRAFT)
                    .and(wrapper -> wrapper.isNull(ContestRunEntity::getEndAt)
                            .or()
                            .gt(ContestRunEntity::getEndAt, now));
            case EXPIRED -> query.eq(ContestRunEntity::getStatus, ContestRunStatus.DRAFT)
                    .isNotNull(ContestRunEntity::getEndAt)
                    .le(ContestRunEntity::getEndAt, now);
            case ARCHIVED -> query.eq(ContestRunEntity::getStatus, ContestRunStatus.ARCHIVED);
            case SCHEDULED -> query.ne(ContestRunEntity::getStatus, ContestRunStatus.DRAFT)
                    .ne(ContestRunEntity::getStatus, ContestRunStatus.EXPIRED)
                    .ne(ContestRunEntity::getStatus, ContestRunStatus.ARCHIVED)
                    .gt(ContestRunEntity::getStartAt, now);
            case RUNNING -> query.ne(ContestRunEntity::getStatus, ContestRunStatus.DRAFT)
                    .ne(ContestRunEntity::getStatus, ContestRunStatus.EXPIRED)
                    .ne(ContestRunEntity::getStatus, ContestRunStatus.ARCHIVED)
                    .le(ContestRunEntity::getStartAt, now)
                    .gt(ContestRunEntity::getEndAt, now);
            case ENDED -> query.ne(ContestRunEntity::getStatus, ContestRunStatus.DRAFT)
                    .ne(ContestRunEntity::getStatus, ContestRunStatus.EXPIRED)
                    .ne(ContestRunEntity::getStatus, ContestRunStatus.ARCHIVED)
                    .le(ContestRunEntity::getEndAt, now);
        }
    }

    private void applyRunPurposeFilter(LambdaQueryWrapper<ContestRunEntity> query, ContestRunListPurpose purpose, Instant now) {
        if (purpose == null) {
            return;
        }
        if (purpose == ContestRunListPurpose.AI_OPERATIONS) {
            query.ne(ContestRunEntity::getStatus, ContestRunStatus.DRAFT)
                    .ne(ContestRunEntity::getStatus, ContestRunStatus.EXPIRED)
                    .ne(ContestRunEntity::getStatus, ContestRunStatus.ARCHIVED)
                    .le(ContestRunEntity::getEndAt, now);
        }
    }

    private ContestRunStatus effectiveRunStatus(ContestRunEntity run, Instant now) {
        return ContestRunStatePolicy.effectiveStatus(run, now);
    }

    private String snapshotAccount(UserAccountEntity user) {
        return StringUtils.hasText(user.getAccount()) ? user.getAccount().trim() : "#" + user.getId();
    }

    private String snapshotDisplayName(UserAccountEntity user) {
        return StringUtils.hasText(user.getDisplayName()) ? user.getDisplayName().trim() : snapshotAccount(user);
    }

    private boolean contains(String value, String keyword) {
        return StringUtils.hasText(value) && value.toLowerCase().contains(keyword.trim().toLowerCase());
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
            return Map.of();
        }
        return loader.apply(ids.stream().filter(Objects::nonNull).distinct().toList()).stream()
                .collect(Collectors.toMap(idGetter, Function.identity()));
    }

    private record RegistrationSettings(
            ContestRegistrationAccess access,
            boolean approvalRequired,
            List<Long> allowedGroupIds
    ) {
    }
}
