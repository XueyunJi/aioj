package com.aioj.next.problem.domain;

import com.aioj.next.common.api.PageResponse;
import com.aioj.next.common.error.DomainException;
import com.aioj.next.common.error.ErrorCode;
import com.aioj.next.common.security.Role;
import com.aioj.next.common.security.SecuritySupport;
import com.aioj.next.contract.contest.ContestAnnouncementRequest;
import com.aioj.next.contract.contest.ContestAnnouncementResponse;
import com.aioj.next.contract.contest.ContestAnnouncementStatus;
import com.aioj.next.contract.contest.ContestClarificationCreateRequest;
import com.aioj.next.contract.contest.ContestClarificationReplyRequest;
import com.aioj.next.contract.contest.ContestClarificationResponse;
import com.aioj.next.contract.contest.ContestClarificationStatus;
import com.aioj.next.contract.contest.ContestClarificationVisibility;
import com.aioj.next.contract.contest.ContestParticipantStatus;
import com.aioj.next.contract.contest.ContestRegistrationAccess;
import com.aioj.next.contract.contest.ContestRegistrationStatus;
import com.aioj.next.contract.contest.ContestRunStatus;
import com.aioj.next.contract.contest.ContestStatus;
import com.aioj.next.contract.learning.LearningGroupMemberRole;
import com.aioj.next.problem.persistence.entity.ContestAnnouncementEntity;
import com.aioj.next.problem.persistence.entity.ContestClarificationEntity;
import com.aioj.next.problem.persistence.entity.ContestEntity;
import com.aioj.next.problem.persistence.entity.ContestParticipantEntity;
import com.aioj.next.problem.persistence.entity.ContestRegistrationEntity;
import com.aioj.next.problem.persistence.entity.ContestRunAllowedGroupEntity;
import com.aioj.next.problem.persistence.entity.ContestRunEntity;
import com.aioj.next.problem.persistence.entity.ContestRunProblemSnapshotEntity;
import com.aioj.next.problem.persistence.entity.LearningGroupMemberEntity;
import com.aioj.next.problem.persistence.mapper.ContestAnnouncementMapper;
import com.aioj.next.problem.persistence.mapper.ContestClarificationMapper;
import com.aioj.next.problem.persistence.mapper.ContestParticipantMapper;
import com.aioj.next.problem.persistence.mapper.ContestRegistrationMapper;
import com.aioj.next.problem.persistence.mapper.ContestRunAllowedGroupMapper;
import com.aioj.next.problem.persistence.mapper.ContestRunProblemSnapshotMapper;
import com.aioj.next.problem.persistence.mapper.LearningGroupMemberMapper;
import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.util.StringUtils;

import java.time.Instant;
import java.util.List;
import java.util.Set;
import java.util.Objects;

@Service
public class ContestCommunicationService {
    private static final int MAX_TITLE_LENGTH = 160;
    private static final int MAX_QUESTION_LENGTH = 2000;

    private final ContestRunService contestRunService;
    private final ContestAnnouncementMapper announcementMapper;
    private final ContestClarificationMapper clarificationMapper;
    private final ContestParticipantMapper participantMapper;
    private final ContestRegistrationMapper registrationMapper;
    private final ContestRunAllowedGroupMapper allowedGroupMapper;
    private final LearningGroupMemberMapper learningGroupMemberMapper;
    private final ContestRunProblemSnapshotMapper problemSnapshotMapper;
    private final ContestProblemVisibilityService visibilityService;

    public ContestCommunicationService(ContestRunService contestRunService,
                                       ContestAnnouncementMapper announcementMapper,
                                       ContestClarificationMapper clarificationMapper,
                                       ContestParticipantMapper participantMapper,
                                       ContestRegistrationMapper registrationMapper,
                                       ContestRunAllowedGroupMapper allowedGroupMapper,
                                       LearningGroupMemberMapper learningGroupMemberMapper,
                                       ContestRunProblemSnapshotMapper problemSnapshotMapper,
                                       ContestProblemVisibilityService visibilityService) {
        this.contestRunService = contestRunService;
        this.announcementMapper = announcementMapper;
        this.clarificationMapper = clarificationMapper;
        this.participantMapper = participantMapper;
        this.registrationMapper = registrationMapper;
        this.allowedGroupMapper = allowedGroupMapper;
        this.learningGroupMemberMapper = learningGroupMemberMapper;
        this.visibilityService = visibilityService;
        this.problemSnapshotMapper = problemSnapshotMapper;
    }

