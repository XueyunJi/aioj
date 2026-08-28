package com.aioj.next.contract.contest;

public record ContestAiPolicyResponse(
        boolean activeContestProblem,
        Long contestId,
        Long contestRunId,
        Long contestProblemId,
        Long problemId,
        String contestTitle,
        String runTitle,
        String problemTitle,
        boolean allowIdeaGuidance,
        boolean allowDebugGuidance,
        boolean allowSubmissionMetadataToAi,
        boolean allowOwnSubmissionCodeToAi,
        boolean allowFullCodeInResponse,
        boolean allowPseudocode,
        Integer maxPseudocodeLines,
        String policyMessage
) {
    public ContestAiPolicyResponse(
            boolean activeContestProblem,
            Long contestId,
            Long contestRunId,
            Long contestProblemId,
            Long problemId,
            String contestTitle,
            String runTitle,
            String problemTitle
    ) {
        this(activeContestProblem, contestId, contestRunId, contestProblemId, problemId, contestTitle, runTitle, problemTitle,
                !activeContestProblem,
                !activeContestProblem,
                !activeContestProblem,
                !activeContestProblem,
                !activeContestProblem,
                !activeContestProblem,
                activeContestProblem ? 0 : null,
                activeContestProblem
                        ? "比赛进行中只能提供思路、复杂度、边界情况和调试方向，不能提供完整可提交代码。"
                        : null);
    }

    public static ContestAiPolicyResponse inactive() {
        return new ContestAiPolicyResponse(false, null, null, null, null, null, null, null,
                true, true, true, true, true, true, null, null);
    }
}
