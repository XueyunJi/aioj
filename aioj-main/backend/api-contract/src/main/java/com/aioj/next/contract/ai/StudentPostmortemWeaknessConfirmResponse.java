package com.aioj.next.contract.ai;

public record StudentPostmortemWeaknessConfirmResponse(
        Long memoryId,
        Long weaknessId,
        Long mergeCandidateId,
        Long mergeJobId,
        String mergeStatus
) {
    public StudentPostmortemWeaknessConfirmResponse(Long memoryId, Long weaknessId) {
        this(memoryId, weaknessId, null, null, null);
    }
}
