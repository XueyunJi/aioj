package com.aioj.next.contract.notification;

import jakarta.validation.constraints.NotNull;

/**
 * When no subject is supplied, all unread notifications of the requested type
 * belonging to the authenticated user are marked read.
 */
public record UserNotificationMarkReadRequest(
        @NotNull UserNotificationType type,
        String subjectType,
        String subjectId
) {
}
