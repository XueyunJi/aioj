package com.aioj.next.contract.auth;

import com.aioj.next.common.security.Role;

import java.time.Instant;
import java.util.Set;

public record TokenResponse(
        String accessToken,
        String refreshToken,
        String tokenType,
        Instant expiresAt,
        Long userId,
        String account,
        String displayName,
        Set<Role> roles,
        boolean passwordResetRequired
) {
}
