package com.aioj.next.contract.problem;

import java.math.BigDecimal;

public record TestcasePackageSubtaskResponse(
        Long id,
        String key,
        String title,
        BigDecimal score,
        Integer sortOrder
) {
}
