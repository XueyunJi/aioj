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
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.time.Instant;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class ContestInvitationNotificationDeliveryServiceTest {
    @Mock
    private ContestRegistrationMapper registrationMapper;
    @Mock
    private ContestRunMapper contestRunMapper;
    @Mock
    private ContestMapper contestMapper;
    @Mock
    private UserNotificationService notificationService;

    @Test
    void deliversOnlyTheCurrentOutstandingInvitationVersion() {
        ContestRegistrationEntity registration = invitation(701L, 301L, 2L, 1L);
        when(registrationMapper.selectById(701L)).thenReturn(registration);
        when(contestRunMapper.selectById(401L)).thenReturn(publishedRun());
        when(contestMapper.selectById(301L)).thenReturn(publishedContest());
        ContestInvitationNotificationDeliveryService service = service();

        service.deliver(701L);

        verify(notificationService).createContestInvitation(eq(1001L), eq(701L), eq(401L), eq(2L), any(Instant.class));
        verify(registrationMapper).markInvitationNotificationDelivered(eq(701L), eq(2L), any(Instant.class));
    }

    @Test
    void draftRunIsNeverDeliveredEvenWhenTheLedgerRowIsPending() {
        ContestRegistrationEntity registration = invitation(701L, 301L, 2L, 0L);
        ContestRunEntity draftRun = publishedRun();
        draftRun.setStatus(ContestRunStatus.DRAFT);
        when(registrationMapper.selectById(701L)).thenReturn(registration);
        when(contestRunMapper.selectById(401L)).thenReturn(draftRun);
        when(contestMapper.selectById(301L)).thenReturn(publishedContest());
        ContestInvitationNotificationDeliveryService service = service();

        service.deliver(701L);

        verify(notificationService, never()).createContestInvitation(eq(1001L), eq(701L), eq(401L), eq(2L), any(Instant.class));
        verify(registrationMapper, never()).markInvitationNotificationDelivered(eq(701L), eq(2L), any(Instant.class));
    }

    @Test
    void currentDeliveredVersionDoesNotCreateAnotherNotification() {
        ContestRegistrationEntity registration = invitation(701L, 301L, 2L, 2L);
        when(registrationMapper.selectById(701L)).thenReturn(registration);
        ContestInvitationNotificationDeliveryService service = service();

        service.deliver(701L);

        verify(notificationService, never()).createContestInvitation(any(), any(), any(), anyLong(), any(Instant.class));
        verify(registrationMapper, never()).markInvitationNotificationDelivered(any(), anyLong(), any(Instant.class));
    }

    private ContestInvitationNotificationDeliveryService service() {
        return new ContestInvitationNotificationDeliveryService(registrationMapper, contestRunMapper, contestMapper, notificationService);
    }

    private ContestRegistrationEntity invitation(Long id, Long contestId, Long version, Long deliveredVersion) {
        ContestRegistrationEntity registration = new ContestRegistrationEntity();
        registration.setId(id);
        registration.setContestId(contestId);
        registration.setContestRunId(401L);
        registration.setUserId(1001L);
        registration.setStatus(ContestRegistrationStatus.INVITED);
        registration.setInvitationNotificationVersion(version);
        registration.setInvitationNotificationDeliveredVersion(deliveredVersion);
        return registration;
    }

    private ContestEntity publishedContest() {
        ContestEntity contest = new ContestEntity();
        contest.setId(301L);
        contest.setStatus(ContestStatus.PUBLISHED);
        return contest;
    }

    private ContestRunEntity publishedRun() {
        ContestRunEntity run = new ContestRunEntity();
        run.setId(401L);
        run.setContestId(301L);
        run.setStatus(ContestRunStatus.SCHEDULED);
        run.setEndAt(Instant.now().plusSeconds(3600));
        return run;
    }
}
