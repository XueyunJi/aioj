package com.aioj.next.contract.auth;

import com.aioj.next.common.security.Role;

public record RoleResponse(Role role, String label) {
}
