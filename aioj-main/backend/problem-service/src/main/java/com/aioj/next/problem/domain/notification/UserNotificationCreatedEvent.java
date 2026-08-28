package com.aioj.next.problem.domain.notification;

import com.aioj.next.contract.notification.UserNotificationStreamEvent;

public record UserNotificationCreatedEvent(Long recipientUserId, UserNotificationStreamEvent notification) {
}
