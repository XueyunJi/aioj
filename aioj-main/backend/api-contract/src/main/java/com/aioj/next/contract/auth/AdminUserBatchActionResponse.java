package com.aioj.next.contract.auth;

import java.util.List;

public record AdminUserBatchActionResponse(
        int requested,
        int succeeded,
        int failed,
        List<AdminUserBatchActionResultItem> results
) {
}
