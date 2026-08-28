package com.aioj.next.problem.domain;

import com.aioj.next.common.api.PageResponse;
import com.aioj.next.common.error.DomainException;
import com.aioj.next.common.error.ErrorCode;
import com.aioj.next.common.security.Role;
import com.aioj.next.common.security.SecuritySupport;
import com.aioj.next.contract.contest.ContestParticipantAddRequest;
import com.aioj.next.contract.ai.ContestParticipantProfile;
import com.aioj.next.contract.contest.ContestParticipantResponse;
import com.aioj.next.contract.contest.ContestParticipantStatus;
import com.aioj.next.contract.contest.ContestParticipantType;
import com.aioj.next.contract.contest.ContestCreateRequest;
import com.aioj.next.contract.contest.ContestAiPolicyMode;
import com.aioj.next.contract.contest.ContestMode;
import com.aioj.next.contract.contest.ContestProblemBatchUpdateRequest;
import com.aioj.next.contract.contest.ContestProblemRequest;
import com.aioj.next.contract.contest.ContestProblemResponse;
import com.aioj.next.contract.contest.ContestProblemScoringMode;
import com.aioj.next.contract.contest.ContestResponse;
import com.aioj.next.contract.contest.ContestRunStatus;
import com.aioj.next.contract.contest.ContestStatus;
import com.aioj.next.contract.contest.ContestUpdateRequest;
import com.aioj.next.contract.contest.ContestVisibility;
import com.aioj.next.contract.learning.LearningGroupMemberRole;
import com.aioj.next.contract.learning.LearningGroupStatus;
import com.aioj.next.contract.learning.LearningGroupType;
import com.aioj.next.problem.persistence.entity.ContestParticipantEntity;
import com.aioj.next.problem.persistence.entity.ContestParticipantSnapshotEntity;
import com.aioj.next.problem.persistence.entity.ContestEntity;
import com.aioj.next.problem.persistence.entity.ContestProblemEntity;
import com.aioj.next.problem.persistence.entity.ContestProblemScoringRuleEntity;
import com.aioj.next.problem.persistence.entity.ContestRunEntity;
import com.aioj.next.problem.persistence.entity.LearningGroupEntity;
import com.aioj.next.problem.persistence.entity.LearningGroupMemberEntity;
import com.aioj.next.problem.persistence.entity.UserAccountEntity;
import com.aioj.next.problem.persistence.mapper.ContestParticipantMapper;
import com.aioj.next.problem.persistence.mapper.ContestParticipantSnapshotMapper;
import com.aioj.next.problem.persistence.mapper.ContestMapper;
import com.aioj.next.problem.persistence.mapper.ContestProblemMapper;
import com.aioj.next.problem.persistence.mapper.ContestProblemScoringRuleMapper;
import com.aioj.next.problem.persistence.mapper.ContestRunMapper;
import com.aioj.next.problem.persistence.mapper.LearningGroupMapper;
import com.aioj.next.problem.persistence.mapper.LearningGroupMemberMapper;
import com.aioj.next.problem.persistence.mapper.UserAccountMapper;
import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.util.StringUtils;

import java.math.BigDecimal;
import java.time.Duration;
import java.time.Instant;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.Function;
import java.util.stream.Collectors;

@Service
public class ContestService {
    private final ContestMapper contestMapper;
    private final ContestProblemMapper contestProblemMapper;
    private final ContestProblemScoringRuleMapper scoringRuleMapper;
    private final LearningGroupMapper learningGroupMapper;
    private final LearningGroupMemberMapper learningGroupMemberMapper;
    private final ContestParticipantMapper contestParticipantMapper;
    private final ContestParticipantSnapshotMapper contestParticipantSnapshotMapper;
    private final ContestRunMapper contestRunMapper;
    private final UserAccountMapper userAccountMapper;
    private final ProblemCatalog problemCatalog;

    public ContestService(ContestMapper contestMapper, ContestProblemMapper contestProblemMapper,
                          ContestProblemScoringRuleMapper scoringRuleMapper,
                          LearningGroupMapper learningGroupMapper, LearningGroupMemberMapper learningGroupMemberMapper,
                          ContestParticipantMapper contestParticipantMapper,
                          ContestParticipantSnapshotMapper contestParticipantSnapshotMapper,
                          ContestRunMapper contestRunMapper,
                          UserAccountMapper userAccountMapper,
                          ProblemCatalog problemCatalog) {
        this.contestMapper = contestMapper;
        this.contestProblemMapper = contestProblemMapper;
        this.scoringRuleMapper = scoringRuleMapper;
        this.learningGroupMapper = learningGroupMapper;
        this.learningGroupMemberMapper = learningGroupMemberMapper;
        this.contestParticipantMapper = contestParticipantMapper;
        this.contestParticipantSnapshotMapper = contestParticipantSnapshotMapper;
        this.contestRunMapper = contestRunMapper;
        this.userAccountMapper = userAccountMapper;
        this.problemCatalog = problemCatalog;
    }

