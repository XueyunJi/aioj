package com.aioj.next.ai.domain;

public interface AiModelConfigResolver {
    AiModelEffectiveConfig effectiveConfig(AiModelScope scope);
}
