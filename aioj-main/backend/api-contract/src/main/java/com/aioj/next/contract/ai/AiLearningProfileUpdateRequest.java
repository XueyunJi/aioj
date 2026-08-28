package com.aioj.next.contract.ai;

public record AiLearningProfileUpdateRequest(
        String state,
        String label,
        String note
) {
}
