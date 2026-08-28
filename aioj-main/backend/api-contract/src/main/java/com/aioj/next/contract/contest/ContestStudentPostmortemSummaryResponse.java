package com.aioj.next.contract.contest;

import java.math.BigDecimal;
import java.time.Instant;

public record ContestStudentPostmortemSummaryResponse(
        Long contestParticipantId,
        Long userId,
        String accountSnapshot,
        String displayNameSnapshot,
        String emailSnapshot,
        Long reportId,
        ContestPostmortemReportStatus status,
        ContestPostmortemAiStatus aiStatus,
        Integer submissionCount,
        Integer acceptedCount,
        BigDecimal totalScore,
        BigDecimal maxScore,
        Integer weaknessCandidateCount,
        Integer pendingWeaknessCandidateCount,
        Instant lastGeneratedAt
) {
}
