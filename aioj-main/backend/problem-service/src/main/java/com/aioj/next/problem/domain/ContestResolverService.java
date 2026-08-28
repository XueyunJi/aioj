package com.aioj.next.problem.domain;

import com.aioj.next.common.error.DomainException;
import com.aioj.next.common.error.ErrorCode;
import com.aioj.next.common.security.Role;
import com.aioj.next.common.security.SecuritySupport;
import com.aioj.next.contract.contest.ContestMode;
import com.aioj.next.contract.contest.ContestParticipantStatus;
import com.aioj.next.contract.contest.ContestResolverSessionCreateRequest;
import com.aioj.next.contract.contest.ContestResolverSessionDetailResponse;
import com.aioj.next.contract.contest.ContestResolverSessionResponse;
import com.aioj.next.contract.contest.ContestResolverSessionStatus;
import com.aioj.next.contract.contest.ContestResolverStepResponse;
import com.aioj.next.contract.contest.ContestResolverStepType;
import com.aioj.next.contract.contest.ContestRunStatus;
import com.aioj.next.contract.contest.ContestScoreboardCellResponse;
import com.aioj.next.contract.contest.ContestScoreboardCellStatus;
import com.aioj.next.contract.contest.ContestScoreboardProblemResponse;
import com.aioj.next.contract.contest.ContestScoreboardResponse;
import com.aioj.next.contract.contest.ContestScoreboardRowResponse;
import com.aioj.next.contract.contest.ContestScoreboardSnapshotCreateRequest;
import com.aioj.next.contract.contest.ContestScoreboardSnapshotKind;
import com.aioj.next.contract.contest.ContestScoreboardView;
import com.aioj.next.contract.contest.ContestStatus;
import com.aioj.next.contract.submission.SubmissionStatus;
import com.aioj.next.problem.persistence.entity.ContestEntity;
import com.aioj.next.problem.persistence.entity.ContestParticipantEntity;
import com.aioj.next.problem.persistence.entity.ContestProblemEntity;
import com.aioj.next.problem.persistence.entity.ContestResolverSessionEntity;
import com.aioj.next.problem.persistence.entity.ContestResolverStepEntity;
import com.aioj.next.problem.persistence.entity.ContestRunEntity;
import com.aioj.next.problem.persistence.entity.SubmissionEntity;
import com.aioj.next.problem.persistence.mapper.ContestMapper;
import com.aioj.next.problem.persistence.mapper.ContestParticipantMapper;
import com.aioj.next.problem.persistence.mapper.ContestProblemMapper;
import com.aioj.next.problem.persistence.mapper.ContestResolverSessionMapper;
import com.aioj.next.problem.persistence.mapper.ContestResolverStepMapper;
import com.aioj.next.problem.persistence.mapper.ContestRunMapper;
import com.aioj.next.problem.persistence.mapper.SubmissionMapper;
import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

@Service
public class ContestResolverService {
    private static final long MINUTE_MILLIS = 60_000L;

    private final ContestMapper contestMapper;
    private final ContestRunMapper contestRunMapper;
    private final ContestProblemMapper contestProblemMapper;
    private final ContestParticipantMapper contestParticipantMapper;
    private final SubmissionMapper submissionMapper;
    private final ContestResolverSessionMapper sessionMapper;
    private final ContestResolverStepMapper stepMapper;
    private final ContestScoreboardService scoreboardService;
    private final ObjectMapper objectMapper;

    public ContestResolverService(ContestMapper contestMapper,
                                  ContestRunMapper contestRunMapper,
                                  ContestProblemMapper contestProblemMapper,
                                  ContestParticipantMapper contestParticipantMapper,
                                  SubmissionMapper submissionMapper,
                                  ContestResolverSessionMapper sessionMapper,
                                  ContestResolverStepMapper stepMapper,
                                  ContestScoreboardService scoreboardService,
                                  ObjectMapper objectMapper) {
        this.contestMapper = contestMapper;
        this.contestRunMapper = contestRunMapper;
        this.contestProblemMapper = contestProblemMapper;
        this.contestParticipantMapper = contestParticipantMapper;
        this.submissionMapper = submissionMapper;
        this.sessionMapper = sessionMapper;
        this.stepMapper = stepMapper;
        this.scoreboardService = scoreboardService;
        this.objectMapper = objectMapper;
    }