    public List<ContestAnnouncementResponse> listAnnouncements(Long contestId, Long runId, boolean includeArchived) {
        ContestEntity contest = contestRunService.requireContest(contestId);
        ContestRunEntity run = contestRunService.requireRun(contestId, runId);
        boolean staff = contestRunService.canManage(contest);
        if (!staff && !canViewRunShell(contest, run, SecuritySupport.currentUserId())) {
            throw new DomainException(ErrorCode.FORBIDDEN, "Cannot access contest run announcements");
        }
        LambdaQueryWrapper<ContestAnnouncementEntity> query = new LambdaQueryWrapper<ContestAnnouncementEntity>()
                .eq(ContestAnnouncementEntity::getContestRunId, runId)
                .eq(ContestAnnouncementEntity::getContestId, contestId)
                .eq(!staff || !includeArchived, ContestAnnouncementEntity::getStatus, ContestAnnouncementStatus.PUBLISHED)
                .orderByDesc(ContestAnnouncementEntity::getPinned)
                .orderByDesc(ContestAnnouncementEntity::getPublishedAt)
                .orderByDesc(ContestAnnouncementEntity::getId);
        return announcementMapper.selectList(query).stream().map(this::toAnnouncementResponse).toList();
    }

    @Transactional
    public ContestAnnouncementResponse createAnnouncement(Long contestId, Long runId, ContestAnnouncementRequest request) {
        ContestEntity contest = requireManageableRun(contestId, runId);
        ContestRunEntity run = contestRunService.requireRun(contest.getId(), runId);
        if (ContestRunStatePolicy.isArchived(run)) {
            throw new DomainException(ErrorCode.BAD_REQUEST, "Archived contest run cannot publish announcements");
        }
        Instant now = Instant.now();
        ContestAnnouncementEntity entity = new ContestAnnouncementEntity();
        entity.setContestId(contestId);
        entity.setContestRunId(runId);
        entity.setAuthorUserId(SecuritySupport.currentUserId());
        entity.setTitle(normalizeTitle(request.title()));
        entity.setContent(normalizeBody(request.content(), "Announcement content is required"));
        entity.setPinned(Boolean.TRUE.equals(request.pinned()));
        entity.setStatus(ContestAnnouncementStatus.PUBLISHED);
        entity.setPublishedAt(now);
        entity.setCreatedAt(now);
        entity.setUpdatedAt(now);
        announcementMapper.insert(entity);
        return toAnnouncementResponse(entity);
    }

    @Transactional
    public ContestAnnouncementResponse updateAnnouncement(Long contestId, Long runId, Long announcementId,
                                                          ContestAnnouncementRequest request) {
        requireManageableRun(contestId, runId);
        ContestAnnouncementEntity entity = requireAnnouncement(contestId, runId, announcementId);
        if (StringUtils.hasText(request.title())) {
            entity.setTitle(normalizeTitle(request.title()));
        }
        if (request.content() != null) {
            entity.setContent(normalizeBody(request.content(), "Announcement content is required"));
        }
        if (request.pinned() != null) {
            entity.setPinned(request.pinned());
        }
        entity.setUpdatedAt(Instant.now());
        announcementMapper.updateById(entity);
        return toAnnouncementResponse(entity);
    }

    @Transactional
    public ContestAnnouncementResponse archiveAnnouncement(Long contestId, Long runId, Long announcementId) {
        requireManageableRun(contestId, runId);
        ContestAnnouncementEntity entity = requireAnnouncement(contestId, runId, announcementId);
        if (entity.getStatus() == ContestAnnouncementStatus.ARCHIVED) {
            return toAnnouncementResponse(entity);
        }
        Instant now = Instant.now();
        entity.setStatus(ContestAnnouncementStatus.ARCHIVED);
        entity.setArchivedAt(now);
        entity.setUpdatedAt(now);
        announcementMapper.updateById(entity);
        return toAnnouncementResponse(entity);
    }

    @Transactional
    public ContestAnnouncementResponse restoreAnnouncement(Long contestId, Long runId, Long announcementId) {
        requireManageableRun(contestId, runId);
        ContestAnnouncementEntity entity = requireAnnouncement(contestId, runId, announcementId);
        entity.setStatus(ContestAnnouncementStatus.PUBLISHED);
        entity.setArchivedAt(null);
        entity.setUpdatedAt(Instant.now());
        announcementMapper.updateById(entity);
        return toAnnouncementResponse(entity);
    }

