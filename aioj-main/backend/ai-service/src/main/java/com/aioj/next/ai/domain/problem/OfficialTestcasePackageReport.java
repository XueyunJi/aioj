package com.aioj.next.ai.domain.problem;

import java.util.List;

public record OfficialTestcasePackageReport(
        String status,
        String errorCode,
        String errorMessage,
        String packageFileId,
        String packageFileName,
        Long packageFileSizeBytes,
        String packageSha256,
        Integer caseCount,
        Integer generatedInputCount,
        Integer generatedOutputCount,
        Long totalInputBytes,
        Long totalOutputBytes,
        Long totalBytes,
        Long largestCaseBytes,
        String manifestJson,
        String scanRoot,
        List<OfficialTestcaseCaseReport> cases
) {
    public OfficialTestcasePackageReport {
        cases = cases == null ? List.of() : List.copyOf(cases);
    }

    public boolean passed() {
        return "PASSED".equals(status);
    }
}
