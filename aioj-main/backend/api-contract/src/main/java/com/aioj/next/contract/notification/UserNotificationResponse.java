package com.aioj.next.contract.notification;

import java.time.Instant;

/**
 * Safe notification metadata only. Notification detail remains behind the
 * resource-specific, authorized API.
 */
public record UserNotificationResponse(
        Long id,
        UserNotificationType type,
        String subjectType,
        String subjectId,
        String scopeType,
        String scopeId,
        Instant readAt,
        Instant createdAt
) {
}
