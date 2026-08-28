package com.aioj.next.contract.contest;

public enum FairnessAlertType {
    HIGH_RISK_UNREVIEWED,
    REPEATED_HIGH_SIMILARITY,
    SHARED_IP_CLUSTER,
    SHARED_USER_AGENT_CLUSTER,
    NEAR_TIME_HIGH_RISK_PAIR,
    UNFINISHED_JUDGING
}
