package com.aioj.next.contract.contest;

import java.time.Instant;
import java.util.List;

public record ContestStudentPostmortemReportResponse(
        Long id,
        Long contestId,
        Long contestRunId,
        Long contestParticipantId,
        Long userId,
        ContestPostmortemReportStatus status,
        ContestPostmortemAiStatus aiStatus,
        Long generatedBy,
        String statisticsJson,
        String aiMarkdown,
        String practiceSuggestionsJson,
        String aiProvider,
        String aiModel,
        Long promptTokens,
        Long completionTokens,
        String errorMessage,
        Instant createdAt,
        Instant updatedAt,
        Instant completedAt,
        List<ContestStudentPostmortemWeaknessCandidateResponse> weaknessCandidates
) {
}
