package com.aioj.next.contract.ai;

import com.aioj.next.contract.problem.ProblemVisibility;

public record ProblemTitleInfo(
        Long id,
        String title,
        ProblemVisibility visibility
) {
}
