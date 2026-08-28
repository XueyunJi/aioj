package com.aioj.next.contract.ai;

import java.time.Instant;
import java.util.List;

public record AiSubmissionContextResponse(
        Long submissionId,
        Long ownerUserId,
        Long problemId,
        Long contestId,
        Long contestRunId,
        Long contestProblemId,
        String scope,
        boolean contestActive,
        String language,
        String status,
        String judgeMessage,
        String stdoutExcerpt,
        String stderrExcerpt,
        Integer exitStatus,
        Integer runTimeMillis,
        Integer memoryKb,
        Double score,
        Double maxScore,
        boolean codeAllowedToModel,
        String codeText,
        String codeHash,
        List<AiSubmissionCaseContext> caseResults,
        AiProblemContextResponse problemContext,
        Instant submittedAt,
        Instant judgedAt,
        String policyMessage
) {
}
