package com.aioj.next.contract.contest;

/**
 * One (contest, run, contest problem) coordinate a merged running-contest
 * problem statement belongs to. A problem shared by several running runs
 * produces a single statement entry carrying every occurrence.
 */
public record RunningContestProblemOccurrence(
        Long contestId,
        Long contestRunId,
        Long contestProblemId
) {
}
