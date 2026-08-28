package com.aioj.next.contract.auth;

import com.aioj.next.common.security.Role;
import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

public record RegisterRequest(
        @NotBlank @Size(min = 3, max = 64) String account,
        @NotBlank @Size(min = 8, max = 128) String password,
        @NotBlank @Size(max = 80) String displayName,
        @Email String email,
        Role role
) {
}
