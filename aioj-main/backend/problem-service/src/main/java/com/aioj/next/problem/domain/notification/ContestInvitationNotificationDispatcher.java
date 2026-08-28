package com.aioj.next.problem.domain.notification;

import com.aioj.next.problem.config.ContestInvitationNotificationProperties;
import com.aioj.next.problem.persistence.entity.ContestRegistrationEntity;
import com.aioj.next.problem.persistence.mapper.ContestRegistrationMapper;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.core.task.TaskRejectedException;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.springframework.transaction.event.TransactionPhase;
import org.springframework.transaction.event.TransactionalEventListener;

import java.time.Instant;
import java.util.List;
import java.util.concurrent.Executor;

/**
 * Schedules small durable-notification batches. The database row remains the
 * retry source; SSE is emitted later by UserNotificationService after commit.
 */
@Service
public class ContestInvitationNotificationDispatcher {
    private final ContestRegistrationMapper registrationMapper;
    private final ContestInvitationNotificationDeliveryService deliveryService;
    private final ContestInvitationNotificationProperties properties;
    private final Executor executor;

    public ContestInvitationNotificationDispatcher(ContestRegistrationMapper registrationMapper,
                                                   ContestInvitationNotificationDeliveryService deliveryService,
                                                   ContestInvitationNotificationProperties properties,
                                                   @Qualifier("contestInvitationNotificationExecutor") Executor executor) {
        this.registrationMapper = registrationMapper;
        this.deliveryService = deliveryService;
        this.properties = properties;
        this.executor = executor;
    }

    @TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT, fallbackExecution = true)
    public void onDeliveryRequested(ContestInvitationNotificationRequestedEvent event) {
        if (event == null || event.contestRunId() == null) {
            return;
        }
        enqueue(registrationMapper.selectPendingInvitationNotificationsForRun(
                event.contestRunId(), Instant.now(), batchSize()));
    }

    @Scheduled(fixedDelayString = "${aioj.contest-invitation-dispatch.poll-millis:5000}")
    public void reconcilePendingDeliveries() {
        enqueue(registrationMapper.selectPendingInvitationNotifications(Instant.now(), batchSize()));
    }

    private void enqueue(List<ContestRegistrationEntity> registrations) {
        if (registrations == null || registrations.isEmpty()) {
            return;
        }
        for (ContestRegistrationEntity registration : registrations) {
            if (registration == null || registration.getId() == null) {
                continue;
            }
            try {
                executor.execute(() -> deliveryService.deliver(registration.getId()));
            } catch (TaskRejectedException ignored) {
                // The next bounded reconciliation pass safely retries this row.
            }
        }
    }

    private int batchSize() {
        return Math.max(1, properties.getBatchSize());
    }
}