    public PageResponse<ContestClarificationResponse> listClarifications(Long contestId, Long runId,
                                                                         ContestClarificationStatus status,
                                                                         ContestClarificationVisibility visibility,
                                                                         Long contestProblemId,
                                                                         boolean staffView,
                                                                         long page,
                                                                         long pageSize) {
        ContestEntity contest = contestRunService.requireContest(contestId);
        ContestRunEntity run = contestRunService.requireRun(contestId, runId);
        boolean canManage = contestRunService.canManage(contest);
        // Staff filtering only applies when the caller explicitly uses the admin staff view;
        // student-facing surfaces behave identically for every identity.
        boolean staff = canManage && staffView;
        Long userId = SecuritySupport.currentUserId();
        if (!canManage && !canViewRunShell(contest, run, userId)) {
            throw new DomainException(ErrorCode.FORBIDDEN, "Cannot access contest run clarifications");
        }

        LambdaQueryWrapper<ContestClarificationEntity> query = new LambdaQueryWrapper<ContestClarificationEntity>()
                .eq(ContestClarificationEntity::getContestRunId, runId)
                .eq(ContestClarificationEntity::getContestId, contestId)
                .eq(status != null, ContestClarificationEntity::getStatus, status)
                .eq(visibility != null, ContestClarificationEntity::getAnswerVisibility, visibility)
                .eq(contestProblemId != null, ContestClarificationEntity::getContestProblemId, contestProblemId)
                .orderByDesc(ContestClarificationEntity::getCreatedAt)
                .orderByDesc(ContestClarificationEntity::getId);
        if (!staff) {
            query.and(wrapper -> wrapper
                    .eq(ContestClarificationEntity::getUserId, userId)
                    .or()
                    .eq(ContestClarificationEntity::getAnswerVisibility, ContestClarificationVisibility.PUBLIC));
            Set<Long> hiddenContestProblemIds = visibilityService.hiddenContestProblemIdsForRun(run, Instant.now());
            if (!hiddenContestProblemIds.isEmpty()) {
                query.notIn(ContestClarificationEntity::getContestProblemId, hiddenContestProblemIds);
            }
        }
        Page<ContestClarificationEntity> result = clarificationMapper.selectPage(
                new Page<>(normalizePage(page), normalizePageSize(pageSize)), query);
        return new PageResponse<>(result.getRecords().stream()
                .map(entity -> toClarificationResponse(entity, staff, userId))
                .toList(), result.getTotal(), result.getCurrent(), result.getSize());
    }

    @Transactional
    public ContestClarificationResponse createClarification(Long contestId, Long runId,
                                                            ContestClarificationCreateRequest request) {
        ContestEntity contest = contestRunService.requireContest(contestId);
        ContestRunEntity run = contestRunService.requireRun(contestId, runId);
        Long userId = SecuritySupport.currentUserId();
        ContestParticipantEntity participant = activeParticipant(runId, userId);
        if (participant == null || !runAcceptsQuestions(run, Instant.now())) {
            throw new DomainException(ErrorCode.FORBIDDEN, "Contest run is not accepting clarifications");
        }
        if (!canViewRunShell(contest, run, userId)) {
            throw new DomainException(ErrorCode.FORBIDDEN, "Cannot access contest run");
        }
        Long contestProblemId = request.contestProblemId();
        if (contestProblemId != null) {
            requireRunProblem(runId, contestProblemId);
        }
        Instant now = Instant.now();
        ContestClarificationEntity entity = new ContestClarificationEntity();
        entity.setContestId(contestId);
        entity.setContestRunId(runId);
        entity.setContestProblemId(contestProblemId);
        entity.setParticipantId(participant.getId());
        entity.setUserId(userId);
        entity.setQuestion(normalizeQuestion(request.question()));
        entity.setStatus(ContestClarificationStatus.OPEN);
        entity.setCreatedAt(now);
        entity.setUpdatedAt(now);
        clarificationMapper.insert(entity);
        return toClarificationResponse(entity, false, userId);
    }

