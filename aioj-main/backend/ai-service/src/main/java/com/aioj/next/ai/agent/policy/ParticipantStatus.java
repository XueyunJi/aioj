package com.aioj.next.ai.agent.policy;

/** L1 participation outcome (design doc §5.1). P0 always writes NON_PARTICIPANT. */
public enum ParticipantStatus {
    NON_PARTICIPANT,
    PARTICIPANT_ACTIVE,
    PARTICIPANT_GRACE
}
