package com.aioj.next.contract.auth;

import com.aioj.next.common.security.Role;

import java.util.Set;

public record UserProfileResponse(
        Long userId,
        String account,
        String displayName,
        String email,
        Set<Role> roles,
        boolean passwordResetRequired
) {
}
