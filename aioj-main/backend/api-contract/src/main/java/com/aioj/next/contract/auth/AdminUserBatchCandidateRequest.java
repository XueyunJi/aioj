package com.aioj.next.contract.auth;

import com.aioj.next.common.security.Role;
import jakarta.validation.constraints.Size;

import java.util.Set;

public record AdminUserBatchCandidateRequest(
        @Size(max = 64) String account,
        @Size(max = 80) String displayName,
        @Size(max = 160) String email,
        @Size(max = 3) Set<Role> roles,
        Boolean enabled,
        Boolean passwordResetRequired
) {
}
