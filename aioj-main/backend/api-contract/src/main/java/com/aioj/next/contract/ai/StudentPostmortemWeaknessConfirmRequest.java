package com.aioj.next.contract.ai;

import java.util.List;

public record StudentPostmortemWeaknessConfirmRequest(
        Long userId,
        Long contestId,
        Long contestRunId,
        Long contestParticipantId,
        Long reportId,
        Long candidateId,
        String knowledgeNode,
        String symptom,
        List<String> tags,
        List<String> evidence,
        double confidence
) {
}
