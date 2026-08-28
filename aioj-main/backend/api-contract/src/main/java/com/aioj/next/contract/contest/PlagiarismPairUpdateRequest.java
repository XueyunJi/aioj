package com.aioj.next.contract.contest;

import jakarta.validation.constraints.Size;

public record PlagiarismPairUpdateRequest(
        PlagiarismReviewStatus reviewStatus,
        @Size(max = 500) String teacherNote
) {
}
