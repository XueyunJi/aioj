package com.aioj.next.contract.contest;

public record ContestSubmissionCodeResponse(
        Long auditLogId,
        ContestSubmissionResponse submission
) {
}