    public PageResponse<ContestResponse> list(long page, long pageSize, ContestStatus status, Boolean mine,
                                              Long scopeGroupId, String keyword, Boolean acm) {
        Long userId = SecuritySupport.currentUserId();
        boolean admin = SecuritySupport.hasRole(Role.ADMIN);
        boolean teacher = SecuritySupport.hasRole(Role.TEACHER);
        String normalizedKeyword = StringUtils.hasText(keyword) ? keyword.trim() : null;
        LambdaQueryWrapper<ContestEntity> query = new LambdaQueryWrapper<ContestEntity>()
                .isNull(ContestEntity::getDeletedAt)
                .eq(status != null, ContestEntity::getStatus, status)
                .eq(scopeGroupId != null, ContestEntity::getScopeGroupId, scopeGroupId)
                .like(StringUtils.hasText(normalizedKeyword), ContestEntity::getTitle, normalizedKeyword)
                .eq(acm != null, ContestEntity::getMode, Boolean.TRUE.equals(acm) ? ContestMode.ACM : ContestMode.IOI)
                .orderByDesc(ContestEntity::getUpdatedAt)
                .orderByDesc(ContestEntity::getId);

        if (admin) {
            if (Boolean.TRUE.equals(mine)) {
                query.eq(ContestEntity::getOwnerUserId, userId);
            }
        } else if (teacher) {
            query.eq(ContestEntity::getOwnerUserId, userId);
        } else {
            List<Long> groupIds = visibleGroupIds(userId);
            if (groupIds.isEmpty()) {
                return new PageResponse<>(List.of(), 0, normalizePage(page), normalizePageSize(pageSize));
            }
            query.eq(ContestEntity::getStatus, ContestStatus.PUBLISHED)
                    .eq(ContestEntity::getVisibility, ContestVisibility.GROUP)
                    .in(ContestEntity::getScopeGroupId, groupIds);
        }

        Page<ContestEntity> result = contestMapper.selectPage(new Page<>(normalizePage(page), normalizePageSize(pageSize)), query);
        return new PageResponse<>(result.getRecords().stream().map(this::toResponse).toList(), result.getTotal(),
                result.getCurrent(), result.getSize());
    }

    public ContestResponse get(Long id) {
        ContestEntity contest = requireContest(id);
        assertCanView(contest);
        return toResponse(contest);
    }

    @Transactional
    public ContestResponse create(ContestCreateRequest request) {
        assertStaff();
        Instant now = Instant.now();
        String title = requireUniqueTitle(null, request.title());
        ContestEntity contest = new ContestEntity();
        contest.setOwnerUserId(SecuritySupport.currentUserId());
        contest.setScopeGroupId(null);
        contest.setTitle(title);
        contest.setDescription(normalizeDescription(request.description()));
        contest.setMode(request.mode());
        contest.setStatus(ContestStatus.DRAFT);
        contest.setVisibility(null);
        contest.setStartAt(null);
        contest.setEndAt(null);
        contest.setFreezeAt(null);
        contest.setPenaltyMinutes(normalizePenaltyMinutes(request.penaltyMinutes()));
        contest.setCePenalty(Boolean.TRUE.equals(request.cePenalty()));
        contest.setAiPolicyMode(request.aiPolicyMode() == null ? ContestAiPolicyMode.DEFAULT : request.aiPolicyMode());
        contest.setAiPolicyNotes(normalizeDescription(request.aiPolicyNotes()));
        contest.setCreatedAt(now);
        contest.setUpdatedAt(now);
        try {
            contestMapper.insert(contest);
        } catch (DuplicateKeyException duplicate) {
            throw new DomainException(ErrorCode.CONFLICT, "Contest title already exists");
        }
        return toResponse(contest);
    }

