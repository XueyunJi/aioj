package com.aioj.next.contract.ai;

public record AiMemoryMergeMaintenanceRequest(
        Long targetUserId,
        String category,
        Integer limit
) {
}
