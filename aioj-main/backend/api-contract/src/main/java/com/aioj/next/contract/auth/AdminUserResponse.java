package com.aioj.next.contract.auth;

import com.aioj.next.common.security.Role;

import java.time.Instant;
import java.util.Set;

public record AdminUserResponse(
        Long userId,
        String account,
        String displayName,
        String email,
        boolean enabled,
        Set<Role> roles,
        boolean passwordResetRequired,
        Instant createdAt,
        Instant updatedAt,
        Instant archivedAt,
        Instant deletedAt,
        Long deletedBy
) {
}
