package com.aioj.next.contract.ai;

import java.util.List;

public record AiMemoryDebugResponse(
        QueryContext queryContext,
        List<RecallItem> selected,
        List<RecallItem> rejected
) {
    public record QueryContext(
            String query,
            String intent,
            String mode,
            Long problemId,
            List<String> problemTags
    ) {
    }

    public record RecallItem(
            Long id,
            Long claimId,
            String category,
            String memoryType,
            String title,
            String content,
            double score,
            boolean selected,
            List<String> reasons
    ) {
    }
}
