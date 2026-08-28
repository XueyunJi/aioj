package com.aioj.next.contract.contest;

public enum ContestRegistrationStatus {
    PENDING,
    APPROVED,
    REJECTED,
    /** The invited user explicitly declined the invitation before joining. */
    DECLINED,
    CANCELLED,
    /**
     * Teacher/admin invited the user; the user becomes a participant only
     * after accepting the invitation.
     */
    INVITED
}
