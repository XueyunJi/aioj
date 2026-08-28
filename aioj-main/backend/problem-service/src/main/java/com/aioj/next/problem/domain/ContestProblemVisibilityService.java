package com.aioj.next.problem.domain;

import com.aioj.next.common.security.Role;
import com.aioj.next.contract.contest.ContestAiPolicyMode;
import com.aioj.next.contract.contest.ContestParticipantStatus;
import com.aioj.next.contract.contest.ContestRunStatus;
import com.aioj.next.contract.contest.ContestRunWindow;
import com.aioj.next.contract.contest.RunningContestParticipation;
import com.aioj.next.contract.contest.RunningContestProblemOccurrence;
import com.aioj.next.contract.contest.RunningContestProblemStatement;
import com.aioj.next.contract.problem.ProblemVisibility;
import com.aioj.next.problem.config.ContestProperties;
import com.aioj.next.problem.persistence.entity.ContestParticipantEntity;
import com.aioj.next.problem.persistence.entity.ContestProblemEntity;
import com.aioj.next.problem.persistence.entity.ContestRunEntity;
import com.aioj.next.problem.persistence.entity.ContestRunProblemSnapshotEntity;
import com.aioj.next.problem.persistence.entity.ProblemEntity;
import com.aioj.next.problem.persistence.mapper.ContestParticipantMapper;
import com.aioj.next.problem.persistence.mapper.ContestProblemMapper;
import com.aioj.next.problem.persistence.mapper.ContestRunMapper;
import com.aioj.next.problem.persistence.mapper.ContestRunProblemSnapshotMapper;
import com.aioj.next.problem.persistence.mapper.ProblemMapper;
import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;

import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

/**
 * Central rules for problem visibility masking. A contest problem is "hidden"
 * for students when the problem is PRIVATE, the owning run has ended, and the
 * problem has not been published since. All read-side masking (snapshots,
 * scoreboards, submissions, clarifications, postmortems) must go through this
 * service so the backend, not the frontend, enforces the mask.
 */
@Service
public class ContestProblemVisibilityService {
    private final ContestRunMapper runMapper;
    private final ContestProblemMapper contestProblemMapper;
    private final ContestParticipantMapper participantMapper;
    private final ProblemMapper problemMapper;
    private final ContestRunProblemSnapshotMapper runProblemSnapshotMapper;
    private final ContestProperties contestProperties;

    public ContestProblemVisibilityService(ContestRunMapper runMapper, ContestProblemMapper contestProblemMapper,
                                           ContestParticipantMapper participantMapper, ProblemMapper problemMapper,
                                           ContestRunProblemSnapshotMapper runProblemSnapshotMapper,
                                           ContestProperties contestProperties) {
        this.runMapper = runMapper;
        this.contestProblemMapper = contestProblemMapper;
        this.participantMapper = participantMapper;
        this.problemMapper = problemMapper;
        this.runProblemSnapshotMapper = runProblemSnapshotMapper;
        this.contestProperties = contestProperties;
    }

    /** True when the caller holds a staff role; safe for anonymous callers. */
    public boolean isStaffViewer() {
        var authentication = SecurityContextHolder.getContext().getAuthentication();
        if (authentication == null || authentication.getAuthorities() == null) {
            return false;
        }
        String teacher = "ROLE_" + Role.TEACHER.name();
        String admin = "ROLE_" + Role.ADMIN.name();
        return authentication.getAuthorities().stream()
                .anyMatch(authority -> teacher.equals(authority.getAuthority()) || admin.equals(authority.getAuthority()));
    }

    /** True when the problem row is PRIVATE (null-safe: legacy rows count as PUBLIC). */
    public boolean isPrivate(ProblemEntity problem) {
        return problem != null && problem.getVisibility() == ProblemVisibility.PRIVATE;
    }

    /** True when the problem id refers to a PRIVATE problem (missing rows count as not private). */
    public boolean isProblemPrivate(Long problemId) {
        if (problemId == null) {
            return false;
        }
        ProblemEntity problem = problemMapper.selectOne(new LambdaQueryWrapper<ProblemEntity>()
                .eq(ProblemEntity::getId, problemId));
        return isPrivate(problem);
    }

