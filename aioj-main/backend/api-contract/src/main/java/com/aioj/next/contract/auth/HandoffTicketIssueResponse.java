package com.aioj.next.contract.auth;

import java.time.Instant;

public record HandoffTicketIssueResponse(
        String ticket,
        Instant expiresAt
) {
}
