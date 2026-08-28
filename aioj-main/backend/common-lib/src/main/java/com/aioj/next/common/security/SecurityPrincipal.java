package com.aioj.next.common.security;

import java.util.Set;

public record SecurityPrincipal(Long userId, String account, Set<Role> roles, boolean passwordResetRequired) {
    public SecurityPrincipal(Long userId, String account, Set<Role> roles) {
        this(userId, account, roles, false);
    }

    public boolean hasRole(Role role) {
        return roles != null && roles.contains(role);
    }
}