    @Transactional
    public ContestResponse update(Long id, ContestUpdateRequest request) {
        ContestEntity contest = requireContest(id);
        assertCanManage(contest);
        if (contest.getStatus() == ContestStatus.ARCHIVED) {
            throw new DomainException(ErrorCode.BAD_REQUEST, "Archived contest cannot be updated");
        }
        if (contest.getStatus() == ContestStatus.PUBLISHED) {
            assertPublishedUpdateOnlyDescription(contest, request);
            if (request.description() != null) {
                contest.setDescription(normalizeDescription(request.description()));
            }
            contest.setUpdatedAt(Instant.now());
            contestMapper.updateById(contest);
            return toResponse(contest);
        }
        if (StringUtils.hasText(request.title())) {
            contest.setTitle(requireUniqueTitle(id, request.title()));
        }
        if (request.description() != null) {
            contest.setDescription(normalizeDescription(request.description()));
        }
        if (request.mode() != null) {
            contest.setMode(request.mode());
        }
        if (request.penaltyMinutes() != null) {
            contest.setPenaltyMinutes(normalizePenaltyMinutes(request.penaltyMinutes()));
        }
        if (request.cePenalty() != null) {
            contest.setCePenalty(request.cePenalty());
        }
        if (request.aiPolicyMode() != null) {
            contest.setAiPolicyMode(request.aiPolicyMode());
        }
        if (request.aiPolicyNotes() != null) {
            contest.setAiPolicyNotes(normalizeDescription(request.aiPolicyNotes()));
        }
        contest.setUpdatedAt(Instant.now());
        try {
            contestMapper.updateById(contest);
        } catch (DuplicateKeyException duplicate) {
            throw new DomainException(ErrorCode.CONFLICT, "Contest title already exists");
        }
        return toResponse(contest);
    }

    @Transactional
    public ContestResponse publish(Long id) {
        ContestEntity contest = requireContest(id);
        assertCanManage(contest);
        if (contest.getStatus() != ContestStatus.DRAFT) {
            throw new DomainException(ErrorCode.BAD_REQUEST, "Only draft contest blueprints can be confirmed");
        }
        List<ContestProblemEntity> problems = contestProblems(id);
        if (problems.isEmpty()) {
            throw new DomainException(ErrorCode.BAD_REQUEST, "Contest blueprint must have at least one problem");
        }
        assertProblemSetValid(problems);
        Instant now = Instant.now();
        contest.setStatus(ContestStatus.PUBLISHED);
        contest.setPublishedAt(now);
        contest.setUpdatedAt(now);
        contestMapper.updateById(contest);
        return toResponse(contest);
    }

    @Transactional
    public ContestResponse confirm(Long id) {
        return publish(id);
    }

    @Transactional
    public ContestResponse archive(Long id) {
        ContestEntity contest = requireContest(id);
        assertCanManage(contest);
        if (contest.getStatus() == ContestStatus.ARCHIVED) {
            return toResponse(contest);
        }
        Instant now = Instant.now();
        contest.setStatus(ContestStatus.ARCHIVED);
        contest.setArchivedAt(now);
        contest.setUpdatedAt(now);
        contestMapper.updateById(contest);
        return toResponse(contest);
    }

    @Transactional
    public ContestResponse restore(Long id) {
        ContestEntity contest = requireContest(id);
        assertCanManage(contest);
        if (contest.getStatus() != ContestStatus.ARCHIVED) {
            throw new DomainException(ErrorCode.BAD_REQUEST, "Only archived contests can be restored");
        }
        requireUniqueTitle(id, contest.getTitle());
        Instant now = Instant.now();
        contest.setStatus(contest.getPublishedAt() == null ? ContestStatus.DRAFT : ContestStatus.PUBLISHED);
        contest.setArchivedAt(null);
        contest.setUpdatedAt(now);
        try {
            contestMapper.updateById(contest);
        } catch (DuplicateKeyException duplicate) {
            throw new DomainException(ErrorCode.CONFLICT, "Contest title already exists");
        }
        return toResponse(contest);
    }

    @Transactional
    public ContestResponse delete(Long id) {
        ContestEntity contest = requireContest(id);
        assertCanManage(contest);
        if (contest.getStatus() != ContestStatus.ARCHIVED) {
            throw new DomainException(ErrorCode.BAD_REQUEST, "Only archived contests can be deleted");
        }
        long activeRuns = contestRunMapper.selectCount(new LambdaQueryWrapper<ContestRunEntity>()
                .eq(ContestRunEntity::getContestId, id)
                .isNull(ContestRunEntity::getDeletedAt)
                .ne(ContestRunEntity::getStatus, ContestRunStatus.ARCHIVED));
        if (activeRuns > 0) {
            throw new DomainException(ErrorCode.BAD_REQUEST, "Contest has active runs and cannot be deleted");
        }
        Instant now = Instant.now();
        contest.setDeletedAt(now);
        contest.setDeletedBy(SecuritySupport.currentUserId());
        contest.setUpdatedAt(now);
        contestMapper.updateById(contest);
        return toResponse(contest);
    }

    public List<ContestProblemResponse> listProblems(Long contestId) {
        ContestEntity contest = requireContest(contestId);
        assertCanView(contest);
        return contestProblems(contestId).stream().map(this::toProblemResponse).toList();
    }

