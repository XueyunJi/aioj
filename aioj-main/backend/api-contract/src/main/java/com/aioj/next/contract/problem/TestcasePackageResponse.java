package com.aioj.next.contract.problem;

import java.time.Instant;
import java.util.List;

public record TestcasePackageResponse(
        Long id,
        Long problemId,
        String version,
        String fileName,
        Long fileSizeBytes,
        String sha256,
        TestcasePackageStatus status,
        boolean active,
        Integer caseCount,
        Integer sampleCount,
        String storageProvider,
        Instant createdAt,
        Instant activatedAt,
        Instant archivedAt,
        Instant deletedAt,
        Long deletedBy,
        String errorMessage,
        TestcasePackageCheckerResponse checker,
        List<TestcasePackageSubtaskResponse> subtasks,
        List<TestcasePackageCaseResponse> cases
) {
}
