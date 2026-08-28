package com.aioj.next.contract.learning;

public record LearningGroupMemberBatchAddResultItem(
        Long userId,
        String account,
        String displayName,
        LearningGroupMemberRole role,
        LearningGroupMemberBatchAddStatus status,
        String message,
        LearningGroupMemberResponse member
) {
}