    @Transactional
    public List<ContestProblemResponse> replaceProblems(Long contestId, ContestProblemBatchUpdateRequest request) {
        ContestEntity contest = requireContest(contestId);
        assertCanManage(contest);
        if (contest.getStatus() != ContestStatus.DRAFT) {
            throw new DomainException(ErrorCode.BAD_REQUEST, "Confirmed contest blueprint problems cannot be changed");
        }
        assertProblemRequestsValid(request.problems());
        scoringRuleMapper.delete(new LambdaQueryWrapper<ContestProblemScoringRuleEntity>()
                .eq(ContestProblemScoringRuleEntity::getContestId, contestId));
        contestProblemMapper.delete(new LambdaQueryWrapper<ContestProblemEntity>()
                .eq(ContestProblemEntity::getContestId, contestId));
        Instant now = Instant.now();
        for (ContestProblemRequest problemRequest : request.problems()) {
            ContestProblemEntity entity = new ContestProblemEntity();
            var problem = problemCatalog.findActive(problemRequest.problemId())
                    .orElseThrow(() -> new DomainException(ErrorCode.NOT_FOUND, "Problem not found"));
            entity.setContestId(contestId);
            entity.setProblemId(problem.getId());
            entity.setLabel(normalizeLabel(problemRequest.label()));
            entity.setDisplayTitle(StringUtils.hasText(problemRequest.displayTitle())
                    ? problemRequest.displayTitle().trim()
                    : problem.getTitle());
            entity.setScore(problemRequest.score());
            entity.setSortOrder(problemRequest.sortOrder());
            entity.setCreatedAt(now);
            entity.setUpdatedAt(now);
            contestProblemMapper.insert(entity);
            ContestProblemScoringRuleEntity scoringRule = new ContestProblemScoringRuleEntity();
            scoringRule.setContestId(contestId);
            scoringRule.setContestProblemId(entity.getId());
            scoringRule.setScoringMode(scoringModeOrDefault(problemRequest.scoringMode()));
            scoringRule.setMaxScore(BigDecimal.valueOf(problemRequest.score()));
            scoringRule.setCreatedAt(now);
            scoringRule.setUpdatedAt(now);
            scoringRuleMapper.insert(scoringRule);
        }
        contest.setUpdatedAt(now);
        contestMapper.updateById(contest);
        return listProblems(contestId);
    }

    public List<ContestParticipantResponse> listParticipants(Long contestId) {
        ContestEntity contest = requireContest(contestId);
        assertCanManage(contest);
        return contestParticipantMapper.selectList(new LambdaQueryWrapper<ContestParticipantEntity>()
                        .eq(ContestParticipantEntity::getContestId, contestId)
                        .orderByAsc(ContestParticipantEntity::getRegisteredAt)
                        .orderByAsc(ContestParticipantEntity::getId))
                .stream()
                .map(this::toParticipantResponse)
                .toList();
    }

    public List<ContestParticipantProfile> participantProfiles(Long contestId) {
        requireContest(contestId);
        return contestParticipantMapper.selectList(new LambdaQueryWrapper<ContestParticipantEntity>()
                        .eq(ContestParticipantEntity::getContestId, contestId)
                        .eq(ContestParticipantEntity::getStatus, ContestParticipantStatus.ACTIVE)
                        .orderByAsc(ContestParticipantEntity::getRegisteredAt)
                        .orderByAsc(ContestParticipantEntity::getId))
                .stream()
                .map(participant -> new ContestParticipantProfile(
                        participant.getUserId(),
                        participant.getContestRunId(),
                        participant.getAccountSnapshot(),
                        participant.getDisplayNameSnapshot()))
                .toList();
    }

    @Transactional
    public List<ContestParticipantResponse> importScopeGroupParticipants(Long contestId) {
        ContestEntity contest = requireContest(contestId);
        assertCanManage(contest);
        if (contest.getStatus() == ContestStatus.ARCHIVED) {
            throw new DomainException(ErrorCode.BAD_REQUEST, "Archived contest participants cannot be changed");
        }
        LearningGroupEntity group = validateScopeGroup(contest.getScopeGroupId());
        List<LearningGroupMemberEntity> members = learningGroupMemberMapper.selectList(new LambdaQueryWrapper<LearningGroupMemberEntity>()
                .eq(LearningGroupMemberEntity::getGroupId, contest.getScopeGroupId())
                .eq(LearningGroupMemberEntity::getRole, LearningGroupMemberRole.STUDENT)
                .orderByAsc(LearningGroupMemberEntity::getCreatedAt)
                .orderByAsc(LearningGroupMemberEntity::getId));
        if (members.isEmpty()) {
            return listParticipants(contestId);
        }
        Map<Long, UserAccountEntity> users = userAccountMapper.selectBatchIds(members.stream()
                        .map(LearningGroupMemberEntity::getUserId)
                        .distinct()
                        .toList())
                .stream()
                .collect(Collectors.toMap(UserAccountEntity::getId, Function.identity()));
        Instant now = Instant.now();
        for (LearningGroupMemberEntity member : members) {
            UserAccountEntity user = users.get(member.getUserId());
            if (user == null || Boolean.FALSE.equals(user.getEnabled())) {
                continue;
            }
            upsertParticipant(contest, group, user, now, "GROUP_IMPORT");
        }
        return listParticipants(contestId);
    }

