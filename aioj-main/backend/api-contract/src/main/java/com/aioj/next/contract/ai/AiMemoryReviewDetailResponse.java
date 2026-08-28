package com.aioj.next.contract.ai;

import java.util.List;

public record AiMemoryReviewDetailResponse(
        AiMemoryReviewListItemResponse candidate,
        List<AiMemoryReviewEvidenceResponse> evidence,
        List<AiMemoryReviewRelatedMemoryResponse> relatedMemories,
        List<AiMemoryReviewRelatedProfileResponse> relatedProfiles,
        List<String> suggestedActions
) {
}
