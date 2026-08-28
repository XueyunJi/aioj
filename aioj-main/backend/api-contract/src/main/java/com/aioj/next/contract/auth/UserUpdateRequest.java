package com.aioj.next.contract.auth;

import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

public record UserUpdateRequest(
        @NotBlank @Size(max = 80) String displayName,
        @Email @Size(max = 160) String email
) {
}
