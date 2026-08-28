package com.aioj.next.contract.contest;

public enum ContestInvitationBatchItemStatus {
    /** The invitation is durable but remains invisible until the run is published. */
    SAVED_FOR_PUBLISH,
    /** The run is published and a bounded delivery worker will create the notification. */
    QUEUED_FOR_NOTIFICATION,
    /** The target already has a current registration or invitation. */
    UNCHANGED,
    /** This one target could not be invited; other selected targets continue. */
    FAILED
}
