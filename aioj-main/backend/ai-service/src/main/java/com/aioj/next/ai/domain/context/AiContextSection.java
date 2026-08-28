package com.aioj.next.ai.domain.context;

import java.util.Map;

public record AiContextSection(
        String id,
        String type,
        String title,
        int priority,
        String source,
        String sensitivity,
        int estimatedTokens,
        boolean required,
        String contentPreview,
        Map<String, Object> metadata
) {
}
