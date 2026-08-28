package com.aioj.next.contract.ai;

import java.util.List;

public record StudentPostmortemAnalysisRequest(
        Long actorUserId,
        Long targetUserId,
        Long contestId,
        Long contestRunId,
        Long contestParticipantId,
        String contestTitle,
        String runTitle,
        String mode,
        String statisticsJson,
        String summaryText,
        List<StudentPostmortemCodeReference> representativeCodeReferences
) {
}
