package com.aioj.next.contract.contest;

public record ContestInvitationBatchResultItem(
        Long userId,
        String account,
        String displayName,
        ContestInvitationBatchItemStatus status,
        String message
) {
}
