package com.aioj.next.problem.domain.notification;

import com.aioj.next.common.api.PageResponse;
import com.aioj.next.common.security.SecuritySupport;
import com.aioj.next.contract.notification.UserNotificationMarkReadRequest;
import com.aioj.next.contract.notification.UserNotificationMarkReadResponse;
import com.aioj.next.contract.notification.UserNotificationResponse;
import com.aioj.next.contract.notification.UserNotificationStreamEvent;
import com.aioj.next.contract.notification.UserNotificationType;
import com.aioj.next.contract.notification.UserNotificationUnreadCountResponse;
import com.aioj.next.problem.persistence.entity.OperationJobEntity;
import com.aioj.next.problem.persistence.entity.UserNotificationEntity;
import com.aioj.next.problem.persistence.mapper.UserNotificationMapper;
import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.conditions.update.LambdaUpdateWrapper;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.util.StringUtils;

import java.time.Instant;

/**
 * Persistent notification facts are the source of truth. The matching SSE
 * event is deliberately only a post-commit wake-up signal.
 */
@Service
public class UserNotificationService {
    public static final String SUBJECT_CONTEST_REGISTRATION = "CONTEST_REGISTRATION";
    public static final String SUBJECT_OPERATION_JOB = "OPERATION_JOB";
    public static final String SCOPE_CONTEST_RUN = "CONTEST_RUN";

    private final UserNotificationMapper notificationMapper;
    private final ApplicationEventPublisher eventPublisher;

    public UserNotificationService(UserNotificationMapper notificationMapper,
                                   ApplicationEventPublisher eventPublisher) {
        this.notificationMapper = notificationMapper;
        this.eventPublisher = eventPublisher;
    }

    @Transactional
    public void createContestInvitation(Long recipientUserId, Long registrationId,
                                        Long contestRunId, Instant invitedAt) {
        createContestInvitation(recipientUserId, registrationId, contestRunId, 1L, invitedAt);
    }

    /**
     * Delivery is versioned by the registration so a draft invitation can be
     * deliberately re-issued when its run becomes visible to students.
     */
    @Transactional
    public void createContestInvitation(Long recipientUserId, Long registrationId,
                                        Long contestRunId, long notificationVersion, Instant createdAt) {
        if (recipientUserId == null || registrationId == null || contestRunId == null) {
            return;
        }
        if (notificationVersion <= 0) {
            return;
        }
        createIfAbsent(recipientUserId, UserNotificationType.CONTEST_INVITATION,
                SUBJECT_CONTEST_REGISTRATION, String.valueOf(registrationId),
                SCOPE_CONTEST_RUN, String.valueOf(contestRunId),
                "contest-invitation:v2:" + registrationId + ":" + notificationVersion,
                "{\"registrationId\":\"" + registrationId + "\"}", createdAt == null ? Instant.now() : createdAt);
    }

    @Transactional
    public void createStudentPostmortemTerminal(Long recipientUserId, OperationJobEntity job,
                                                 UserNotificationType type) {
        if (recipientUserId == null || job == null || job.getId() == null || job.getContestRunId() == null
                || (type != UserNotificationType.STUDENT_POSTMORTEM_JOB_COMPLETED
                && type != UserNotificationType.STUDENT_POSTMORTEM_JOB_FAILED)) {
            return;
        }
        Instant createdAt = job.getCompletedAt() == null ? Instant.now() : job.getCompletedAt();
        createIfAbsent(recipientUserId, type, SUBJECT_OPERATION_JOB, String.valueOf(job.getId()),
                SCOPE_CONTEST_RUN, String.valueOf(job.getContestRunId()),
                "student-postmortem:" + job.getId() + ":" + type.name(),
                "{\"operationJobId\":\"" + job.getId() + "\"}", createdAt);
    }

    public PageResponse<UserNotificationResponse> list(UserNotificationType type,
                                                       String subjectType,
                                                       String subjectId,
                                                       String scopeType,
                                                       String scopeId,
                                                       boolean unreadOnly,
                                                       long page,
                                                       long pageSize) {
        Long recipientUserId = SecuritySupport.currentUserId();
        LambdaQueryWrapper<UserNotificationEntity> query = new LambdaQueryWrapper<UserNotificationEntity>()
                .eq(UserNotificationEntity::getRecipientUserId, recipientUserId)
                .eq(type != null, UserNotificationEntity::getNotificationType, type)
                .eq(StringUtils.hasText(subjectType), UserNotificationEntity::getSubjectType, trim(subjectType))
                .eq(StringUtils.hasText(subjectId), UserNotificationEntity::getSubjectId, trim(subjectId))
                .eq(StringUtils.hasText(scopeType), UserNotificationEntity::getScopeType, trim(scopeType))
                .eq(StringUtils.hasText(scopeId), UserNotificationEntity::getScopeId, trim(scopeId))
                .isNull(unreadOnly, UserNotificationEntity::getReadAt)
                .orderByDesc(UserNotificationEntity::getCreatedAt)
                .orderByDesc(UserNotificationEntity::getId);
        Page<UserNotificationEntity> result = notificationMapper.selectPage(
                new Page<>(normalizePage(page), normalizePageSize(pageSize)), query);
        return new PageResponse<>(result.getRecords().stream().map(this::toResponse).toList(),
                result.getTotal(), result.getCurrent(), result.getSize());
    }

