package com.aioj.next.contract.contest;

import java.util.List;

public record ContestScoreboardTimelineResponse(
        ContestScoreboardTimelineStatus status,
        List<ContestScoreboardTimelineTickResponse> ticks,
        Long jobId,
        Integer progressCurrent,
        Integer progressTotal,
        String message
) {
}
