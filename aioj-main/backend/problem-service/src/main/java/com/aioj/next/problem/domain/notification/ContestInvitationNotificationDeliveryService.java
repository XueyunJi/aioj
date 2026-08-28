package com.aioj.next.problem.domain.notification;

import com.aioj.next.contract.contest.ContestRegistrationStatus;
import com.aioj.next.contract.contest.ContestRunStatus;
import com.aioj.next.contract.contest.ContestStatus;
import com.aioj.next.problem.persistence.entity.ContestEntity;
import com.aioj.next.problem.persistence.entity.ContestRegistrationEntity;
import com.aioj.next.problem.persistence.entity.ContestRunEntity;
import com.aioj.next.problem.persistence.mapper.ContestMapper;
import com.aioj.next.problem.persistence.mapper.ContestRegistrationMapper;
import com.aioj.next.problem.persistence.mapper.ContestRunMapper;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;

@Service
public class ContestInvitationNotificationDeliveryService {
    private final ContestRegistrationMapper registrationMapper;
    private final ContestRunMapper contestRunMapper;
    private final ContestMapper contestMapper;
    private final UserNotificationService notificationService;

    public ContestInvitationNotificationDeliveryService(ContestRegistrationMapper registrationMapper,
                                                        ContestRunMapper contestRunMapper,
                                                        ContestMapper contestMapper,
                                                        UserNotificationService notificationService) {
        this.registrationMapper = registrationMapper;
        this.contestRunMapper = contestRunMapper;
        this.contestMapper = contestMapper;
        this.notificationService = notificationService;
    }

    @Transactional
    public void deliver(Long registrationId) {
        if (registrationId == null) {
            return;
        }
        ContestRegistrationEntity registration = registrationMapper.selectById(registrationId);
        if (registration == null || registration.getStatus() != ContestRegistrationStatus.INVITED) {
            return;
        }
        long version = valueOrZero(registration.getInvitationNotificationVersion());
        if (version <= 0 || version <= valueOrZero(registration.getInvitationNotificationDeliveredVersion())) {
            return;
        }
        ContestRunEntity run = contestRunMapper.selectById(registration.getContestRunId());
        ContestEntity contest = contestMapper.selectById(registration.getContestId());
        Instant now = Instant.now();
        if (!isDeliverable(contest, run, now)) {
            return;
        }
        notificationService.createContestInvitation(registration.getUserId(), registration.getId(), run.getId(), version, now);
        registrationMapper.markInvitationNotificationDelivered(registration.getId(), version, now);
    }

    private boolean isDeliverable(ContestEntity contest, ContestRunEntity run, Instant now) {
        return contest != null
                && contest.getDeletedAt() == null
                && contest.getStatus() == ContestStatus.PUBLISHED
                && run != null
                && run.getDeletedAt() == null
                && run.getStatus() != ContestRunStatus.DRAFT
                && run.getStatus() != ContestRunStatus.ARCHIVED
                && run.getEndAt() != null
                && now.isBefore(run.getEndAt());
    }

    private long valueOrZero(Long value) {
        return value == null ? 0L : value;
    }
}
