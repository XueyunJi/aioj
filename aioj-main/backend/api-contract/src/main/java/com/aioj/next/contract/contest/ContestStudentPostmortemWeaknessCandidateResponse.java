package com.aioj.next.contract.contest;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;

public record ContestStudentPostmortemWeaknessCandidateResponse(
        Long id,
        Long reportId,
        Long contestId,
        Long contestRunId,
        Long contestParticipantId,
        Long userId,
        ContestStudentPostmortemWeaknessCandidateStatus status,
        String knowledgeNode,
        String symptom,
        List<String> tags,
        List<String> evidence,
        BigDecimal confidence,
        Long memoryId,
        Long weaknessId,
        Instant createdAt,
        Instant updatedAt,
        Instant decidedAt
) {
}