    /** Problem ids hidden for students in the given run (empty while the run is still active). */
    public Set<Long> hiddenProblemIdsForRun(ContestRunEntity run, Instant now) {
        if (run == null || run.getEndAt() == null || now.isBefore(run.getEndAt())) {
            return Set.of();
        }
        return privateProblemIdsOfContest(run.getContestId());
    }

    /** Contest-problem ids hidden for students in the given run. */
    public Set<Long> hiddenContestProblemIdsForRun(ContestRunEntity run, Instant now) {
        Set<Long> hiddenProblemIds = hiddenProblemIdsForRun(run, now);
        if (hiddenProblemIds.isEmpty()) {
            return Set.of();
        }
        Set<Long> hidden = new HashSet<>();
        for (ContestProblemEntity contestProblem : contestProblems(run.getContestId())) {
            if (hiddenProblemIds.contains(contestProblem.getProblemId())) {
                hidden.add(contestProblem.getId());
            }
        }
        return hidden;
    }

    /**
     * Hidden (runId -> contestProblemIds) pairs for submission-list filtering.
     * contestId/runId may be null to widen the scope.
     */
    public Map<Long, Set<Long>> hiddenRunProblemPairs(Long contestId, Long runId, Instant now) {
        LambdaQueryWrapper<ContestRunEntity> wrapper = new LambdaQueryWrapper<ContestRunEntity>()
                .eq(contestId != null, ContestRunEntity::getContestId, contestId)
                .eq(runId != null, ContestRunEntity::getId, runId)
                .isNotNull(ContestRunEntity::getEndAt)
                .le(ContestRunEntity::getEndAt, now)
                .isNull(ContestRunEntity::getDeletedAt);
        List<ContestRunEntity> endedRuns = runMapper.selectList(wrapper);
        if (endedRuns.isEmpty()) {
            return Map.of();
        }
        Map<Long, Set<Long>> hiddenByRun = new LinkedHashMap<>();
        Map<Long, Set<Long>> privateIdsByContest = new HashMap<>();
        for (ContestRunEntity run : endedRuns) {
            Set<Long> privateProblemIds = privateIdsByContest.computeIfAbsent(run.getContestId(),
                    this::privateProblemIdsOfContest);
            if (privateProblemIds.isEmpty()) {
                continue;
            }
            Set<Long> hiddenContestProblemIds = new HashSet<>();
            for (ContestProblemEntity contestProblem : contestProblems(run.getContestId())) {
                if (privateProblemIds.contains(contestProblem.getProblemId())) {
                    hiddenContestProblemIds.add(contestProblem.getId());
                }
            }
            if (!hiddenContestProblemIds.isEmpty()) {
                hiddenByRun.put(run.getId(), hiddenContestProblemIds);
            }
        }
        return hiddenByRun;
    }

    /**
     * Exception path for GET /problems/{id}: an active participant may still read a
     * PRIVATE problem while the owning run window is open.
     */
    public boolean canViewPrivateProblem(Long userId, Long runId, Long contestProblemId, Long problemId, Instant now) {
        if (userId == null || runId == null || contestProblemId == null || problemId == null) {
            return false;
        }
        ContestRunEntity run = runMapper.selectOne(new LambdaQueryWrapper<ContestRunEntity>()
                .eq(ContestRunEntity::getId, runId)
                .isNull(ContestRunEntity::getDeletedAt));
        if (run == null || run.getStatus() == ContestRunStatus.DRAFT || run.getStatus() == ContestRunStatus.ARCHIVED) {
            return false;
        }
        if (run.getStartAt() == null || run.getEndAt() == null || now.isBefore(run.getStartAt()) || !now.isBefore(run.getEndAt())) {
            return false;
        }
        Long participants = participantMapper.selectCount(new LambdaQueryWrapper<ContestParticipantEntity>()
                .eq(ContestParticipantEntity::getContestRunId, runId)
                .eq(ContestParticipantEntity::getUserId, userId)
                .eq(ContestParticipantEntity::getStatus, ContestParticipantStatus.ACTIVE));
        if (participants == null || participants == 0) {
            return false;
        }
        ContestProblemEntity contestProblem = contestProblemMapper.selectOne(new LambdaQueryWrapper<ContestProblemEntity>()
                .eq(ContestProblemEntity::getId, contestProblemId)
                .eq(ContestProblemEntity::getContestId, run.getContestId()));
        return contestProblem != null && problemId.equals(contestProblem.getProblemId());
    }

