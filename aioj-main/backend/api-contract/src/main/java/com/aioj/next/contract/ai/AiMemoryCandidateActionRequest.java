package com.aioj.next.contract.ai;

import jakarta.validation.constraints.Size;

public record AiMemoryCandidateActionRequest(
        @Size(max = 32) String category,
        @Size(max = 160) String title,
        @Size(max = 48) String memoryType,
        @Size(max = 20000) String canonicalText,
        @Size(max = 500) String reason
) {
}