    @Transactional
    public ContestClarificationResponse replyClarification(Long contestId, Long runId, Long clarificationId,
                                                           ContestClarificationReplyRequest request) {
        requireManageableRun(contestId, runId);
        ContestClarificationEntity entity = requireClarification(contestId, runId, clarificationId);
        Instant now = Instant.now();
        entity.setAnswer(normalizeBody(request.answer(), "Clarification answer is required"));
        entity.setAnswerVisibility(request.visibility() == null ? ContestClarificationVisibility.PRIVATE : request.visibility());
        entity.setAnsweredBy(SecuritySupport.currentUserId());
        entity.setAnsweredAt(now);
        entity.setStatus(ContestClarificationStatus.ANSWERED);
        entity.setUpdatedAt(now);
        clarificationMapper.updateById(entity);
        return toClarificationResponse(entity, true, SecuritySupport.currentUserId());
    }

    @Transactional
    public ContestClarificationResponse closeClarification(Long contestId, Long runId, Long clarificationId) {
        requireManageableRun(contestId, runId);
        ContestClarificationEntity entity = requireClarification(contestId, runId, clarificationId);
        Instant now = Instant.now();
        entity.setStatus(ContestClarificationStatus.CLOSED);
        entity.setClosedAt(now);
        entity.setUpdatedAt(now);
        clarificationMapper.updateById(entity);
        return toClarificationResponse(entity, true, SecuritySupport.currentUserId());
    }

    private ContestEntity requireManageableRun(Long contestId, Long runId) {
        ContestEntity contest = contestRunService.requireContest(contestId);
        contestRunService.requireRun(contestId, runId);
        if (!contestRunService.canManage(contest)) {
            throw new DomainException(ErrorCode.FORBIDDEN, "Cannot manage contest run communications");
        }
        return contest;
    }

    private boolean canViewRunShell(ContestEntity contest, ContestRunEntity run, Long userId) {
        if (contest.getStatus() != ContestStatus.PUBLISHED || run.getDeletedAt() != null
                || run.getStatus() == ContestRunStatus.DRAFT || run.getStatus() == ContestRunStatus.ARCHIVED) {
            return false;
        }
        ContestRegistrationAccess access = run.getRegistrationAccess() == null
                ? ContestRegistrationAccess.PUBLIC
                : run.getRegistrationAccess();
        return switch (access) {
            case PUBLIC -> true;
            case GROUPS -> isAllowedGroupStudent(run.getId(), userId)
                    || registrationFor(run.getId(), userId) != null
                    || participantFor(run.getId(), userId) != null;
            case INVITE_ONLY -> registrationFor(run.getId(), userId) != null
                    || participantFor(run.getId(), userId) != null;
        };
    }

    private boolean runAcceptsQuestions(ContestRunEntity run, Instant now) {
        return run.getStatus() != ContestRunStatus.DRAFT
                && run.getStatus() != ContestRunStatus.ARCHIVED
                && run.getStartAt() != null
                && run.getEndAt() != null
                && !now.isBefore(run.getStartAt())
                && now.isBefore(run.getEndAt());
    }

    private ContestParticipantEntity activeParticipant(Long runId, Long userId) {
        ContestParticipantEntity participant = participantFor(runId, userId);
        return participant != null && participant.getStatus() == ContestParticipantStatus.ACTIVE ? participant : null;
    }

    private ContestParticipantEntity participantFor(Long runId, Long userId) {
        return participantMapper.selectOne(new LambdaQueryWrapper<ContestParticipantEntity>()
                .eq(ContestParticipantEntity::getContestRunId, runId)
                .eq(ContestParticipantEntity::getUserId, userId));
    }

    private ContestRegistrationEntity registrationFor(Long runId, Long userId) {
        return registrationMapper.selectOne(new LambdaQueryWrapper<ContestRegistrationEntity>()
                .eq(ContestRegistrationEntity::getContestRunId, runId)
                .eq(ContestRegistrationEntity::getUserId, userId)
                .in(ContestRegistrationEntity::getStatus, List.of(ContestRegistrationStatus.PENDING,
                        ContestRegistrationStatus.APPROVED)));
    }

    private boolean isAllowedGroupStudent(Long runId, Long userId) {
        List<Long> groupIds = allowedGroupMapper.selectList(new LambdaQueryWrapper<ContestRunAllowedGroupEntity>()
                        .eq(ContestRunAllowedGroupEntity::getContestRunId, runId))
                .stream()
                .map(ContestRunAllowedGroupEntity::getGroupId)
                .toList();
        if (groupIds.isEmpty()) {
            return false;
        }
        return learningGroupMemberMapper.selectCount(new LambdaQueryWrapper<LearningGroupMemberEntity>()
                .in(LearningGroupMemberEntity::getGroupId, groupIds)
                .eq(LearningGroupMemberEntity::getUserId, userId)
                .eq(LearningGroupMemberEntity::getRole, LearningGroupMemberRole.STUDENT)) > 0;
    }

