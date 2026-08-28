package com.aioj.next.contract.ai;

import jakarta.validation.constraints.Size;

public record AiConversationCreateRequest(
        Long problemId,
        @Size(max = 160) String title,
        @Size(max = 32) String source,
        @Size(max = 32) String sourceRefType,
        @Size(max = 64) String sourceRefId,
        @Size(max = 32) String mode
) {
}
