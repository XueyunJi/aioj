package com.aioj.next.contract.notification;

/**
 * The intentionally small set of student-facing durable notifications. New
 * business producers must be added deliberately instead of becoming a global
 * broadcast channel.
 */
public enum UserNotificationType {
    CONTEST_INVITATION,
    STUDENT_POSTMORTEM_JOB_COMPLETED,
    STUDENT_POSTMORTEM_JOB_FAILED
}
