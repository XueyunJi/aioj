package com.aioj.next.problem.domain;

public record SubmissionRequestMetadata(
        String remoteAddress,
        String forwardedFor,
        String userAgent
) {
}
