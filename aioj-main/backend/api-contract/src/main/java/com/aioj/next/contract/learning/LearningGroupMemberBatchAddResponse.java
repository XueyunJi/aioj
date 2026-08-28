package com.aioj.next.contract.learning;

import java.util.List;

public record LearningGroupMemberBatchAddResponse(
        int requested,
        int succeeded,
        int failed,
        List<LearningGroupMemberBatchAddResultItem> results
) {
}
