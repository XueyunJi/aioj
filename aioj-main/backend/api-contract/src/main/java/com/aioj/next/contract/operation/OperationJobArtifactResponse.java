package com.aioj.next.contract.operation;

import java.time.Instant;

public record OperationJobArtifactResponse(
        Long id,
        Long jobId,
        String fileName,
        String contentType,
        long byteSize,
        String sha256,
        Instant expiresAt,
        Instant createdAt
) {
}
