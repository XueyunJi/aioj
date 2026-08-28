package com.aioj.next.contract.auth;

import com.aioj.next.common.security.Role;

import java.util.List;

/**
 * Capability matrix row for one role. Capability keys are stable identifiers;
 * display labels live in frontend i18n.
 */
public record RoleCapabilityResponse(
        Role role,
        List<String> capabilities
) {
    public static final String SOLVE_SUBMIT = "solveSubmit";
    public static final String USE_AI_CHAT = "useAiChat";
    public static final String REVIEW_DRAFTS = "reviewDrafts";
    public static final String EDIT_PROBLEMS = "editProblems";
    public static final String MANAGE_USERS = "manageUsers";
    public static final String DISABLE_ACCOUNTS = "disableAccounts";
}
