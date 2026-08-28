package com.aioj.next.contract.contest;

import java.util.List;

public record ContestInvitationBatchResponse(
        int requested,
        int succeeded,
        int failed,
        List<ContestInvitationBatchResultItem> results
) {
}
