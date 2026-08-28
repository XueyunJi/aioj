package com.aioj.next.contract.contest;

import java.time.Instant;

public record ContestAnnouncementResponse(
        Long id,
        Long contestId,
        Long contestRunId,
        Long authorUserId,
        String title,
        String content,
        Boolean pinned,
        ContestAnnouncementStatus status,
        Instant publishedAt,
        Instant archivedAt,
        Instant createdAt,
        Instant updatedAt
) {
}