    @Transactional
    public ContestResolverSessionDetailResponse createSession(Long contestId, Long runId,
                                                              ContestResolverSessionCreateRequest request) {
        ContestEntity contest = requireContest(contestId);
        ContestRunEntity run = requireRun(contestId, runId);
        assertCanManage(contest);
        if (effectiveMode(contest, run) != ContestMode.ACM) {
            throw new DomainException(ErrorCode.BAD_REQUEST, "Resolver only supports ACM contests");
        }
        if (run.getFreezeAt() == null) {
            throw new DomainException(ErrorCode.BAD_REQUEST, "Resolver requires a freeze time");
        }
        Instant now = Instant.now();
        if (now.isBefore(run.getEndAt())) {
            throw new DomainException(ErrorCode.BAD_REQUEST, "Resolver can only be generated after the run ends");
        }
        long unfinished = submissionMapper.selectCount(new LambdaQueryWrapper<SubmissionEntity>()
                .eq(SubmissionEntity::getContestId, contestId)
                .eq(SubmissionEntity::getContestRunId, runId)
                .in(SubmissionEntity::getStatus, SubmissionStatus.QUEUED, SubmissionStatus.RUNNING, SubmissionStatus.SYSTEM_ERROR));
        if (unfinished > 0) {
            throw new DomainException(ErrorCode.BAD_REQUEST, "Contest run has unfinished or system-error submissions");
        }

        long durationMillis = durationMillis(run);
        long freezeMillis = Math.max(0L, Duration.between(run.getStartAt(), run.getFreezeAt()).toMillis());
        ContestScoreboardResponse freezeSnapshot = scoreboardService.createSnapshot(contestId, runId,
                new ContestScoreboardSnapshotCreateRequest(ContestScoreboardSnapshotKind.PUBLIC_FROZEN,
                        ContestScoreboardView.PUBLIC, durationMillis));
        ContestScoreboardResponse finalSnapshot = scoreboardService.createSnapshot(contestId, runId,
                new ContestScoreboardSnapshotCreateRequest(ContestScoreboardSnapshotKind.FINAL,
                        ContestScoreboardView.PRIVATE, durationMillis));

        Instant createdAt = Instant.now();
        ContestResolverSessionEntity session = new ContestResolverSessionEntity();
        session.setContestId(contestId);
        session.setContestRunId(runId);
        session.setStatus(ContestResolverSessionStatus.DRAFT);
        session.setTitle(resolveTitle(request, run));
        session.setViewType(ContestScoreboardView.PUBLIC);
        session.setFreezeSnapshotId(freezeSnapshot.snapshotId());
        session.setFinalSnapshotId(finalSnapshot.snapshotId());
        session.setStepCount(0);
        session.setChecksum("pending");
        session.setCreatedBy(SecuritySupport.currentUserId());
        session.setCreatedAt(createdAt);
        session.setUpdatedAt(createdAt);
        sessionMapper.insert(session);

        List<ContestProblemEntity> problems = contestProblems(contestId);
        List<ContestParticipantEntity> participants = contestParticipants(contestId, runId);
        List<SubmissionEntity> submissions = contestSubmissions(contestId, runId, durationMillis);
        List<ContestResolverStepEntity> steps = buildSteps(session, contest, run, problems, participants,
                submissions, freezeMillis, durationMillis, createdAt);
        for (ContestResolverStepEntity step : steps) {
            stepMapper.insert(step);
        }
        session.setStepCount(steps.size());
        session.setChecksum(checksumFor(steps.stream().map(ContestResolverStepEntity::getScoreboardJson).toList().toString()));
        session.setUpdatedAt(Instant.now());
        sessionMapper.updateById(session);
        return detail(contestId, runId, session.getId());
    }

    public List<ContestResolverSessionResponse> listSessions(Long contestId, Long runId) {
        ContestEntity contest = requireContest(contestId);
        ContestRunEntity run = requireRun(contestId, runId);
        boolean manager = canManage(contest);
        if (!manager) {
            assertCanReadPublished(contest, run);
        }
        LambdaQueryWrapper<ContestResolverSessionEntity> query = new LambdaQueryWrapper<ContestResolverSessionEntity>()
                .eq(ContestResolverSessionEntity::getContestId, contestId)
                .eq(ContestResolverSessionEntity::getContestRunId, runId)
                .isNull(ContestResolverSessionEntity::getDeletedAt)
                .ne(!manager, ContestResolverSessionEntity::getStatus, ContestResolverSessionStatus.DRAFT)
                .eq(!manager, ContestResolverSessionEntity::getStatus, ContestResolverSessionStatus.PUBLISHED)
                .orderByDesc(ContestResolverSessionEntity::getCreatedAt)
                .orderByDesc(ContestResolverSessionEntity::getId);
        return sessionMapper.selectList(query).stream().map(this::toSessionResponse).toList();
    }