    @Transactional
    public ContestParticipantResponse addParticipant(Long contestId, ContestParticipantAddRequest request) {
        ContestEntity contest = requireContest(contestId);
        assertCanManage(contest);
        if (contest.getStatus() == ContestStatus.ARCHIVED) {
            throw new DomainException(ErrorCode.BAD_REQUEST, "Archived contest participants cannot be changed");
        }
        LearningGroupEntity group = validateScopeGroup(contest.getScopeGroupId());
        UserAccountEntity user = resolveParticipantUser(request);
        if (Boolean.FALSE.equals(user.getEnabled())) {
            throw new DomainException(ErrorCode.BAD_REQUEST, "User account is disabled");
        }
        LearningGroupMemberEntity member = learningGroupMemberMapper.selectOne(new LambdaQueryWrapper<LearningGroupMemberEntity>()
                .eq(LearningGroupMemberEntity::getGroupId, contest.getScopeGroupId())
                .eq(LearningGroupMemberEntity::getUserId, user.getId()));
        if (member == null) {
            throw new DomainException(ErrorCode.BAD_REQUEST, "User is not in the contest user group");
        }
        if (member.getRole() != LearningGroupMemberRole.STUDENT) {
            throw new DomainException(ErrorCode.BAD_REQUEST, "Only student members can join as contest participants");
        }
        return toParticipantResponse(upsertParticipant(contest, group, user, Instant.now(), "MANUAL_ADD"));
    }

    public ContestSubmissionContext resolveSubmissionContext(Long contestId, Long contestProblemId,
                                                             Long problemId, Long userId, Instant submittedAt) {
        return resolveSubmissionContext(contestId, null, contestProblemId, problemId, userId, submittedAt);
    }

    public ContestSubmissionContext resolveSubmissionContext(Long contestId, Long contestRunId, Long contestProblemId,
                                                             Long problemId, Long userId, Instant submittedAt) {
        ContestEntity contest = requireContest(contestId);
        if (contest.getStatus() != ContestStatus.PUBLISHED) {
            throw new DomainException(ErrorCode.BAD_REQUEST, "Contest is not open for submissions");
        }
        ContestRunEntity run = contestRunId == null ? null : requireRun(contestId, contestRunId);
        if (run != null && (run.getStatus() == ContestRunStatus.DRAFT || run.getStatus() == ContestRunStatus.ARCHIVED)) {
            throw new DomainException(ErrorCode.BAD_REQUEST, "Contest run is not open for submissions");
        }
        Instant startAt = run == null ? contest.getStartAt() : run.getStartAt();
        Instant endAt = run == null ? contest.getEndAt() : run.getEndAt();
        if (startAt == null || endAt == null) {
            throw new DomainException(ErrorCode.BAD_REQUEST, "Contest run is required for submissions");
        }
        if (submittedAt.isBefore(startAt)) {
            throw new DomainException(ErrorCode.BAD_REQUEST, "Contest has not started");
        }
        if (!submittedAt.isBefore(endAt)) {
            throw new DomainException(ErrorCode.BAD_REQUEST, "Contest has ended");
        }
        if (contestProblemId == null) {
            throw new DomainException(ErrorCode.BAD_REQUEST, "Contest problem is required");
        }
        ContestProblemEntity contestProblem = contestProblemMapper.selectOne(new LambdaQueryWrapper<ContestProblemEntity>()
                .eq(ContestProblemEntity::getId, contestProblemId)
                .eq(ContestProblemEntity::getContestId, contestId));
        if (contestProblem == null) {
            throw new DomainException(ErrorCode.NOT_FOUND, "Contest problem not found");
        }
        if (!contestProblem.getProblemId().equals(problemId)) {
            throw new DomainException(ErrorCode.BAD_REQUEST, "Contest problem does not match submitted problem");
        }
        ContestParticipantEntity participant = contestParticipantMapper.selectOne(new LambdaQueryWrapper<ContestParticipantEntity>()
                .eq(ContestParticipantEntity::getContestId, contestId)
                .eq(contestRunId != null, ContestParticipantEntity::getContestRunId, contestRunId)
                .eq(ContestParticipantEntity::getUserId, userId)
                .eq(ContestParticipantEntity::getStatus, ContestParticipantStatus.ACTIVE));
        if (participant == null) {
            throw new DomainException(ErrorCode.FORBIDDEN, "User is not an active participant in this contest");
        }
        long elapsed = submittedAt.isBefore(startAt) ? 0L : Duration.between(startAt, submittedAt).toMillis();
        return new ContestSubmissionContext(contest, run, contestProblem, participant, Math.max(0L, elapsed));
    }