    private ContestRunProblemSnapshotEntity requireRunProblem(Long runId, Long contestProblemId) {
        ContestRunProblemSnapshotEntity snapshot = problemSnapshotMapper.selectOne(
                new LambdaQueryWrapper<ContestRunProblemSnapshotEntity>()
                        .eq(ContestRunProblemSnapshotEntity::getContestRunId, runId)
                        .eq(ContestRunProblemSnapshotEntity::getContestProblemId, contestProblemId));
        if (snapshot == null) {
            throw new DomainException(ErrorCode.BAD_REQUEST, "Contest problem does not belong to this run");
        }
        return snapshot;
    }

    private ContestAnnouncementEntity requireAnnouncement(Long contestId, Long runId, Long id) {
        ContestAnnouncementEntity entity = announcementMapper.selectById(id);
        if (entity == null || !Objects.equals(entity.getContestId(), contestId)
                || !Objects.equals(entity.getContestRunId(), runId)) {
            throw new DomainException(ErrorCode.NOT_FOUND, "Contest announcement not found");
        }
        return entity;
    }

    private ContestClarificationEntity requireClarification(Long contestId, Long runId, Long id) {
        ContestClarificationEntity entity = clarificationMapper.selectById(id);
        if (entity == null || !Objects.equals(entity.getContestId(), contestId)
                || !Objects.equals(entity.getContestRunId(), runId)) {
            throw new DomainException(ErrorCode.NOT_FOUND, "Contest clarification not found");
        }
        return entity;
    }

    private ContestAnnouncementResponse toAnnouncementResponse(ContestAnnouncementEntity entity) {
        return new ContestAnnouncementResponse(entity.getId(), entity.getContestId(), entity.getContestRunId(),
                entity.getAuthorUserId(), entity.getTitle(), entity.getContent(), Boolean.TRUE.equals(entity.getPinned()),
                entity.getStatus(), entity.getPublishedAt(), entity.getArchivedAt(), entity.getCreatedAt(), entity.getUpdatedAt());
    }

    private ContestClarificationResponse toClarificationResponse(ContestClarificationEntity entity, boolean staff, Long currentUserId) {
        boolean mine = Objects.equals(entity.getUserId(), currentUserId);
        boolean publicAnswer = entity.getAnswerVisibility() == ContestClarificationVisibility.PUBLIC;
        Long visibleUserId = staff || mine ? entity.getUserId() : null;
        Long visibleParticipantId = staff || mine ? entity.getParticipantId() : null;
        return new ContestClarificationResponse(entity.getId(), entity.getContestId(), entity.getContestRunId(),
                entity.getContestProblemId(), visibleParticipantId, visibleUserId, entity.getQuestion(), entity.getStatus(),
                entity.getAnswer(), entity.getAnswerVisibility(), entity.getAnsweredBy(), entity.getAnsweredAt(),
                entity.getClosedAt(), mine, publicAnswer, entity.getCreatedAt(), entity.getUpdatedAt());
    }

    private String normalizeTitle(String value) {
        if (!StringUtils.hasText(value)) {
            throw new DomainException(ErrorCode.BAD_REQUEST, "Announcement title is required");
        }
        String trimmed = value.trim();
        return trimmed.length() > MAX_TITLE_LENGTH ? trimmed.substring(0, MAX_TITLE_LENGTH) : trimmed;
    }

    private String normalizeBody(String value, String message) {
        if (!StringUtils.hasText(value)) {
            throw new DomainException(ErrorCode.BAD_REQUEST, message);
        }
        return value.trim();
    }

    private String normalizeQuestion(String value) {
        if (!StringUtils.hasText(value)) {
            throw new DomainException(ErrorCode.BAD_REQUEST, "Clarification question is required");
        }
        String trimmed = value.trim();
        if (trimmed.length() > MAX_QUESTION_LENGTH) {
            throw new DomainException(ErrorCode.BAD_REQUEST, "Clarification question is too long");
        }
        return trimmed;
    }

    private long normalizePage(long page) {
        return page < 1 ? 1 : page;
    }

    private long normalizePageSize(long pageSize) {
        if (pageSize < 1) {
            return 20;
        }
        return Math.min(pageSize, 200);
    }
}
