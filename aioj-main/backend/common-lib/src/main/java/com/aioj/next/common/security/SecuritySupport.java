package com.aioj.next.common.security;

import com.aioj.next.common.error.DomainException;
import com.aioj.next.common.error.ErrorCode;
import org.springframework.security.core.context.SecurityContextHolder;

public final class SecuritySupport {
    private SecuritySupport() {
    }

    public static SecurityPrincipal currentPrincipal() {
        var authentication = SecurityContextHolder.getContext().getAuthentication();
        if (authentication == null || !(authentication.getPrincipal() instanceof SecurityPrincipal principal)) {
            throw new DomainException(ErrorCode.UNAUTHORIZED);
        }
        return principal;
    }

    public static Long currentUserId() {
        return currentPrincipal().userId();
    }

    public static boolean hasRole(Role role) {
        return currentPrincipal().roles().contains(role);
    }

    public static boolean hasAnyRole(Role... roles) {
        var currentRoles = currentPrincipal().roles();
        for (Role role : roles) {
            if (currentRoles.contains(role)) {
                return true;
            }
        }
        return false;
    }
}
