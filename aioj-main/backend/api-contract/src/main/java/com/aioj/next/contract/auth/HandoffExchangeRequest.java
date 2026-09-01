package com.aioj.next.contract.auth;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

public record HandoffExchangeRequest(
        @NotBlank @Size(max = 128) String ticket
) {
}