    public ContestResolverSessionDetailResponse detail(Long contestId, Long runId, Long sessionId) {
        ContestEntity contest = requireContest(contestId);
        ContestRunEntity run = requireRun(contestId, runId);
        ContestResolverSessionEntity session = requireSession(contestId, runId, sessionId);
        assertCanReadSession(contest, run, session);
        List<ContestResolverStepResponse> steps = stepMapper.selectList(new LambdaQueryWrapper<ContestResolverStepEntity>()
                        .eq(ContestResolverStepEntity::getResolverSessionId, sessionId)
                        .orderByAsc(ContestResolverStepEntity::getStepOrder))
                .stream()
                .map(this::toStepResponse)
                .toList();
        return new ContestResolverSessionDetailResponse(toSessionResponse(session), steps);
    }

    @Transactional
    public ContestResolverSessionResponse publish(Long contestId, Long runId, Long sessionId) {
        ContestEntity contest = requireContest(contestId);
        requireRun(contestId, runId);
        assertCanManage(contest);
        ContestResolverSessionEntity session = requireSession(contestId, runId, sessionId);
        if (session.getStatus() == ContestResolverSessionStatus.ARCHIVED) {
            throw new DomainException(ErrorCode.BAD_REQUEST, "Archived resolver session cannot be published");
        }
        Instant now = Instant.now();
        session.setStatus(ContestResolverSessionStatus.PUBLISHED);
        session.setPublishedAt(now);
        session.setArchivedAt(null);
        session.setStatusBeforeArchive(null);
        session.setUpdatedAt(now);
        sessionMapper.updateById(session);
        return toSessionResponse(session);
    }

    @Transactional
    public ContestResolverSessionResponse archive(Long contestId, Long runId, Long sessionId) {
        ContestEntity contest = requireContest(contestId);
        requireRun(contestId, runId);
        assertCanManage(contest);
        ContestResolverSessionEntity session = requireSession(contestId, runId, sessionId);
        Instant now = Instant.now();
        if (session.getStatus() != ContestResolverSessionStatus.ARCHIVED) {
            session.setStatusBeforeArchive(session.getStatus());
        }
        session.setStatus(ContestResolverSessionStatus.ARCHIVED);
        session.setArchivedAt(now);
        session.setUpdatedAt(now);
        sessionMapper.updateById(session);
        return toSessionResponse(session);
    }

    @Transactional
    public ContestResolverSessionResponse restore(Long contestId, Long runId, Long sessionId) {
        ContestEntity contest = requireContest(contestId);
        requireRun(contestId, runId);
        assertCanManage(contest);
        ContestResolverSessionEntity session = requireSession(contestId, runId, sessionId);
        if (session.getStatus() != ContestResolverSessionStatus.ARCHIVED) {
            throw new DomainException(ErrorCode.BAD_REQUEST, "Only archived resolver sessions can be restored");
        }
        Instant now = Instant.now();
        session.setStatus(session.getStatusBeforeArchive() == null ? ContestResolverSessionStatus.DRAFT : session.getStatusBeforeArchive());
        session.setStatusBeforeArchive(null);
        session.setArchivedAt(null);
        session.setUpdatedAt(now);
        sessionMapper.updateById(session);
        return toSessionResponse(session);
    }

    @Transactional
    public ContestResolverSessionResponse delete(Long contestId, Long runId, Long sessionId) {
        ContestEntity contest = requireContest(contestId);
        requireRun(contestId, runId);
        assertCanManage(contest);
        ContestResolverSessionEntity session = requireSession(contestId, runId, sessionId);
        if (session.getStatus() != ContestResolverSessionStatus.ARCHIVED) {
            throw new DomainException(ErrorCode.BAD_REQUEST, "Only archived resolver sessions can be deleted");
        }
        Instant now = Instant.now();
        session.setDeletedAt(now);
        session.setDeletedBy(SecuritySupport.currentUserId());
        session.setUpdatedAt(now);
        sessionMapper.updateById(session);
        return toSessionResponse(session);
    }

