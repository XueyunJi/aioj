package com.aioj.next.contract.contest;

import com.aioj.next.contract.problem.ProblemVisibility;

/**
 * Statement excerpt of a problem currently used by a RUNNING contest run.
 * Consumed by ai-service to refuse AI questions that reproduce running contest
 * problem content outside the legitimate contest assistance context. The contest
 * coordinates let ai-service attribute blocked turns to the affected contest, and
 * visibility selects the judge rule (private: refuse discussion; public: refuse
 * complete solution code only).
 */
public record RunningContestProblemStatement(
        Long problemId,
        String statement,
        Long contestId,
        Long contestRunId,
        Long contestProblemId,
        ProblemVisibility visibility,
        /**
         * Merged AI policy mode across every occurrence (strictest wins:
         * STRICT > DEFAULT > DISABLED). Sourced from run policy snapshots.
         */
        ContestAiPolicyMode aiPolicyMode,
        /**
         * Run AI policy notes of every occurrence, concatenated with the
         * source run title as prefix. Null when no occurrence carries notes.
         */
        String aiPolicyNotes,
        /** Every (contest, run, contest problem) coordinate this problem is used by. */
        java.util.List<RunningContestProblemOccurrence> occurrences
) {
}