    /**
     * Statement excerpts for every problem currently used by a run inside the AI
     * guard window. Serves the ai-service leak guard; statements are truncated to
     * maxStatementChars and read from run problem snapshots so later problem edits
     * do not change what a published run enforces.
     */
    public List<RunningContestProblemStatement> runningProblemStatements(Instant now, int maxStatementChars) {
        return statementsForRuns(guardWindowRuns(now), now, maxStatementChars);
    }

    /**
     * Statement excerpts (private and public, tagged with visibility) for problems used by
     * guard-window runs in which the user is an ACTIVE participant. Serves the participant leak
     * interceptor whose recall is scoped to the user's own contests.
     */
    public List<RunningContestProblemStatement> runningParticipationProblemStatements(Long userId, Instant now, int maxStatementChars) {
        List<RunningContestParticipation> participations = runningParticipations(userId, now);
        if (participations.isEmpty()) {
            return List.of();
        }
        Set<Long> runIds = new HashSet<>();
        for (RunningContestParticipation participation : participations) {
            runIds.add(participation.contestRunId());
        }
        List<ContestRunEntity> runs = runMapper.selectList(new LambdaQueryWrapper<ContestRunEntity>()
                        .in(ContestRunEntity::getId, runIds)
                        .isNull(ContestRunEntity::getDeletedAt))
                .stream()
                .sorted(inProgressFirstOrder(now))
                .toList();
        return statementsForRuns(runs, now, maxStatementChars);
    }

    /** Scheduled windows of every (non-deleted) run of a contest, for usage-record time filtering. */
    public List<ContestRunWindow> contestRunWindows(Long contestId) {
        return runMapper.selectList(new LambdaQueryWrapper<ContestRunEntity>()
                        .eq(ContestRunEntity::getContestId, contestId)
                        .isNull(ContestRunEntity::getDeletedAt))
                .stream()
                .map(run -> new ContestRunWindow(run.getId(), run.getStartAt(), run.getEndAt()))
                .toList();
    }

    /**
     * Guard-window runs in which the user is an ACTIVE participant. Runs still in
     * progress at {@code now} come first and grace tails after them, so a caller that
     * attributes the turn to a single run never lands on an already-ended run while a
     * newer run is open; startAt ascending keeps the order deterministic inside a group.
     */
    public List<RunningContestParticipation> runningParticipations(Long userId, Instant now) {
        if (userId == null) {
            return List.of();
        }
        List<ContestRunEntity> runningRuns = guardWindowRuns(now);
        if (runningRuns.isEmpty()) {
            return List.of();
        }
        List<Long> runIds = runningRuns.stream().map(ContestRunEntity::getId).toList();
        List<ContestParticipantEntity> participations = participantMapper.selectList(new LambdaQueryWrapper<ContestParticipantEntity>()
                .in(ContestParticipantEntity::getContestRunId, runIds)
                .eq(ContestParticipantEntity::getUserId, userId)
                .eq(ContestParticipantEntity::getStatus, ContestParticipantStatus.ACTIVE));
        if (participations.isEmpty()) {
            return List.of();
        }
        Map<Long, ContestRunEntity> runsById = new HashMap<>();
        for (ContestRunEntity run : runningRuns) {
            runsById.putIfAbsent(run.getId(), run);
        }
        return participations.stream()
                .map(participation -> {
                    ContestRunEntity run = runsById.get(participation.getContestRunId());
                    return run == null ? null : new RunningContestParticipation(
                            run.getContestId(), run.getId(), run.getStartAt(), run.getEndAt());
                })
                .filter(Objects::nonNull)
                .sorted(Comparator.comparingInt((RunningContestParticipation participation) -> inProgressRank(participation.endAt(), now))
                        .thenComparing(RunningContestParticipation::startAt, Comparator.nullsLast(Comparator.naturalOrder())))
                .toList();
    }

