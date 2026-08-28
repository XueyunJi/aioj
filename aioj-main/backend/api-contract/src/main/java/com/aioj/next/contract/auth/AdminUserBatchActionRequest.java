package com.aioj.next.contract.auth;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotEmpty;

import java.util.Set;

public record AdminUserBatchActionRequest(
        @NotEmpty Set<Long> userIds,
        AdminUserBatchAction action,
        @Valid AdminUserBatchPasswordRequest password,
        Boolean passwordResetRequired
) {
}
