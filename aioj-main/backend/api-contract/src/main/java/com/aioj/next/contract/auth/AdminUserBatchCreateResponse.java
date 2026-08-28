package com.aioj.next.contract.auth;

import java.util.List;

public record AdminUserBatchCreateResponse(
        int requested,
        int created,
        int skipped,
        List<AdminUserBatchResultItem> results
) {
}