    /**
     * Runs whose AI guard window is open at {@code now}: the run has started and
     * {@code now < endAt + aiGuardGraceSeconds}. The grace tail keeps late AI turns
     * covered right after the contest ends; DRAFT/ARCHIVED/deleted runs never qualify.
     * Returned in-progress runs first (grace tails after), startAt ascending inside a group.
     */
    private List<ContestRunEntity> guardWindowRuns(Instant now) {
        List<ContestRunEntity> runs = runMapper.selectList(new LambdaQueryWrapper<ContestRunEntity>()
                .isNull(ContestRunEntity::getDeletedAt));
        return runs.stream()
                .filter(run -> isGuardWindowRun(run, now))
                .sorted(inProgressFirstOrder(now))
                .toList();
    }

    /** In-progress runs ({@code now < endAt}) sort before grace tails; startAt ascending inside a group. */
    private static Comparator<ContestRunEntity> inProgressFirstOrder(Instant now) {
        return Comparator.comparingInt((ContestRunEntity run) -> inProgressRank(run.getEndAt(), now))
                .thenComparing(ContestRunEntity::getStartAt, Comparator.nullsLast(Comparator.naturalOrder()));
    }

    /** 0 while the run is still in progress at {@code now}; 1 once it sits in its grace tail. */
    private static int inProgressRank(Instant endAt, Instant now) {
        return endAt == null || now.isBefore(endAt) ? 0 : 1;
    }

    private boolean isGuardWindowRun(ContestRunEntity run, Instant now) {
        if (run.getStatus() == ContestRunStatus.DRAFT || run.getStatus() == ContestRunStatus.ARCHIVED
                || run.getStartAt() == null || run.getEndAt() == null) {
            return false;
        }
        return !now.isBefore(run.getStartAt())
                && now.isBefore(run.getEndAt().plusSeconds(contestProperties.getAiGuardGraceSeconds()));
    }

    private List<RunningContestProblemStatement> statementsForRuns(List<ContestRunEntity> runs, Instant now, int maxStatementChars) {
        if (runs.isEmpty()) {
            return List.of();
        }
        Map<Long, ContestRunEntity> runsById = new LinkedHashMap<>();
        for (ContestRunEntity run : runs) {
            runsById.putIfAbsent(run.getId(), run);
        }
        List<ContestRunProblemSnapshotEntity> snapshots = runProblemSnapshotMapper.selectList(
                new LambdaQueryWrapper<ContestRunProblemSnapshotEntity>()
                        .in(ContestRunProblemSnapshotEntity::getContestRunId, runsById.keySet()));
        if (snapshots.isEmpty()) {
            return List.of();
        }
        // Merge snapshots of the same problem across runs into one entry: visibility
        // PRIVATE wins, policy mode takes the strictest (STRICT > DEFAULT > DISABLED),
        // policy notes are concatenated with the source run title, and every
        // (contest, run, contest problem) coordinate is preserved. The representative
        // coordinates prefer an occurrence from a run still in progress at `now`: a
        // grace tail participates in matching but never claims the merged attribution
        // while a newer run is open.
        Map<Long, MergedProblem> mergedByProblem = new LinkedHashMap<>();
        for (ContestRunProblemSnapshotEntity snapshot : snapshots) {
            ContestRunEntity run = runsById.get(snapshot.getContestRunId());
            if (run == null) {
                continue;
            }
            MergedProblem merged = mergedByProblem.computeIfAbsent(snapshot.getProblemId(), id -> new MergedProblem());
            merged.occurrences.add(new RunningContestProblemOccurrence(
                    snapshot.getContestId(), snapshot.getContestRunId(), snapshot.getContestProblemId()));
            if (snapshot.getVisibility() == ProblemVisibility.PRIVATE) {
                merged.privateSeen = true;
            }
            merged.aiPolicyMode = strictest(merged.aiPolicyMode, effectiveAiPolicyMode(run));
            if (StringUtils.hasText(run.getAiPolicyNotesSnapshot())) {
                merged.notes.add("[" + (StringUtils.hasText(run.getTitle()) ? run.getTitle() : "#" + run.getId()) + "] "
                        + run.getAiPolicyNotesSnapshot().trim());
            }
            if (merged.statement == null && StringUtils.hasText(snapshot.getStatement())) {
                merged.statement = snapshot.getStatement();
            }
        }
        List<RunningContestProblemStatement> statements = new ArrayList<>();
        for (Map.Entry<Long, MergedProblem> entry : mergedByProblem.entrySet()) {
            MergedProblem merged = entry.getValue();
            if (merged.statement == null || merged.occurrences.isEmpty()) {
                continue;
            }
            RunningContestProblemOccurrence first = merged.occurrences.stream()
                    .min(Comparator.comparingInt(occurrence -> {
                        ContestRunEntity run = runsById.get(occurrence.contestRunId());
                        return inProgressRank(run == null ? null : run.getEndAt(), now);
                    }))
                    .orElse(merged.occurrences.get(0));
            statements.add(new RunningContestProblemStatement(entry.getKey(),
                    merged.statement.length() <= maxStatementChars ? merged.statement : merged.statement.substring(0, maxStatementChars),
                    first.contestId(),
                    first.contestRunId(),
                    first.contestProblemId(),
                    merged.privateSeen ? ProblemVisibility.PRIVATE : ProblemVisibility.PUBLIC,
                    merged.aiPolicyMode,
                    merged.notes.isEmpty() ? null : String.join("\n", merged.notes),
                    List.copyOf(merged.occurrences)));
        }
        return statements;
    }

