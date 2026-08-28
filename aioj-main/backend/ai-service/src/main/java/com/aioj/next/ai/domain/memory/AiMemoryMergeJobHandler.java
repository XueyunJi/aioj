package com.aioj.next.ai.domain.memory;

import com.aioj.next.ai.persistence.entity.AiMemoryJobEntity;
import org.springframework.stereotype.Component;

@Component
public class AiMemoryMergeJobHandler implements AiMemoryJobHandler {
    private final AiMemoryMergeService mergeService;

    public AiMemoryMergeJobHandler(AiMemoryMergeService mergeService) {
        this.mergeService = mergeService;
    }

    @Override
    public String jobType() {
        return AiMemoryJobTypes.JOB_AI_MEMORY_MERGE_REVIEW;
    }

    @Override
    public void handle(AiMemoryJobEntity job) {
        mergeService.handleJob(job);
    }
}