    private ContestEntity requireContest(Long id) {
        ContestEntity contest = contestMapper.selectById(id);
        if (contest == null || contest.getDeletedAt() != null) {
            throw new DomainException(ErrorCode.NOT_FOUND, "Contest not found");
        }
        return contest;
    }

    private ContestRunEntity requireRun(Long contestId, Long runId) {
        ContestRunEntity run = contestRunMapper.selectById(runId);
        if (run == null || run.getDeletedAt() != null || !contestId.equals(run.getContestId())) {
            throw new DomainException(ErrorCode.NOT_FOUND, "Contest run not found");
        }
        return run;
    }

    private void assertCanView(ContestEntity contest) {
        if (canManage(contest)) {
            return;
        }
        if (contest.getStatus() == ContestStatus.PUBLISHED
                && contest.getVisibility() == ContestVisibility.GROUP
                && isGroupMember(contest.getScopeGroupId(), SecuritySupport.currentUserId())) {
            return;
        }
        throw new DomainException(ErrorCode.FORBIDDEN, "Cannot access contest");
    }

    private void assertCanManage(ContestEntity contest) {
        if (!canManage(contest)) {
            throw new DomainException(ErrorCode.FORBIDDEN, "Cannot manage contest");
        }
    }

    private boolean canManage(ContestEntity contest) {
        return SecuritySupport.hasRole(Role.ADMIN)
                || (SecuritySupport.hasRole(Role.TEACHER) && contest.getOwnerUserId().equals(SecuritySupport.currentUserId()));
    }

    private void assertStaff() {
        if (!SecuritySupport.hasAnyRole(Role.TEACHER, Role.ADMIN)) {
            throw new DomainException(ErrorCode.FORBIDDEN, "Cannot manage contest");
        }
    }

    private LearningGroupEntity validateScopeGroup(Long groupId) {
        LearningGroupEntity group = learningGroupMapper.selectOne(new LambdaQueryWrapper<LearningGroupEntity>()
                .eq(LearningGroupEntity::getId, groupId)
                .eq(LearningGroupEntity::getType, LearningGroupType.CLASS)
                .eq(LearningGroupEntity::getStatus, LearningGroupStatus.ACTIVE));
        if (group == null) {
            throw new DomainException(ErrorCode.NOT_FOUND, "User group not found");
        }
        return group;
    }

    private UserAccountEntity resolveParticipantUser(ContestParticipantAddRequest request) {
        if (request == null) {
            throw new DomainException(ErrorCode.BAD_REQUEST, "Participant user is required");
        }
        if (request.userId() != null) {
            UserAccountEntity user = userAccountMapper.selectById(request.userId());
            if (user != null) {
                return user;
            }
            UserAccountEntity accountMatched = userAccountMapper.selectOne(new LambdaQueryWrapper<UserAccountEntity>()
                    .eq(UserAccountEntity::getAccount, String.valueOf(request.userId())));
            if (accountMatched != null) {
                return accountMatched;
            }
        }
        if (StringUtils.hasText(request.account())) {
            UserAccountEntity user = userAccountMapper.selectOne(new LambdaQueryWrapper<UserAccountEntity>()
                    .eq(UserAccountEntity::getAccount, request.account().trim()));
            if (user != null) {
                return user;
            }
        }
        throw new DomainException(ErrorCode.NOT_FOUND, "User not found");
    }

