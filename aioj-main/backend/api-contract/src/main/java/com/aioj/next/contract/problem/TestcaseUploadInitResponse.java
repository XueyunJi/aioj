package com.aioj.next.contract.problem;

import java.time.Instant;
import java.util.List;

public record TestcaseUploadInitResponse(
        String uploadId,
        TestcasePackageStatus status,
        Long packageId,
        List<Integer> uploadedChunks,
        Integer chunkSizeBytes,
        Integer totalChunks,
        Instant expiresAt,
        String message
) {
}
