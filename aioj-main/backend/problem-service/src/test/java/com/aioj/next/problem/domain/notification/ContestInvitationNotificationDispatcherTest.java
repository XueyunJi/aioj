package com.aioj.next.problem.domain.notification;

import com.aioj.next.problem.config.ContestInvitationNotificationProperties;
import com.aioj.next.problem.persistence.entity.ContestRegistrationEntity;
import com.aioj.next.problem.persistence.mapper.ContestRegistrationMapper;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.List;
import java.util.concurrent.Executor;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class ContestInvitationNotificationDispatcherTest {
    @Mock
    private ContestRegistrationMapper registrationMapper;
    @Mock
    private ContestInvitationNotificationDeliveryService deliveryService;

    @Test
    void publishRequestDispatchesOnlyThatRunPendingRows() {
        ContestRegistrationEntity registration = new ContestRegistrationEntity();
        registration.setId(701L);
        when(registrationMapper.selectPendingInvitationNotificationsForRun(eq(401L), any(), eq(25)))
                .thenReturn(List.of(registration));
        ContestInvitationNotificationDispatcher dispatcher = dispatcher();

        dispatcher.onDeliveryRequested(new ContestInvitationNotificationRequestedEvent(401L));

        verify(deliveryService).deliver(701L);
    }

    @Test
    void reconciliationUsesTheBoundedGlobalRetryQuery() {
        ContestRegistrationEntity registration = new ContestRegistrationEntity();
        registration.setId(702L);
        when(registrationMapper.selectPendingInvitationNotifications(any(), eq(25))).thenReturn(List.of(registration));
        ContestInvitationNotificationDispatcher dispatcher = dispatcher();

        dispatcher.reconcilePendingDeliveries();

        verify(deliveryService).deliver(702L);
    }

    private ContestInvitationNotificationDispatcher dispatcher() {
        ContestInvitationNotificationProperties properties = new ContestInvitationNotificationProperties();
        properties.setBatchSize(25);
        Executor directExecutor = Runnable::run;
        return new ContestInvitationNotificationDispatcher(registrationMapper, deliveryService, properties, directExecutor);
    }
}
