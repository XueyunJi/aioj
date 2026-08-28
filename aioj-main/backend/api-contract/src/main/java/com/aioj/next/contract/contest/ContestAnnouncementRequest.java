package com.aioj.next.contract.contest;

public record ContestAnnouncementRequest(
        String title,
        String content,
        Boolean pinned
) {
}
