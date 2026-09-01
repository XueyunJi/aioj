package com.aioj.next.contract.auth;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

public record HandoffTicketIssueRequest(
        @NotBlank @Size(max = 32) String audience,
        @NotBlank @Size(max = 255) String nextPath
) {
}