    private List<ContestResolverStepEntity> buildSteps(ContestResolverSessionEntity session, ContestEntity contest,
                                                       ContestRunEntity run, List<ContestProblemEntity> problems,
                                                       List<ContestParticipantEntity> participants,
                                                       List<SubmissionEntity> submissions, long freezeMillis,
                                                       long durationMillis, Instant createdAt) {
        Map<Long, ContestScoreboardRowResponse> frozenRows = buildScoreboard(contest, run, problems, participants,
                submissions, freezeMillis, durationMillis, Set.of(), false).rows().stream()
                .collect(HashMap::new, (map, row) -> map.put(row.participantId(), row), Map::putAll);
        List<ResolverReveal> reveals = resolverReveals(submissions, frozenRows, freezeMillis, durationMillis);

        List<ContestResolverStepEntity> steps = new ArrayList<>();
        Set<Long> revealedSubmissionIds = new HashSet<>();
        steps.add(newStep(session, 0, ContestResolverStepType.INITIAL, null,
                payload("initial", null, null, null),
                buildScoreboard(contest, run, problems, participants, submissions, freezeMillis, durationMillis,
                        revealedSubmissionIds, false), createdAt));
        int order = 1;
        for (ResolverReveal reveal : reveals) {
            SubmissionEntity submission = reveal.submission();
            revealedSubmissionIds.addAll(reveal.revealedSubmissionIds());
            ContestProblemEntity problem = problems.stream()
                    .filter(item -> Objects.equals(item.getId(), submission.getContestProblemId()))
                    .findFirst()
                    .orElse(null);
            ContestParticipantEntity participant = participants.stream()
                    .filter(item -> Objects.equals(item.getId(), submission.getContestParticipantId()))
                    .findFirst()
                    .orElse(null);
            steps.add(newStep(session, order++, ContestResolverStepType.REVEAL, submission,
                    payload("reveal", participant, problem, submission),
                    buildScoreboard(contest, run, problems, participants, submissions, freezeMillis, durationMillis,
                            revealedSubmissionIds, false), createdAt));
        }
        steps.add(newStep(session, order, ContestResolverStepType.FINAL, null,
                payload("final", null, null, null),
                buildScoreboard(contest, run, problems, participants, submissions, freezeMillis, durationMillis,
                        reveals.stream()
                                .flatMap(reveal -> reveal.revealedSubmissionIds().stream())
                                .collect(HashSet::new, HashSet::add, HashSet::addAll),
                        true), createdAt));
        return steps;
    }

    private List<ResolverReveal> resolverReveals(List<SubmissionEntity> submissions,
                                                 Map<Long, ContestScoreboardRowResponse> frozenRows,
                                                 long freezeMillis, long durationMillis) {
        Map<CellKey, List<SubmissionEntity>> byCell = new HashMap<>();
        for (SubmissionEntity submission : submissions) {
            long submittedAt = safeLong(submission.getSubmittedAtContestMillis());
            if (submittedAt > durationMillis) {
                continue;
            }
            byCell.computeIfAbsent(new CellKey(submission.getContestParticipantId(), submission.getContestProblemId()),
                    ignored -> new ArrayList<>()).add(submission);
        }
        List<ResolverReveal> reveals = new ArrayList<>();
        for (List<SubmissionEntity> cellSubmissions : byCell.values()) {
            List<SubmissionEntity> ordered = cellSubmissions.stream()
                    .sorted(Comparator.comparing((SubmissionEntity item) -> safeLong(item.getSubmittedAtContestMillis()))
                            .thenComparing(SubmissionEntity::getId))
                    .toList();
            if (isSolvedAtOrBefore(ordered, freezeMillis)) {
                continue;
            }
            List<SubmissionEntity> afterFreeze = ordered.stream()
                    .filter(submission -> safeLong(submission.getSubmittedAtContestMillis()) > freezeMillis)
                    .filter(submission -> safeLong(submission.getSubmittedAtContestMillis()) <= durationMillis)
                    .toList();
            if (afterFreeze.isEmpty()) {
                continue;
            }
            SubmissionEntity representative = firstAccepted(afterFreeze);
            if (representative == null) {
                representative = afterFreeze.get(afterFreeze.size() - 1);
            }
            long revealAt = safeLong(representative.getSubmittedAtContestMillis());
            Set<Long> revealedIds = afterFreeze.stream()
                    .filter(submission -> safeLong(submission.getSubmittedAtContestMillis()) <= revealAt)
                    .map(SubmissionEntity::getId)
                    .collect(HashSet::new, HashSet::add, HashSet::addAll);
            reveals.add(new ResolverReveal(representative, revealedIds));
        }
        return reveals.stream()
                .sorted(Comparator.comparing((ResolverReveal reveal) ->
                                frozenRank(frozenRows, reveal.submission().getContestParticipantId()))
                        .reversed()
                        .thenComparing(reveal -> safeLong(reveal.submission().getSubmittedAtContestMillis()))
                        .thenComparing(reveal -> reveal.submission().getId()))
                .toList();
    }

    private boolean isSolvedAtOrBefore(List<SubmissionEntity> submissions, long atMillis) {
        return submissions.stream()
                .filter(submission -> safeLong(submission.getSubmittedAtContestMillis()) <= atMillis)
                .anyMatch(submission -> submission.getStatus() == SubmissionStatus.ACCEPTED);
    }

