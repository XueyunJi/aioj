package com.aioj.next.ai.agent.digest;

import java.util.List;

/**
 * Everything the digest pipeline needs to describe one completed turn. Carried as a
 * record so the TurnCoordinator integration stays a single call (design doc §6.3).
 */
public record TurnDigestInput(
        String turnId,
        String conversationId,
        Long userId,
        String userMessageId,
        String assistantMessageId,
        String userContent,
        String assistantContent,
        String assistantModel,
        Long explicitProblemId,
        List<Long> referencedProblemIds,
        String selectionText,
        String selectionSourceMessageId,
        Long submissionId,
        String entryPoint
) {
}
