package com.aioj.next.contract.notification;

/**
 * A wake-up signal for clients. Consumers must re-read notification details
 * through the normal authenticated REST endpoint.
 */
public record UserNotificationStreamEvent(
        Long id,
        UserNotificationType type,
        String subjectType,
        String subjectId
) {
}