    private SubmissionEntity firstAccepted(List<SubmissionEntity> submissions) {
        for (SubmissionEntity submission : submissions) {
            if (submission.getStatus() == SubmissionStatus.ACCEPTED) {
                return submission;
            }
        }
        return null;
    }

    private ContestResolverStepEntity newStep(ContestResolverSessionEntity session, int order,
                                              ContestResolverStepType stepType, SubmissionEntity submission,
                                              Map<String, Object> payload, ContestScoreboardResponse scoreboard,
                                              Instant createdAt) {
        ContestResolverStepEntity entity = new ContestResolverStepEntity();
        entity.setResolverSessionId(session.getId());
        entity.setContestId(session.getContestId());
        entity.setContestRunId(session.getContestRunId());
        entity.setStepOrder(order);
        entity.setStepType(stepType);
        entity.setParticipantId(submission == null ? null : submission.getContestParticipantId());
        entity.setContestProblemId(submission == null ? null : submission.getContestProblemId());
        entity.setSubmissionId(submission == null ? null : submission.getId());
        entity.setPayloadJson(writeJson(payload));
        entity.setScoreboardJson(writeJson(scoreboard));
        entity.setCreatedAt(createdAt);
        return entity;
    }

    private ContestScoreboardResponse buildScoreboard(ContestEntity contest, ContestRunEntity run,
                                                      List<ContestProblemEntity> problems,
                                                      List<ContestParticipantEntity> participants,
                                                      List<SubmissionEntity> submissions, long freezeMillis,
                                                      long durationMillis, Set<Long> revealedSubmissionIds,
                                                      boolean finalStep) {
        int penaltyMinutes = effectivePenaltyMinutes(contest, run);
        boolean cePenalty = effectiveCePenalty(contest, run);
        List<ContestScoreboardProblemResponse> problemResponses = problems.stream()
                .map(problem -> new ContestScoreboardProblemResponse(problem.getId(), problem.getProblemId(),
                        problem.getLabel(), problem.getDisplayTitle(), safeInt(problem.getScore()), safeInt(problem.getSortOrder())))
                .toList();
        Map<CellKey, List<SubmissionEntity>> byCell = new HashMap<>();
        for (SubmissionEntity submission : submissions) {
            byCell.computeIfAbsent(new CellKey(submission.getContestParticipantId(), submission.getContestProblemId()),
                    ignored -> new ArrayList<>()).add(submission);
        }
        List<ContestScoreboardRowResponse> rows = new ArrayList<>();
        for (ContestParticipantEntity participant : participants) {
            List<ContestScoreboardCellResponse> cells = new ArrayList<>();
            int solved = 0;
            int totalPenalty = 0;
            Long lastAccepted = null;
            for (ContestProblemEntity problem : problems) {
                ContestScoreboardCellResponse cell = scoreCell(problem.getId(),
                        byCell.getOrDefault(new CellKey(participant.getId(), problem.getId()), List.of()),
                        freezeMillis, durationMillis, revealedSubmissionIds, penaltyMinutes, cePenalty);
                cells.add(cell);
                if (cell.status() == ContestScoreboardCellStatus.SOLVED) {
                    solved++;
                    totalPenalty += cell.penaltyMinutes();
                    if (cell.acceptedAtMillis() != null) {
                        lastAccepted = lastAccepted == null ? cell.acceptedAtMillis() : Math.max(lastAccepted, cell.acceptedAtMillis());
                    }
                }
            }
            rows.add(new ContestScoreboardRowResponse(0, participant.getId(), participant.getUserId(),
                    participant.getAccountSnapshot(), participant.getDisplayNameSnapshot(), solved, totalPenalty,
                    lastAccepted, null, null, cells));
        }
        return new ContestScoreboardResponse(contest.getId(), run.getId(), ContestMode.ACM, ContestScoreboardView.PUBLIC,
                null, ContestScoreboardSnapshotKind.LIVE, durationMillis, Instant.now(), !finalStep,
                freezeMillis, penaltyMinutes, cePenalty, problemResponses, rankRows(rows));
    }

