package com.aioj.next.contract.contest;

public enum ContestRunStatus {
    DRAFT,
    /**
     * A draft whose configured end time has elapsed before publication. It is
     * an effective display/operation status; legacy rows remain DRAFT until
     * they are archived.
     */
    EXPIRED,
    SCHEDULED,
    RUNNING,
    ENDED,
    ARCHIVED
}
