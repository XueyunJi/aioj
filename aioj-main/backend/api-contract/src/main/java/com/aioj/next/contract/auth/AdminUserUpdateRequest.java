package com.aioj.next.contract.auth;

import com.aioj.next.common.security.Role;
import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.Size;

import java.util.Set;

public record AdminUserUpdateRequest(
        @NotBlank @Size(max = 80) String displayName,
        @Email @Size(max = 160) String email,
        @NotEmpty Set<Role> roles,
        Boolean enabled,
        Boolean passwordResetRequired
) {
}
