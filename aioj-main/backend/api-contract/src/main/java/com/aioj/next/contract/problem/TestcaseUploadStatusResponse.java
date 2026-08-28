package com.aioj.next.contract.problem;

import java.util.List;

public record TestcaseUploadStatusResponse(
        String uploadId,
        TestcasePackageStatus status,
        List<Integer> uploadedChunks,
        Integer totalChunks,
        double progress,
        Long packageId,
        String errorMessage
) {
}
