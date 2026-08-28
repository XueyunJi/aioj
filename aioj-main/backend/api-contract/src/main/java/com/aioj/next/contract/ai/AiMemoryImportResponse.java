package com.aioj.next.contract.ai;

import java.util.List;

public record AiMemoryImportResponse(
        int created,
        int updated,
        List<AiMemoryResponse> records
) {
}
