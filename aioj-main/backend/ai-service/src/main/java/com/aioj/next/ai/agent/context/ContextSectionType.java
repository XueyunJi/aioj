package com.aioj.next.ai.agent.context;

public enum ContextSectionType {
    SYSTEM_POLICY,
    ACTIVE_POLICY_SNAPSHOT,
    CONTEST_GUARD_MATCH,
    /** P3-5: one-shot L4 interception notice appended for the safe regeneration run. */
    CONTEST_OUTPUT_GUARD_RETRY,
    /** F1: server-resolved entry metadata (problem/contest/submission identifiers), priority 30. */
    ENTRY_CONTEXT,
    /** F2: user-selected UI content rendered as a delimited data block, priority 35. */
    SELECTED_CONTEXT,
    RECENT_TURNS,
    CONVERSATION_FOCUS,
    BOOTSTRAP_CONTEXT,
    RETRIEVED_EVIDENCE,
    CURRENT_USER_REQUEST
}
