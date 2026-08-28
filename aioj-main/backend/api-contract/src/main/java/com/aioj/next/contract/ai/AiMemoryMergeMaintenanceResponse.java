package com.aioj.next.contract.ai;

import java.util.List;

public record AiMemoryMergeMaintenanceResponse(
        Long targetUserId,
        int scannedMemories,
        int relatedGroups,
        int queuedJobs,
        List<Long> candidateIds,
        List<Long> jobIds
) {
}
