package com.aioj.next.contract.auth;

import jakarta.validation.constraints.Size;

public record AdminUserBatchPasswordRequest(
        String mode,
        @Size(max = 128) String fixedPassword,
        @Size(max = 32) String accountSuffix
) {
}