    private ContestParticipantEntity upsertParticipant(ContestEntity contest, LearningGroupEntity group,
                                                       UserAccountEntity user, Instant now, String reason) {
        ContestParticipantEntity participant = contestParticipantMapper.selectOne(new LambdaQueryWrapper<ContestParticipantEntity>()
                .eq(ContestParticipantEntity::getContestId, contest.getId())
                .eq(ContestParticipantEntity::getUserId, user.getId()));
        if (participant == null) {
            participant = new ContestParticipantEntity();
            participant.setContestId(contest.getId());
            participant.setUserId(user.getId());
            participant.setParticipantType(ContestParticipantType.INDIVIDUAL);
            participant.setRegisteredAt(now);
            participant.setCreatedAt(now);
        }
        participant.setStatus(ContestParticipantStatus.ACTIVE);
        participant.setAccountSnapshot(snapshotAccount(user));
        participant.setDisplayNameSnapshot(snapshotDisplayName(user));
        participant.setEmailSnapshot(user.getEmail());
        participant.setScopeGroupId(group.getId());
        participant.setGroupNameSnapshot(group.getName());
        participant.setUpdatedAt(now);
        if (participant.getId() == null) {
            contestParticipantMapper.insert(participant);
        } else {
            contestParticipantMapper.updateById(participant);
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
        contestParticipantSnapshotMapper.insert(snapshot);
    }

    private String snapshotAccount(UserAccountEntity user) {
        return StringUtils.hasText(user.getAccount()) ? user.getAccount().trim() : "#" + user.getId();
    }

    private String snapshotDisplayName(UserAccountEntity user) {
        if (StringUtils.hasText(user.getDisplayName())) {
            return user.getDisplayName().trim();
        }
        return snapshotAccount(user);
    }

    private boolean isGroupMember(Long groupId, Long userId) {
        return learningGroupMemberMapper.selectCount(new LambdaQueryWrapper<LearningGroupMemberEntity>()
                .eq(LearningGroupMemberEntity::getGroupId, groupId)
                .eq(LearningGroupMemberEntity::getUserId, userId)) > 0;
    }

    private List<Long> visibleGroupIds(Long userId) {
        return learningGroupMemberMapper.selectList(new LambdaQueryWrapper<LearningGroupMemberEntity>()
                        .eq(LearningGroupMemberEntity::getUserId, userId))
                .stream()
                .map(LearningGroupMemberEntity::getGroupId)
                .distinct()
                .toList();
    }

    private void validateTimeRange(Instant startAt, Instant endAt, Instant freezeAt) {
        if (startAt == null || endAt == null || !startAt.isBefore(endAt)) {
            throw new DomainException(ErrorCode.BAD_REQUEST, "Contest start time must be before end time");
        }
        if (freezeAt != null && (freezeAt.isBefore(startAt) || !freezeAt.isBefore(endAt))) {
            throw new DomainException(ErrorCode.BAD_REQUEST, "Contest freeze time must be inside contest time range");
        }
    }

    private void assertProblemRequestsValid(List<ContestProblemRequest> problems) {
        Set<String> labels = new HashSet<>();
        Set<Long> problemIds = new HashSet<>();
        for (ContestProblemRequest problem : problems) {
            String label = normalizeLabel(problem.label());
            if (!labels.add(label)) {
                throw new DomainException(ErrorCode.BAD_REQUEST, "Contest problem labels must be unique");
            }
            if (!problemIds.add(problem.problemId())) {
                throw new DomainException(ErrorCode.BAD_REQUEST, "Contest problems must be unique");
            }
            if (problemCatalog.findActive(problem.problemId()).isEmpty()) {
                throw new DomainException(ErrorCode.NOT_FOUND, "Problem not found");
            }
        }
    }

    private void assertProblemSetValid(List<ContestProblemEntity> problems) {
        Set<String> labels = new HashSet<>();
        Set<Long> problemIds = new HashSet<>();
        for (ContestProblemEntity problem : problems) {
            if (!labels.add(normalizeLabel(problem.getLabel()))) {
                throw new DomainException(ErrorCode.BAD_REQUEST, "Contest problem labels must be unique");
            }
            if (!problemIds.add(problem.getProblemId())) {
                throw new DomainException(ErrorCode.BAD_REQUEST, "Contest problems must be unique");
            }
            if (!problemCatalog.existsActive(problem.getProblemId())) {
                throw new DomainException(ErrorCode.NOT_FOUND, "Problem not found");
            }
        }
    }

    private List<ContestProblemEntity> contestProblems(Long contestId) {
        return contestProblemMapper.selectList(new LambdaQueryWrapper<ContestProblemEntity>()
                .eq(ContestProblemEntity::getContestId, contestId)
                .orderByAsc(ContestProblemEntity::getSortOrder)
                .orderByAsc(ContestProblemEntity::getId));
    }

    private ContestResponse toResponse(ContestEntity contest) {
        long problemCount = contestProblemMapper.selectCount(new LambdaQueryWrapper<ContestProblemEntity>()
                .eq(ContestProblemEntity::getContestId, contest.getId()));
        return new ContestResponse(contest.getId(), contest.getOwnerUserId(), contest.getScopeGroupId(),
                contest.getTitle(), contest.getDescription(), contest.getMode(), contest.getStatus(),
                contest.getVisibility(), contest.getStartAt(), contest.getEndAt(), contest.getFreezeAt(),
                effectivePenaltyMinutes(contest), Boolean.TRUE.equals(contest.getCePenalty()), problemCount,
                contest.getCreatedAt(), contest.getUpdatedAt(), contest.getPublishedAt(), contest.getArchivedAt(),
                contest.getDeletedAt(), contest.getDeletedBy(),
                effectiveAiPolicyMode(contest), contest.getAiPolicyNotes());
    }

    private ContestProblemResponse toProblemResponse(ContestProblemEntity problem) {
        return new ContestProblemResponse(problem.getId(), problem.getContestId(), problem.getProblemId(),
                problem.getLabel(), problem.getDisplayTitle(), problem.getScore(), problem.getSortOrder(),
                scoringMode(problem.getId()), problem.getCreatedAt(), problem.getUpdatedAt());
    }

    private ContestProblemScoringMode scoringMode(Long contestProblemId) {
        ContestProblemScoringRuleEntity rule = scoringRuleMapper.selectOne(new LambdaQueryWrapper<ContestProblemScoringRuleEntity>()
                .eq(ContestProblemScoringRuleEntity::getContestProblemId, contestProblemId)
                .last("LIMIT 1"));
        return rule == null ? ContestProblemScoringMode.CASE_SUM_BEST_SUBMISSION : scoringModeOrDefault(rule.getScoringMode());
    }

    private ContestProblemScoringMode scoringModeOrDefault(ContestProblemScoringMode scoringMode) {
        return scoringMode == null ? ContestProblemScoringMode.CASE_SUM_BEST_SUBMISSION : scoringMode;
    }

    private ContestParticipantResponse toParticipantResponse(ContestParticipantEntity participant) {
        return new ContestParticipantResponse(participant.getId(), participant.getContestId(), participant.getContestRunId(),
                participant.getUserId(),
                participant.getParticipantType(), participant.getStatus(), participant.getAccountSnapshot(),
                participant.getDisplayNameSnapshot(), participant.getEmailSnapshot(), participant.getScopeGroupId(),
                participant.getGroupNameSnapshot(), participant.getRegisteredAt(), participant.getCreatedAt(),
                participant.getUpdatedAt());
    }

    private String requireTitle(String title) {
        if (!StringUtils.hasText(title)) {
            throw new DomainException(ErrorCode.BAD_REQUEST, "Contest title is required");
        }
        return title.trim();
    }

    private String requireUniqueTitle(Long contestId, String title) {
        String normalized = requireTitle(title);
        LambdaQueryWrapper<ContestEntity> query = new LambdaQueryWrapper<ContestEntity>()
                .eq(ContestEntity::getTitle, normalized)
                .isNull(ContestEntity::getDeletedAt);
        if (contestId != null) {
            query.ne(ContestEntity::getId, contestId);
        }
        Long duplicates = contestMapper.selectCount(query);
        if (duplicates != null && duplicates > 0) {
            throw new DomainException(ErrorCode.CONFLICT, "Contest title already exists");
        }
        return normalized;
    }

    private String normalizeDescription(String description) {
        return StringUtils.hasText(description) ? description.trim() : null;
    }

    private void assertPublishedUpdateOnlyDescription(ContestEntity contest, ContestUpdateRequest request) {
        if (StringUtils.hasText(request.title()) && !request.title().trim().equals(contest.getTitle())) {
            throw new DomainException(ErrorCode.BAD_REQUEST, "Published contest only allows description updates");
        }
        if (request.mode() != null && request.mode() != contest.getMode()) {
            throw new DomainException(ErrorCode.BAD_REQUEST, "Published contest only allows description updates");
        }
        if (request.penaltyMinutes() != null && normalizePenaltyMinutes(request.penaltyMinutes()) != effectivePenaltyMinutes(contest)) {
            throw new DomainException(ErrorCode.BAD_REQUEST, "Published contest only allows description updates");
        }
        if (request.cePenalty() != null && request.cePenalty() != Boolean.TRUE.equals(contest.getCePenalty())) {
            throw new DomainException(ErrorCode.BAD_REQUEST, "Published contest only allows description updates");
        }
        if (request.aiPolicyMode() != null && request.aiPolicyMode() != effectiveAiPolicyMode(contest)) {
            throw new DomainException(ErrorCode.BAD_REQUEST, "Published contest only allows description updates");
        }
        if (request.aiPolicyNotes() != null && !request.aiPolicyNotes().trim().equals(
                contest.getAiPolicyNotes() == null ? "" : contest.getAiPolicyNotes())) {
            throw new DomainException(ErrorCode.BAD_REQUEST, "Published contest only allows description updates");
        }
    }

    private ContestAiPolicyMode effectiveAiPolicyMode(ContestEntity contest) {
        return contest.getAiPolicyMode() == null ? ContestAiPolicyMode.DEFAULT : contest.getAiPolicyMode();
    }

    private int normalizePenaltyMinutes(Integer penaltyMinutes) {
        if (penaltyMinutes == null) {
            return 20;
        }
        if (penaltyMinutes < 0 || penaltyMinutes > 300) {
            throw new DomainException(ErrorCode.BAD_REQUEST, "Contest penalty minutes must be between 0 and 300");
        }
        return penaltyMinutes;
    }

    private int effectivePenaltyMinutes(ContestEntity contest) {
        return contest.getPenaltyMinutes() == null ? 20 : contest.getPenaltyMinutes();
    }

    private String normalizeLabel(String label) {
        if (!StringUtils.hasText(label)) {
            throw new DomainException(ErrorCode.BAD_REQUEST, "Contest problem label is required");
        }
        return label.trim().toUpperCase();
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
}