    private ContestScoreboardCellResponse scoreCell(Long contestProblemId, List<SubmissionEntity> submissions,
                                                    long freezeMillis, long durationMillis, Set<Long> revealedSubmissionIds,
                                                    int penaltyMinutes, boolean cePenalty) {
        int attempts = 0;
        int wrongAttempts = 0;
        int pendingAttempts = 0;
        Long acceptedAt = null;
        for (SubmissionEntity submission : submissions.stream()
                .sorted(Comparator.comparing((SubmissionEntity item) -> safeLong(item.getSubmittedAtContestMillis()))
                        .thenComparing(SubmissionEntity::getId))
                .toList()) {
            long submittedAt = safeLong(submission.getSubmittedAtContestMillis());
            if (submittedAt > durationMillis) {
                continue;
            }
            if (acceptedAt != null) {
                continue;
            }
            boolean afterFreeze = submittedAt > freezeMillis;
            if (afterFreeze && !revealedSubmissionIds.contains(submission.getId())) {
                attempts++;
                pendingAttempts++;
                continue;
            }
            attempts++;
            SubmissionStatus status = submission.getStatus();
            if (status == SubmissionStatus.ACCEPTED) {
                acceptedAt = submittedAt;
            } else if (isPenaltyStatus(status, cePenalty)) {
                wrongAttempts++;
            } else if (isPendingStatus(status)) {
                pendingAttempts++;
            }
        }
        if (acceptedAt != null) {
            int acceptedMinute = (int) (acceptedAt / MINUTE_MILLIS);
            int problemPenalty = acceptedMinute + wrongAttempts * penaltyMinutes;
            return new ContestScoreboardCellResponse(contestProblemId, ContestScoreboardCellStatus.SOLVED,
                    attempts, wrongAttempts, pendingAttempts, acceptedAt, problemPenalty,
                    null, null, null, null);
        }
        ContestScoreboardCellStatus status = ContestScoreboardCellStatus.UNSOLVED;
        if (pendingAttempts > 0) {
            status = ContestScoreboardCellStatus.PENDING;
        } else if (wrongAttempts > 0 || attempts > 0) {
            status = ContestScoreboardCellStatus.ATTEMPTED;
        }
        return new ContestScoreboardCellResponse(contestProblemId, status, attempts, wrongAttempts,
                pendingAttempts, null, 0, null, null, null, null);
    }

    private List<ContestScoreboardRowResponse> rankRows(List<ContestScoreboardRowResponse> rows) {
        List<ContestScoreboardRowResponse> sorted = rows.stream()
                .sorted(Comparator.comparingInt(ContestScoreboardRowResponse::solvedCount).reversed()
                        .thenComparingInt(ContestScoreboardRowResponse::penaltyMinutes)
                        .thenComparing(row -> row.lastAcceptedAtMillis() == null ? Long.MAX_VALUE : row.lastAcceptedAtMillis())
                        .thenComparing(ContestScoreboardRowResponse::displayNameSnapshot)
                        .thenComparing(ContestScoreboardRowResponse::participantId))
                .toList();
        List<ContestScoreboardRowResponse> ranked = new ArrayList<>();
        int rank = 0;
        ContestScoreboardRowResponse previous = null;
        for (int index = 0; index < sorted.size(); index++) {
            ContestScoreboardRowResponse row = sorted.get(index);
            if (previous == null || !sameScore(previous, row)) {
                rank = index + 1;
            }
            ranked.add(new ContestScoreboardRowResponse(rank, row.participantId(), row.userId(),
                    row.accountSnapshot(), row.displayNameSnapshot(), row.solvedCount(), row.penaltyMinutes(),
                    row.lastAcceptedAtMillis(), row.totalScore(), row.lastScoreImprovedAtMillis(), row.cells()));
            previous = row;
        }
        return ranked;
    }

    private boolean sameScore(ContestScoreboardRowResponse left, ContestScoreboardRowResponse right) {
        long leftLast = left.lastAcceptedAtMillis() == null ? Long.MAX_VALUE : left.lastAcceptedAtMillis();
        long rightLast = right.lastAcceptedAtMillis() == null ? Long.MAX_VALUE : right.lastAcceptedAtMillis();
        return left.solvedCount() == right.solvedCount()
                && left.penaltyMinutes() == right.penaltyMinutes()
                && leftLast == rightLast;
    }

    private Map<String, Object> payload(String event, ContestParticipantEntity participant,
                                        ContestProblemEntity problem, SubmissionEntity submission) {
        Map<String, Object> payload = new HashMap<>();
        payload.put("event", event);
        if (participant != null) {
            payload.put("participantId", participant.getId());
            payload.put("accountSnapshot", participant.getAccountSnapshot());
            payload.put("displayNameSnapshot", participant.getDisplayNameSnapshot());
        }
        if (problem != null) {
            payload.put("contestProblemId", problem.getId());
            payload.put("problemLabel", problem.getLabel());
            payload.put("problemTitle", problem.getDisplayTitle());
        }
        if (submission != null) {
            payload.put("submissionId", submission.getId());
            payload.put("status", submission.getStatus());
            payload.put("submittedAtContestMillis", submission.getSubmittedAtContestMillis());
        }
        return payload;
    }

