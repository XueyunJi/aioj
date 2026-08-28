package com.aioj.next.contract.contest;

/**
 * One RUNNING contest run in which a user is an ACTIVE participant. Consumed by
 * ai-service to decide whether the enhanced participant leak guard applies.
 */
public record RunningContestParticipation(
        Long contestId,
        Long contestRunId,
        /** Run window start; lets consumers mark guard decisions inside the grace window. */
        java.time.Instant startAt,
        /** Run window end; the guard stays effective until endAt plus the configured grace. */
        java.time.Instant endAt
) {
}
