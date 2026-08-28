package com.aioj.next.contract.problem;

import jakarta.validation.constraints.Pattern;

import java.util.List;

public record TestcaseUploadCompleteRequest(
        List<Integer> uploadedChunks,
        @Pattern(regexp = "(?i)^[0-9a-f]{64}$") String sha256
) {
}
