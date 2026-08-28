package com.aioj.next.contract.contest;

import java.time.Instant;
import java.util.Map;

public record FairnessAlertResponse(
        Long id,
        Long contestId,
        Long contestRunId,
        FairnessAlertType type,
        FairnessAlertSeverity severity,
        FairnessAlertStatus status,
        Long primaryParticipantId,
        Long secondaryParticipantId,
        Long plagiarismPairId,
        String primaryDisplayName,
        String secondaryDisplayName,
        String title,
        String summary,
        Map<String, Object> evidence,
        String teacherNote,
        Long reviewedBy,
        Instant reviewedAt,
        Instant createdAt,
        Instant updatedAt
) {
}
