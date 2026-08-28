package com.aioj.next.ai.domain.memory;

import com.aioj.next.ai.persistence.entity.AiMemoryJobEntity;

public interface AiMemoryJobHandler {
    String jobType();

    void handle(AiMemoryJobEntity job);
}
