package com.aioj.next.contract.ai;

import java.time.Instant;

public record AdminContestAiMessageResponse(
        Long id,
        String role,
        String content,
        String status,
        String model,
        Instant createdAt
) {
}
