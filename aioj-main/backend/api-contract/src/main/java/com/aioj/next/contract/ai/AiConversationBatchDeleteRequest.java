package com.aioj.next.contract.ai;

import jakarta.validation.constraints.NotEmpty;

import java.util.List;

public record AiConversationBatchDeleteRequest(
        @NotEmpty List<String> conversationIds
) {
}
