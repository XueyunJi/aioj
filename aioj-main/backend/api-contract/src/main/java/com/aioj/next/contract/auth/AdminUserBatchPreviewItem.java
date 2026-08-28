package com.aioj.next.contract.auth;

import com.aioj.next.common.security.Role;

import java.util.List;
import java.util.Set;

public record AdminUserBatchPreviewItem(
        int rowNumber,
        String account,
        String displayName,
        String email,
        Set<Role> roles,
        boolean enabled,
        boolean passwordResetRequired,
        boolean valid,
        boolean duplicateInBatch,
        boolean duplicateExisting,
        List<String> errors,
        List<String> errorCodes
) {
    /** Stable machine-readable codes for {@link #errors}, keeping frontend logic independent of message wording. */
    public static final String CODE_ACCOUNT_EMPTY = "ACCOUNT_EMPTY";
    public static final String CODE_ACCOUNT_INVALID = "ACCOUNT_INVALID";
    public static final String CODE_DISPLAY_NAME_EMPTY = "DISPLAY_NAME_EMPTY";
    public static final String CODE_DISPLAY_NAME_TOO_LONG = "DISPLAY_NAME_TOO_LONG";
    public static final String CODE_EMAIL_INVALID = "EMAIL_INVALID";
    public static final String CODE_ROLES_INVALID = "ROLES_INVALID";
    public static final String CODE_ACCOUNT_DUPLICATE_IN_BATCH = "ACCOUNT_DUPLICATE_IN_BATCH";
    public static final String CODE_ACCOUNT_EXISTS = "ACCOUNT_EXISTS";
}
