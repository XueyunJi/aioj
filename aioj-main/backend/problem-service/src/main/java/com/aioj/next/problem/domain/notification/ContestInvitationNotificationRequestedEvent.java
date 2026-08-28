package com.aioj.next.problem.domain.notification;

/** Published after the registration transaction commits; delivery remains durable and retryable. */
public record ContestInvitationNotificationRequestedEvent(Long contestRunId) {
}