    public UserNotificationUnreadCountResponse unreadCount(UserNotificationType type) {
        Long recipientUserId = SecuritySupport.currentUserId();
        if (type == UserNotificationType.CONTEST_INVITATION) {
            Long visible = notificationMapper.countVisibleUnreadContestInvitations(recipientUserId, Instant.now());
            return new UserNotificationUnreadCountResponse(visible == null ? 0 : visible);
        }
        Long count = notificationMapper.selectCount(new LambdaQueryWrapper<UserNotificationEntity>()
                .eq(UserNotificationEntity::getRecipientUserId, recipientUserId)
                .eq(type != null, UserNotificationEntity::getNotificationType, type)
                .isNull(UserNotificationEntity::getReadAt));
        return new UserNotificationUnreadCountResponse(count == null ? 0 : count);
    }

    @Transactional
    public UserNotificationMarkReadResponse markCurrentUserRead(UserNotificationMarkReadRequest request) {
        Long recipientUserId = SecuritySupport.currentUserId();
        Instant now = Instant.now();
        int changed = notificationMapper.update(null, new LambdaUpdateWrapper<UserNotificationEntity>()
                .eq(UserNotificationEntity::getRecipientUserId, recipientUserId)
                .eq(UserNotificationEntity::getNotificationType, request.type())
                .eq(StringUtils.hasText(request.subjectType()), UserNotificationEntity::getSubjectType, trim(request.subjectType()))
                .eq(StringUtils.hasText(request.subjectId()), UserNotificationEntity::getSubjectId, trim(request.subjectId()))
                .isNull(UserNotificationEntity::getReadAt)
                .set(UserNotificationEntity::getReadAt, now));
        return new UserNotificationMarkReadResponse(changed);
    }

    @Transactional
    public void markContestInvitationRead(Long recipientUserId, Long registrationId) {
        if (recipientUserId == null || registrationId == null) {
            return;
        }
        notificationMapper.update(null, new LambdaUpdateWrapper<UserNotificationEntity>()
                .eq(UserNotificationEntity::getRecipientUserId, recipientUserId)
                .eq(UserNotificationEntity::getNotificationType, UserNotificationType.CONTEST_INVITATION)
                .eq(UserNotificationEntity::getSubjectType, SUBJECT_CONTEST_REGISTRATION)
                .eq(UserNotificationEntity::getSubjectId, String.valueOf(registrationId))
                .isNull(UserNotificationEntity::getReadAt)
                .set(UserNotificationEntity::getReadAt, Instant.now()));
    }

    private void createIfAbsent(Long recipientUserId, UserNotificationType type,
                                String subjectType, String subjectId,
                                String scopeType, String scopeId,
                                String deduplicationKey, String payloadJson, Instant createdAt) {
        UserNotificationEntity entity = new UserNotificationEntity();
        entity.setRecipientUserId(recipientUserId);
        entity.setNotificationType(type);
        entity.setSubjectType(subjectType);
        entity.setSubjectId(subjectId);
        entity.setScopeType(scopeType);
        entity.setScopeId(scopeId);
        entity.setPayloadJson(payloadJson);
        entity.setDeduplicationKey(deduplicationKey);
        entity.setCreatedAt(createdAt);
        try {
            notificationMapper.insert(entity);
        } catch (DuplicateKeyException ignored) {
            return;
        }
        eventPublisher.publishEvent(new UserNotificationCreatedEvent(recipientUserId,
                new UserNotificationStreamEvent(entity.getId(), type, subjectType, subjectId)));
    }

    private UserNotificationResponse toResponse(UserNotificationEntity entity) {
        return new UserNotificationResponse(entity.getId(), entity.getNotificationType(), entity.getSubjectType(),
                entity.getSubjectId(), entity.getScopeType(), entity.getScopeId(), entity.getReadAt(), entity.getCreatedAt());
    }

    private long normalizePage(long page) {
        return Math.max(1, page);
    }

    private long normalizePageSize(long pageSize) {
        return Math.min(100, Math.max(1, pageSize));
    }

    private String trim(String value) {
        return value == null ? null : value.trim();
    }
}
