package com.aioj.next.ai.agent.context;

/** Unified data classification (design doc §4.4 / §5). */
public enum DataClassification {
    PUBLIC,
    AUTHENTICATED,
    USER_PRIVATE,
    CONTEST_PUBLIC_ACTIVE,
    CONTEST_PRIVATE,
    STAFF_ONLY,
    SECRET
}
