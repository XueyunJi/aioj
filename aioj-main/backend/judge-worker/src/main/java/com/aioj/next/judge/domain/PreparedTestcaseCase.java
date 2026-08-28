package com.aioj.next.judge.domain;

import java.nio.file.Path;

public record PreparedTestcaseCase(
        Long id,
        String name,
        Path inputFile,
        Path expectedOutputFile,
        boolean sample,
        String subtaskKey,
        Integer score,
        Integer sortOrder
) {
}
