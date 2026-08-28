package com.aioj.next.contract.ai;

import java.util.List;

public record StudentPostmortemPracticeSuggestion(
        String title,
        String description,
        List<String> tags
) {
}
