package com.aioj.next.contract.contest;

import java.util.List;

public record ContestPlagiarismGraphResponse(
        Long contestId,
        Long contestRunId,
        Summary summary,
        List<Node> nodes,
        List<Edge> edges,
        List<Cluster> clusters
) {
    public record Summary(
            int nodeCount,
            int edgeCount,
            int highRiskEdgeCount,
            int criticalRiskEdgeCount,
            int repeatedPairCount
    ) {
    }

    public record Node(
            Long participantId,
            Long userId,
            String accountSnapshot,
            String displayNameSnapshot,
            int pairCount,
            int highRiskPairCount,
            int criticalRiskPairCount,
            int connectedParticipantCount
    ) {
    }

    public record Edge(
            Long pairId,
            Long jobId,
            Long contestProblemId,
            String problemLabel,
            String problemTitle,
            String language,
            Long leftParticipantId,
            Long rightParticipantId,
            String leftDisplayNameSnapshot,
            String rightDisplayNameSnapshot,
            double similarity,
            int matchedTokens,
            PlagiarismRiskLevel riskLevel,
            PlagiarismReviewStatus reviewStatus,
            String aiSummary
    ) {
    }

    public record Cluster(
            Long leftParticipantId,
            Long rightParticipantId,
            String leftDisplayNameSnapshot,
            String rightDisplayNameSnapshot,
            int pairCount,
            int highRiskPairCount,
            double maxSimilarity,
            List<Long> pairIds
    ) {
    }
}