    private ContestAiPolicyMode effectiveAiPolicyMode(ContestRunEntity run) {
        return run.getAiPolicyModeSnapshot() == null ? ContestAiPolicyMode.DEFAULT : run.getAiPolicyModeSnapshot();
    }

    private ContestAiPolicyMode strictest(ContestAiPolicyMode current, ContestAiPolicyMode candidate) {
        return severity(candidate) > severity(current) ? candidate : current;
    }

    private int severity(ContestAiPolicyMode mode) {
        return switch (mode) {
            case STRICT -> 3;
            case DEFAULT -> 2;
            case DISABLED -> 1;
        };
    }

    private static final class MergedProblem {
        private final List<RunningContestProblemOccurrence> occurrences = new ArrayList<>();
        private final List<String> notes = new ArrayList<>();
        private String statement;
        private boolean privateSeen;
        private ContestAiPolicyMode aiPolicyMode = ContestAiPolicyMode.DISABLED;
    }

    /** Live visibility for every problem used by a contest (problemId -> visibility). */
    public Map<Long, ProblemVisibility> problemVisibilityMap(Long contestId) {
        List<ContestProblemEntity> contestProblems = contestProblems(contestId);
        if (contestProblems.isEmpty()) {
            return Map.of();
        }
        Set<Long> problemIds = contestProblems.stream().map(ContestProblemEntity::getProblemId).collect(java.util.stream.Collectors.toSet());
        List<ProblemEntity> problems = problemMapper.selectList(new LambdaQueryWrapper<ProblemEntity>()
                .in(ProblemEntity::getId, problemIds));
        Map<Long, ProblemVisibility> visibility = new HashMap<>();
        for (ProblemEntity problem : problems) {
            visibility.put(problem.getId(), problem.getVisibility() == null ? ProblemVisibility.PUBLIC : problem.getVisibility());
        }
        return visibility;
    }

    private Set<Long> privateProblemIdsOfContest(Long contestId) {
        List<ContestProblemEntity> contestProblems = contestProblems(contestId);
        if (contestProblems.isEmpty()) {
            return Set.of();
        }
        Set<Long> problemIds = contestProblems.stream().map(ContestProblemEntity::getProblemId).collect(java.util.stream.Collectors.toSet());
        List<ProblemEntity> privateProblems = problemMapper.selectList(new LambdaQueryWrapper<ProblemEntity>()
                .in(ProblemEntity::getId, problemIds)
                .eq(ProblemEntity::getVisibility, ProblemVisibility.PRIVATE));
        Set<Long> hidden = new HashSet<>();
        for (ProblemEntity problem : privateProblems) {
            hidden.add(problem.getId());
        }
        return hidden;
    }

    private List<ContestProblemEntity> contestProblems(Long contestId) {
        return contestProblemMapper.selectList(new LambdaQueryWrapper<ContestProblemEntity>()
                .eq(ContestProblemEntity::getContestId, contestId));
    }
}
