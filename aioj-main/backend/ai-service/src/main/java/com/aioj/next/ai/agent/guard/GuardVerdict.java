package com.aioj.next.ai.agent.guard;

import com.aioj.next.ai.agent.policy.GuardDecision;
import com.aioj.next.ai.agent.policy.GuardDecisionRecorder;

import java.util.List;

/** Outcome of one fingerprint matching pass (design doc §5.3). */
public record GuardVerdict(GuardDecision decision,
                           List<GuardDecisionRecorder.MatchedProblemRef> matchedProblems,
                           double maxScore,
                           String reasonCode) {

    public static final String REASON_MATCH = "fingerprint_match";
    public static final String REASON_NO_MATCH = "no_match";

    public boolean hasMatches() {
        return matchedProblems != null && !matchedProblems.isEmpty();
    }

    public static GuardVerdict pass() {
        return new GuardVerdict(GuardDecision.PASS, List.of(), 0.0, REASON_NO_MATCH);
    }

    public static GuardVerdict constrain(List<GuardDecisionRecorder.MatchedProblemRef> matched, double maxScore) {
        return new GuardVerdict(GuardDecision.CONSTRAIN, matched, maxScore, REASON_MATCH);
    }

    /**
     * P3-4: union of two layers' hits within one turn, exposed to L4 (§5.4).
     * Refs are value-equal records, so identical problem-occurrence refs collapse;
     * the looser verdict (fewer matches) yields to the richer one.
     */
    public GuardVerdict mergedWith(GuardVerdict other) {
        if (other == null || !other.hasMatches()) {
            return this;
        }
        if (!hasMatches()) {
            return other;
        }
        java.util.LinkedHashSet<GuardDecisionRecorder.MatchedProblemRef> refs =
                new java.util.LinkedHashSet<>(matchedProblems);
        refs.addAll(other.matchedProblems());
        return GuardVerdict.constrain(List.copyOf(refs), Math.max(maxScore, other.maxScore));
    }
}
