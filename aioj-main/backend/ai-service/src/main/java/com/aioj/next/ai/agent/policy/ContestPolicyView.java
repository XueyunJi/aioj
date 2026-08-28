package com.aioj.next.ai.agent.policy;

import com.aioj.next.contract.contest.ContestAiPolicyMode;
import com.aioj.next.contract.contest.RunningContestProblemOccurrence;
import com.aioj.next.contract.contest.RunningContestProblemStatement;
import com.aioj.next.contract.problem.ProblemVisibility;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Compact, immutable contest policy projection threaded from TurnCoordinator
 * through AgentRuntime into every ToolExecutionContext (P3-3, C5 wiring).
 * Tool-internal ABAC reads it; the model can never supply or alter it.
 *
 * <p>{@link #contestProblems()} holds the deduplicated running-contest problem
 * set of the turn's policy snapshot (all modes, including DISABLED, so the
 * search tool covers the full snapshot space); empty for non-participants.</p>
 */
public record ContestPolicyView(
        ParticipantStatus participantStatus,
        Map<Long, ContestProblemPolicy> contestProblems
) {
    public ContestPolicyView {
        // Keep snapshot order stable (search topK truncation must be deterministic).
        contestProblems = contestProblems == null
                ? Map.of()
                : java.util.Collections.unmodifiableMap(new LinkedHashMap<>(contestProblems));
    }

    /** One running-contest problem the tool layer may have to rule on. */
    public record ContestProblemPolicy(
            Long problemId,
            ProblemVisibility visibility,
            ContestAiPolicyMode aiPolicyMode,
            String aiPolicyNotes,
            String statement,
            List<RunningContestProblemOccurrence> occurrences
    ) {
        public ContestProblemPolicy {
            occurrences = occurrences == null ? List.of() : List.copyOf(occurrences);
        }

        /** First occurrence coordinates, for audit attribution. */
        public RunningContestProblemOccurrence firstOccurrence() {
            return occurrences.isEmpty() ? null : occurrences.get(0);
        }
    }

    public boolean isParticipant() {
        return participantStatus != null && participantStatus != ParticipantStatus.NON_PARTICIPANT;
    }

    public ContestProblemPolicy problem(long problemId) {
        return contestProblems.get(problemId);
    }

    /**
     * Problems the guard actually constrains (DISABLED-mode excluded), mirroring
     * {@link PolicySnapshotService.PolicySnapshot#constrainedProblems()}. Feeds the
     * L3 fingerprint candidate set (P3-4, design doc §5.3).
     */
    public List<ContestProblemPolicy> constrainedProblems() {
        return contestProblems.values().stream()
                .filter(problem -> problem.aiPolicyMode() != ContestAiPolicyMode.DISABLED)
                .toList();
    }

    public static ContestPolicyView nonParticipant() {
        return new ContestPolicyView(ParticipantStatus.NON_PARTICIPANT, Map.of());
    }

    /** Builds the view from the turn's policy snapshot; a null/baseline snapshot yields a non-participant view. */
    public static ContestPolicyView from(PolicySnapshotService.PolicySnapshot snapshot) {
        if (snapshot == null) {
            return nonParticipant();
        }
        return from(snapshot.participantStatus(), snapshot.contestProblems());
    }

    /**
     * P3-6: builds the view from a recheck's fresh status + statements — no snapshot
     * entity is involved for the mid-generation state, only the same projection rules.
     */
    public static ContestPolicyView from(ParticipantStatus participantStatus,
                                         List<RunningContestProblemStatement> statements) {
        if (participantStatus == null || participantStatus == ParticipantStatus.NON_PARTICIPANT) {
            return nonParticipant();
        }
        List<RunningContestProblemStatement> safe = statements == null ? List.of() : statements;
        Map<Long, ContestProblemPolicy> problems = new LinkedHashMap<>();
        for (RunningContestProblemStatement statement : safe) {
            if (statement == null || statement.problemId() == null) {
                continue;
            }
            problems.putIfAbsent(statement.problemId(), new ContestProblemPolicy(
                    statement.problemId(),
                    statement.visibility(),
                    statement.aiPolicyMode(),
                    statement.aiPolicyNotes(),
                    statement.statement(),
                    statement.occurrences()));
        }
        return new ContestPolicyView(participantStatus, problems);
    }
}
