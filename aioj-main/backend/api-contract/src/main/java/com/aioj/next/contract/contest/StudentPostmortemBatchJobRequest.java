package com.aioj.next.contract.contest;

import java.util.List;

public record StudentPostmortemBatchJobRequest(
        List<Long> participantIds
) {
}