    private ContestEntity requireContest(Long id) {
        ContestEntity contest = contestMapper.selectById(id);
        if (contest == null || contest.getDeletedAt() != null) {
            throw new DomainException(ErrorCode.NOT_FOUND, "Contest not found");
        }
        return contest;
    }

    private ContestRunEntity requireRun(Long contestId, Long runId) {
        ContestRunEntity run = contestRunMapper.selectById(runId);
        if (run == null || run.getDeletedAt() != null || !contestId.equals(run.getContestId())) {
            throw new DomainException(ErrorCode.NOT_FOUND, "Contest run not found");
        }
        return run;
    }

    private ContestResolverSessionEntity requireSession(Long contestId, Long runId, Long sessionId) {
        ContestResolverSessionEntity session = sessionMapper.selectById(sessionId);
        if (session == null || session.getDeletedAt() != null
                || !contestId.equals(session.getContestId()) || !runId.equals(session.getContestRunId())) {
            throw new DomainException(ErrorCode.NOT_FOUND, "Resolver session not found");
        }
        return session;
    }

    private List<ContestProblemEntity> contestProblems(Long contestId) {
        return contestProblemMapper.selectList(new LambdaQueryWrapper<ContestProblemEntity>()
                .eq(ContestProblemEntity::getContestId, contestId)
                .orderByAsc(ContestProblemEntity::getSortOrder)
                .orderByAsc(ContestProblemEntity::getId));
    }

    private List<ContestParticipantEntity> contestParticipants(Long contestId, Long runId) {
        return contestParticipantMapper.selectList(new LambdaQueryWrapper<ContestParticipantEntity>()
                .eq(ContestParticipantEntity::getContestId, contestId)
                .eq(ContestParticipantEntity::getContestRunId, runId)
                .eq(ContestParticipantEntity::getStatus, ContestParticipantStatus.ACTIVE)
                .orderByAsc(ContestParticipantEntity::getRegisteredAt)
                .orderByAsc(ContestParticipantEntity::getId));
    }

    private List<SubmissionEntity> contestSubmissions(Long contestId, Long runId, long atMillis) {
        return submissionMapper.selectList(new LambdaQueryWrapper<SubmissionEntity>()
                .eq(SubmissionEntity::getContestId, contestId)
                .eq(SubmissionEntity::getContestRunId, runId)
                .le(SubmissionEntity::getSubmittedAtContestMillis, atMillis)
                .eq(SubmissionEntity::getVisibleToParticipant, true)
                .orderByAsc(SubmissionEntity::getSubmittedAtContestMillis)
                .orderByAsc(SubmissionEntity::getId));
    }

    private void assertCanManage(ContestEntity contest) {
        if (!canManage(contest)) {
            throw new DomainException(ErrorCode.FORBIDDEN, "Cannot manage contest resolver");
        }
    }

    private boolean canManage(ContestEntity contest) {
        return SecuritySupport.hasRole(Role.ADMIN)
                || (SecuritySupport.hasRole(Role.TEACHER) && Objects.equals(contest.getOwnerUserId(), SecuritySupport.currentUserId()));
    }

    private void assertCanReadSession(ContestEntity contest, ContestRunEntity run, ContestResolverSessionEntity session) {
        if (canManage(contest)) {
            return;
        }
        if (session.getStatus() != ContestResolverSessionStatus.PUBLISHED) {
            throw new DomainException(ErrorCode.FORBIDDEN, "Resolver session is not published");
        }
        assertCanReadPublished(contest, run);
    }

    private void assertCanReadPublished(ContestEntity contest, ContestRunEntity run) {
        if (contest.getStatus() != ContestStatus.PUBLISHED || run.getStatus() == ContestRunStatus.ARCHIVED) {
            throw new DomainException(ErrorCode.FORBIDDEN, "Cannot access resolver session");
        }
        if (Instant.now().isBefore(run.getEndAt())) {
            throw new DomainException(ErrorCode.FORBIDDEN, "Resolver session is available after the run ends");
        }
        Long userId = SecuritySupport.currentUserId();
        long participants = contestParticipantMapper.selectCount(new LambdaQueryWrapper<ContestParticipantEntity>()
                .eq(ContestParticipantEntity::getContestRunId, run.getId())
                .eq(ContestParticipantEntity::getUserId, userId)
                .eq(ContestParticipantEntity::getStatus, ContestParticipantStatus.ACTIVE));
        if (participants <= 0) {
            throw new DomainException(ErrorCode.FORBIDDEN, "Cannot access resolver session");
        }
    }

