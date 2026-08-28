package com.aioj.next.contract.auth;

import java.util.List;

public record AdminUserBatchPreviewResponse(
        int total,
        int valid,
        int invalid,
        List<AdminUserBatchPreviewItem> items
) {
}
