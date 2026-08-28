package com.aioj.next.ai.domain.problem;

import java.util.List;

public record RepairTask(
        String category,
        String repairScope,
        double confidence,
        List<String> allowedFields,
        List<String> forbiddenFields,
        List<String> evidence
) {
    public RepairTask {
        allowedFields = allowedFields == null ? List.of() : List.copyOf(allowedFields);
        forbiddenFields = forbiddenFields == null ? List.of() : List.copyOf(forbiddenFields);
        evidence = evidence == null ? List.of() : List.copyOf(evidence);
    }
}
