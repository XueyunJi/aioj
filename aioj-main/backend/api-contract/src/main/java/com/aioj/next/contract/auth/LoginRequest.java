package com.aioj.next.contract.auth;

import jakarta.validation.constraints.NotBlank;

public record LoginRequest(@NotBlank String account, @NotBlank String password) {
}