    private ContestResolverSessionResponse toSessionResponse(ContestResolverSessionEntity entity) {
        return new ContestResolverSessionResponse(entity.getId(), entity.getContestId(), entity.getContestRunId(),
                entity.getStatus(), entity.getTitle(), entity.getViewType(), entity.getFreezeSnapshotId(),
                entity.getFinalSnapshotId(), entity.getStepCount(), entity.getChecksum(), entity.getCreatedBy(),
                entity.getPublishedAt(), entity.getArchivedAt(), entity.getStatusBeforeArchive(), entity.getDeletedAt(),
                entity.getDeletedBy(), entity.getCreatedAt(), entity.getUpdatedAt());
    }

    private ContestResolverStepResponse toStepResponse(ContestResolverStepEntity entity) {
        return new ContestResolverStepResponse(entity.getId(), entity.getResolverSessionId(), entity.getContestId(),
                entity.getContestRunId(), entity.getStepOrder(), entity.getStepType(), entity.getParticipantId(),
                entity.getContestProblemId(), entity.getSubmissionId(), entity.getPayloadJson(),
                readScoreboard(entity.getScoreboardJson()), entity.getCreatedAt());
    }

    private ContestScoreboardResponse readScoreboard(String json) {
        try {
            return objectMapper.readValue(json, ContestScoreboardResponse.class);
        } catch (JsonProcessingException e) {
            throw new DomainException(ErrorCode.INTERNAL_ERROR, "Resolver scoreboard payload cannot be read");
        }
    }

    private String writeJson(Object value) {
        try {
            return objectMapper.writeValueAsString(value);
        } catch (JsonProcessingException e) {
            throw new DomainException(ErrorCode.INTERNAL_ERROR, "Resolver payload cannot be written");
        }
    }

    private String checksumFor(String value) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            byte[] hash = digest.digest(value.getBytes(StandardCharsets.UTF_8));
            StringBuilder builder = new StringBuilder(hash.length * 2);
            for (byte b : hash) {
                builder.append(String.format("%02x", b));
            }
            return builder.toString();
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }

    private String resolveTitle(ContestResolverSessionCreateRequest request, ContestRunEntity run) {
        if (request != null && request.title() != null && !request.title().isBlank()) {
            return request.title().trim();
        }
        return run.getTitle() + " Resolver";
    }

    private ContestMode effectiveMode(ContestEntity contest, ContestRunEntity run) {
        return run.getModeSnapshot() != null ? run.getModeSnapshot() : contest.getMode();
    }

    private int effectivePenaltyMinutes(ContestEntity contest, ContestRunEntity run) {
        return run.getPenaltyMinutesSnapshot() != null ? run.getPenaltyMinutesSnapshot()
                : contest.getPenaltyMinutes() == null ? 20 : contest.getPenaltyMinutes();
    }

    private boolean effectiveCePenalty(ContestEntity contest, ContestRunEntity run) {
        return run.getCePenaltySnapshot() != null ? Boolean.TRUE.equals(run.getCePenaltySnapshot())
                : Boolean.TRUE.equals(contest.getCePenalty());
    }

    private long durationMillis(ContestRunEntity run) {
        return Math.max(0L, Duration.between(run.getStartAt(), run.getEndAt()).toMillis());
    }

    private int safeInt(Integer value) {
        return value == null ? 0 : value;
    }

    private long safeLong(Long value) {
        return value == null ? 0L : value;
    }

    private int frozenRank(Map<Long, ContestScoreboardRowResponse> rows, Long participantId) {
        ContestScoreboardRowResponse row = rows.get(participantId);
        return row == null ? Integer.MAX_VALUE : row.rank();
    }

    private boolean isPenaltyStatus(SubmissionStatus status, boolean cePenalty) {
        return status == SubmissionStatus.WRONG_ANSWER
                || status == SubmissionStatus.RUNTIME_ERROR
                || status == SubmissionStatus.TIME_LIMIT_EXCEEDED
                || status == SubmissionStatus.MEMORY_LIMIT_EXCEEDED
                || status == SubmissionStatus.OUTPUT_LIMIT_EXCEEDED
                || (cePenalty && status == SubmissionStatus.COMPILE_ERROR);
    }

    private boolean isPendingStatus(SubmissionStatus status) {
        return status == SubmissionStatus.QUEUED || status == SubmissionStatus.RUNNING || status == SubmissionStatus.SYSTEM_ERROR;
    }

    private record ResolverReveal(SubmissionEntity submission, Set<Long> revealedSubmissionIds) {
    }

    private record CellKey(Long participantId, Long contestProblemId) {
    }
}
