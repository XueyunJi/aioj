package com.aioj.next.auth.domain;

import com.aioj.next.common.security.Role;

import java.util.Set;

public record UserAccount(
        Long id,
        String account,
        String passwordHash,
        String displayName,
        String email,
        boolean enabled,
        Set<Role> roles,
        boolean passwordResetRequired
) {
}
