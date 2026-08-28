package com.aioj.next.contract.ai;

import jakarta.validation.constraints.Size;

public record AiConversationUpdateRequest(
        @Size(max = 160) String title,
        @Size(max = 32) String mode,
        Long recentProblemId
) {
}
