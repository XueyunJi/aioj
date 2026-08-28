package com.aioj.next.contract.problem;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Positive;
import jakarta.validation.constraints.Size;

public record TestcaseUploadInitRequest(
        @NotBlank @Size(max = 255) String fileName,
        @Positive long fileSizeBytes,
        @NotBlank @Pattern(regexp = "(?i)^[0-9a-f]{64}$") String sha256,
        @Positive int chunkSizeBytes,
        @Positive int totalChunks
) {
}
