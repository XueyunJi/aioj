package com.aioj.next.contract.ai;

import java.util.List;

public record StudentPostmortemWeaknessSuggestion(
        String knowledgeNode,
        String symptom,
        List<String> tags,
        List<String> evidence,
        double confidence
) {
}
