package com.aioj.next.problem.domain;

import com.aioj.next.common.error.DomainException;
import com.aioj.next.common.error.ErrorCode;
import com.aioj.next.common.security.Role;
import com.aioj.next.common.security.SecuritySupport;
import com.aioj.next.contract.contest.ContestMode;
import com.aioj.next.contract.contest.ContestParticipantStatus;
import com.aioj.next.contract.contest.ContestProblemScoringMode;
import com.aioj.next.contract.contest.ContestRegistrationAccess;
import com.aioj.next.contract.contest.ContestScoreboardCellResponse;
import com.aioj.next.contract.contest.ContestScoreboardCellStatus;
import com.aioj.next.contract.contest.ContestScoreboardProblemResponse;
import com.aioj.next.contract.contest.ContestScoreboardResponse;
import com.aioj.next.contract.contest.ContestScoreboardRowResponse;
import com.aioj.next.contract.contest.ContestScoreboardSnapshotCreateRequest;
import com.aioj.next.contract.contest.ContestScoreboardSnapshotKind;
import com.aioj.next.contract.contest.ContestScoreboardSnapshotResponse;
import com.aioj.next.contract.contest.ContestScoreboardTimelineResponse;
import com.aioj.next.contract.contest.ContestScoreboardTimelineStatus;
import com.aioj.next.contract.contest.ContestScoreboardTimelineTickResponse;
import com.aioj.next.contract.contest.ContestScoreboardView;
import com.aioj.next.contract.contest.ContestStatus;
import com.aioj.next.contract.contest.ContestVisibility;
import com.aioj.next.contract.contest.ContestRegistrationPolicy;
import com.aioj.next.contract.contest.ContestRunResponse;
import com.aioj.next.contract.contest.ContestRunStatus;
import com.aioj.next.contract.learning.LearningGroupMemberRole;
import com.aioj.next.contract.operation.OperationJobResponse;
import com.aioj.next.contract.operation.OperationJobStatus;
import com.aioj.next.contract.submission.SubmissionStatus;
import com.aioj.next.problem.persistence.entity.ContestEntity;
import com.aioj.next.problem.persistence.entity.ContestParticipantEntity;
import com.aioj.next.problem.persistence.entity.ContestProblemEntity;
import com.aioj.next.problem.persistence.entity.ContestProblemScoringRuleEntity;
import com.aioj.next.problem.persistence.entity.ContestRunAllowedGroupEntity;
import com.aioj.next.problem.persistence.entity.ContestRunEntity;
import com.aioj.next.problem.persistence.entity.ContestScoreboardCellEntity;
import com.aioj.next.problem.persistence.entity.ContestScoreboardRowEntity;
import com.aioj.next.problem.persistence.entity.ContestScoreboardSnapshotEntity;
import com.aioj.next.problem.persistence.entity.ContestScoreboardTimelineTickEntity;
import com.aioj.next.problem.persistence.entity.LearningGroupMemberEntity;
import com.aioj.next.problem.persistence.entity.ProblemSubtaskEntity;
import com.aioj.next.problem.persistence.entity.SubmissionCaseResultEntity;
import com.aioj.next.problem.persistence.entity.SubmissionEntity;
import com.aioj.next.problem.persistence.mapper.ContestMapper;
import com.aioj.next.problem.persistence.mapper.ContestParticipantMapper;
import com.aioj.next.problem.persistence.mapper.ContestProblemMapper;
import com.aioj.next.problem.persistence.mapper.ContestProblemScoringRuleMapper;
import com.aioj.next.problem.persistence.mapper.ContestRunAllowedGroupMapper;
import com.aioj.next.problem.persistence.mapper.ContestRunMapper;
import com.aioj.next.problem.persistence.mapper.ContestScoreboardCellMapper;
import com.aioj.next.problem.persistence.mapper.ContestScoreboardRowMapper;
import com.aioj.next.problem.persistence.mapper.ContestScoreboardSnapshotMapper;
import com.aioj.next.problem.persistence.mapper.ContestScoreboardTimelineTickMapper;
import com.aioj.next.problem.persistence.mapper.LearningGroupMemberMapper;
import com.aioj.next.problem.persistence.mapper.ProblemSubtaskMapper;
import com.aioj.next.problem.persistence.mapper.SubmissionCaseResultMapper;
import com.aioj.next.problem.persistence.mapper.SubmissionMapper;
import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.context.annotation.Lazy;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.math.RoundingMode;
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
import java.util.NavigableMap;
import java.util.NavigableSet;
import java.util.Set;
import java.util.TreeMap;
import java.util.TreeSet;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;
import java.util.concurrent.locks.ReentrantLock;
import java.util.function.Supplier;

@Service
public class ContestScoreboardService {
    private static final int SCORING_VERSION = 1;
    private static final long MINUTE_MILLIS = 60_000L;
    private static final BigDecimal ZERO_SCORE = BigDecimal.ZERO;

    private final ContestMapper contestMapper;
    private final ContestRunMapper contestRunMapper;
    private final ContestRunAllowedGroupMapper allowedGroupMapper;
    private final ContestProblemMapper contestProblemMapper;
    private final ContestProblemScoringRuleMapper scoringRuleMapper;
    private final ContestParticipantMapper contestParticipantMapper;
    private final SubmissionMapper submissionMapper;
    private final SubmissionCaseResultMapper caseResultMapper;
    private final ProblemSubtaskMapper subtaskMapper;
    private final LearningGroupMemberMapper learningGroupMemberMapper;
    private final ContestScoreboardSnapshotMapper snapshotMapper;
    private final ContestScoreboardRowMapper rowMapper;
    private final ContestScoreboardCellMapper cellMapper;
    private final ContestScoreboardTimelineTickMapper timelineTickMapper;
    private final OperationJobService operationJobService;
    private final ObjectMapper objectMapper;
    private final ContestProblemVisibilityService visibilityService;
    private final ConcurrentMap<String, ReentrantLock> timelineLocks = new ConcurrentHashMap<>();

    public ContestScoreboardService(ContestMapper contestMapper,
                                    ContestRunMapper contestRunMapper,
                                    ContestRunAllowedGroupMapper allowedGroupMapper,
                                    ContestProblemMapper contestProblemMapper,
                                    ContestProblemScoringRuleMapper scoringRuleMapper,
                                    ContestParticipantMapper contestParticipantMapper,
                                    SubmissionMapper submissionMapper,
                                    SubmissionCaseResultMapper caseResultMapper,
                                    ProblemSubtaskMapper subtaskMapper,
                                    LearningGroupMemberMapper learningGroupMemberMapper,
                                    ContestScoreboardSnapshotMapper snapshotMapper,
                                    ContestScoreboardRowMapper rowMapper,
                                    ContestScoreboardCellMapper cellMapper,
                                    ContestScoreboardTimelineTickMapper timelineTickMapper,
                                    @Lazy OperationJobService operationJobService,
                                    ObjectMapper objectMapper,
                                    ContestProblemVisibilityService visibilityService) {
        this.contestMapper = contestMapper;
        this.contestRunMapper = contestRunMapper;
        this.allowedGroupMapper = allowedGroupMapper;
        this.contestProblemMapper = contestProblemMapper;
        this.scoringRuleMapper = scoringRuleMapper;
        this.contestParticipantMapper = contestParticipantMapper;
        this.submissionMapper = submissionMapper;
        this.caseResultMapper = caseResultMapper;
        this.subtaskMapper = subtaskMapper;
        this.learningGroupMemberMapper = learningGroupMemberMapper;
        this.snapshotMapper = snapshotMapper;
        this.rowMapper = rowMapper;
        this.cellMapper = cellMapper;
        this.timelineTickMapper = timelineTickMapper;
        this.operationJobService = operationJobService;
        this.objectMapper = objectMapper;
        this.visibilityService = visibilityService;
    }

    public ContestScoreboardResponse scoreboard(Long contestId, Long runId, ContestScoreboardView view, Long atMillis, Long snapshotId) {
        if (snapshotId != null) {
            return snapshot(contestId, snapshotId);
        }
        ContestEntity contest = requireContest(contestId);
        ContestRunEntity run = runId == null ? null : requireRun(contestId, runId);
        requireConcreteScoreboardScope(contest, run);
        ContestScoreboardView effectiveView = view == null ? ContestScoreboardView.PUBLIC : view;
        assertCanViewScoreboard(contest, run, effectiveView);
        long requestedAt = normalizeAtMillis(contest, run, atMillis, Instant.now());
        ContestScoreboardTimelineTickEntity exactTick = run == null || atMillis == null
                ? null
                : findTimelineTick(run.getId(), effectiveView, requestedAt);
        if (exactTick != null && exactTick.getSnapshotId() != null) {
            return snapshot(contestId, exactTick.getSnapshotId());
        }
        return compute(contest, run, effectiveView, requestedAt, null, null, Instant.now());
    }

    public ContestScoreboardResponse scoreboard(Long contestId, ContestScoreboardView view, Long atMillis, Long snapshotId) {
        return scoreboard(contestId, null, view, atMillis, snapshotId);
    }

    public List<ContestScoreboardSnapshotResponse> listSnapshots(Long contestId, Long runId) {
        ContestEntity contest = requireContest(contestId);
        assertCanManage(contest);
        return snapshotMapper.selectList(new LambdaQueryWrapper<ContestScoreboardSnapshotEntity>()
                        .eq(ContestScoreboardSnapshotEntity::getContestId, contestId)
                        .eq(runId != null, ContestScoreboardSnapshotEntity::getContestRunId, runId)
                        .orderByDesc(ContestScoreboardSnapshotEntity::getCreatedAt)
                        .orderByDesc(ContestScoreboardSnapshotEntity::getId))
                .stream()
                .map(this::toSnapshotResponse)
                .toList();
    }

    public List<ContestScoreboardSnapshotResponse> listSnapshots(Long contestId) {
        return listSnapshots(contestId, null);
    }

    public ContestScoreboardResponse snapshot(Long contestId, Long snapshotId) {
        ContestEntity contest = requireContest(contestId);
        ContestScoreboardSnapshotEntity snapshot = snapshotMapper.selectById(snapshotId);
        if (snapshot == null || !contestId.equals(snapshot.getContestId())) {
            throw new DomainException(ErrorCode.NOT_FOUND, "Scoreboard snapshot not found");
        }
        ContestRunEntity run = snapshot.getContestRunId() == null ? null : requireRun(contestId, snapshot.getContestRunId());
        assertCanViewScoreboard(contest, run, snapshot.getViewType());
        SnapshotPayload payload = readPayload(snapshot.getRowsJson());
        return new ContestScoreboardResponse(contestId, snapshot.getContestRunId(), effectiveMode(contest, run), snapshot.getViewType(), snapshot.getId(),
                snapshot.getSnapshotKind(), snapshot.getContestTimeMillis(), snapshot.getSnapshotAt(),
                Boolean.TRUE.equals(snapshot.getFrozen()), payload.freezeAtContestMillis(), payload.penaltyMinutes(),
                payload.cePenalty(), payload.problems(), payload.rows());
    }

    @Transactional
    public ContestScoreboardResponse createSnapshot(Long contestId, Long runId, ContestScoreboardSnapshotCreateRequest request) {
        ContestEntity contest = requireContest(contestId);
        assertCanManage(contest);
        ContestRunEntity run = runId == null ? null : requireRun(contestId, runId);
        requireConcreteScoreboardScope(contest, run);
        ContestScoreboardSnapshotKind kind = request == null || request.snapshotKind() == null
                ? ContestScoreboardSnapshotKind.MANUAL
                : request.snapshotKind();
        ContestScoreboardView view = request == null || request.view() == null
                ? defaultSnapshotView(kind)
                : request.view();
        long atMillis = kind == ContestScoreboardSnapshotKind.FINAL
                ? contestDurationMillis(contest, run)
                : normalizeAtMillis(contest, run, request == null ? null : request.atMillis(), Instant.now());
        ContestScoreboardResponse computed = compute(contest, run, view, atMillis, kind, null, Instant.now());
        return persistSnapshot(computed, kind, view);
    }

    public ContestScoreboardResponse createSnapshot(Long contestId, ContestScoreboardSnapshotCreateRequest request) {
        return createSnapshot(contestId, null, request);
    }

    @Transactional
    public ContestScoreboardResponse finalizeContest(Long contestId, Long runId) {
        ContestEntity contest = requireContest(contestId);
        assertCanManage(contest);
        ContestRunEntity run = runId == null ? null : requireRun(contestId, runId);
        requireConcreteScoreboardScope(contest, run);
        Instant now = Instant.now();
        if (now.isBefore(endAt(contest, run))) {
            throw new DomainException(ErrorCode.BAD_REQUEST, "Contest cannot be finalized before it ends");
        }
        long unfinished = submissionMapper.selectCount(new LambdaQueryWrapper<SubmissionEntity>()
                .eq(SubmissionEntity::getContestId, contestId)
                .eq(runId != null, SubmissionEntity::getContestRunId, runId)
                .in(SubmissionEntity::getStatus, SubmissionStatus.QUEUED, SubmissionStatus.RUNNING, SubmissionStatus.SYSTEM_ERROR));
        if (unfinished > 0) {
            throw new DomainException(ErrorCode.BAD_REQUEST, "Contest has unfinished or system-error submissions");
        }
        ContestScoreboardResponse computed = compute(contest, run, ContestScoreboardView.PRIVATE, contestDurationMillis(contest, run),
                ContestScoreboardSnapshotKind.FINAL, null, now);
        return persistSnapshot(computed, ContestScoreboardSnapshotKind.FINAL, ContestScoreboardView.PRIVATE);
    }

    public ContestScoreboardResponse finalizeContest(Long contestId) {
        return finalizeContest(contestId, null);
    }

    @Transactional
    public ContestRunResponse unfreezePublicScoreboard(Long contestId, Long runId) {
        ContestEntity contest = requireContest(contestId);
        assertCanManage(contest);
        ContestRunEntity run = requireRun(contestId, runId);
        if (run.getFreezeAt() == null) {
            throw new DomainException(ErrorCode.BAD_REQUEST, "Contest run has no scoreboard freeze");
        }
        assertRunFinishedAndSettled(contestId, run);
        Instant now = Instant.now();
        run.setPublicScoreboardUnfrozenAt(now);
        run.setPublicScoreboardUnfrozenBy(SecuritySupport.currentUserId());
        run.setUpdatedAt(now);
        contestRunMapper.updateById(run);
        clearPublicTimeline(runId);
        return toRunResponse(run);
    }

    @Transactional
    public ContestRunResponse refreezePublicScoreboard(Long contestId, Long runId) {
        ContestEntity contest = requireContest(contestId);
        assertCanManage(contest);
        ContestRunEntity run = requireRun(contestId, runId);
        Instant now = Instant.now();
        run.setPublicScoreboardUnfrozenAt(null);
        run.setPublicScoreboardUnfrozenBy(null);
        run.setUpdatedAt(now);
        contestRunMapper.updateById(run);
        clearPublicTimeline(runId);
        return toRunResponse(run);
    }

    @Transactional
    public ContestScoreboardTimelineResponse timeline(Long contestId, Long runId, ContestScoreboardView view) {
        ContestEntity contest = requireContest(contestId);
        ContestRunEntity run = requireRun(contestId, runId);
        ContestScoreboardView effectiveView = view == null ? ContestScoreboardView.PUBLIC : view;
        assertCanViewScoreboard(contest, run, effectiveView);
        List<ContestScoreboardTimelineTickEntity> existing = listTimelineTicks(runId, effectiveView);
        if (timelineStale(contestId, runId, existing)) {
            clearTimeline(runId, effectiveView);
            existing = List.of();
        }
        int expectedTicks = expectedTimelineTickCount(contest, run);
        if (timelineComplete(existing, expectedTicks)) {
            return new ContestScoreboardTimelineResponse(ContestScoreboardTimelineStatus.READY,
                    existing.stream().map(this::toTimelineResponse).toList(), null,
                    expectedTicks, expectedTicks, "Ready");
        }
        OperationJobResponse job = operationJobService.findOrCreateScoreboardTimelineJob(contestId, runId, effectiveView);
        ContestScoreboardTimelineStatus status = existing.isEmpty()
                ? ContestScoreboardTimelineStatus.GENERATING
                : ContestScoreboardTimelineStatus.PARTIAL;
        if (job != null && job.status() == OperationJobStatus.FAILED) {
            status = ContestScoreboardTimelineStatus.FAILED;
        }
        int progressCurrent = job == null || job.progressCurrent() == null ? existing.size() : job.progressCurrent();
        int progressTotal = job == null || job.progressTotal() == null ? expectedTicks : job.progressTotal();
        String message = job == null || job.progressMessage() == null ? "Generating scoreboard timeline" : job.progressMessage();
        return new ContestScoreboardTimelineResponse(status,
                existing.stream().map(this::toTimelineResponse).toList(),
                job == null ? null : job.id(), progressCurrent, progressTotal, message);
    }

    @Transactional
    public int generateTimelineForOperation(Long contestId, Long runId, ContestScoreboardView view,
                                            TimelineProgressCallback progressCallback) {
        ContestEntity contest = requireContest(contestId);
        ContestRunEntity run = requireRun(contestId, runId);
        ContestScoreboardView effectiveView = view == null ? ContestScoreboardView.PUBLIC : view;
        assertCanViewScoreboard(contest, run, effectiveView);
        return withTimelineLock(runId, effectiveView, () ->
                generateTimelineLocked(contest, run, effectiveView, progressCallback));
    }

    private int generateTimelineLocked(ContestEntity contest, ContestRunEntity run, ContestScoreboardView view,
                                       TimelineProgressCallback progressCallback) {
        List<ContestScoreboardTimelineTickEntity> existing = listTimelineTicks(run.getId(), view);
        int expectedTicks = expectedTimelineTickCount(contest, run);
        if (timelineComplete(existing, expectedTicks) && !timelineStale(contest.getId(), run.getId(), existing)) {
            reportProgress(progressCallback, expectedTicks, expectedTicks, "Ready");
            return expectedTicks;
        }

        clearTimeline(run.getId(), view);
        long duration = contestDurationMillis(contest, run);
        long lastBucket = lastTimelineBucket(duration);
        NavigableSet<Long> changeBuckets = timelineChangeBuckets(contest, run, view, duration, lastBucket);
        NavigableMap<Long, TimelineSnapshotRef> snapshotByChangeBucket = new TreeMap<>();
        TimelineSnapshotRef lastRef = null;
        String lastChecksum = null;
        Instant generationStartedAt = Instant.now();
        reportProgress(progressCallback, 0, expectedTicks, "Computing changed scoreboard minutes");
        for (Long bucket : changeBuckets) {
            ContestScoreboardResponse computed = compute(contest, run, view, bucket,
                    ContestScoreboardSnapshotKind.MANUAL, null, Instant.now());
            String checksum = checksumFor(computed);
            TimelineSnapshotRef ref;
            if (lastRef != null && checksum.equals(lastChecksum)) {
                ref = lastRef;
            } else {
                Long snapshotId = persistSnapshot(computed, ContestScoreboardSnapshotKind.MANUAL,
                        view, SnapshotPersistMode.JSON_ONLY).snapshotId();
                ref = new TimelineSnapshotRef(snapshotId, checksum);
            }
            snapshotByChangeBucket.put(bucket, ref);
            lastRef = ref;
            lastChecksum = checksum;
        }

        int current = 0;
        for (long bucket = 0; bucket <= lastBucket; bucket += MINUTE_MILLIS) {
            Map.Entry<Long, TimelineSnapshotRef> floor = snapshotByChangeBucket.floorEntry(bucket);
            TimelineSnapshotRef ref = floor == null ? lastRef : floor.getValue();
            if (ref == null) {
                ContestScoreboardResponse computed = compute(contest, run, view, bucket,
                        ContestScoreboardSnapshotKind.MANUAL, null, Instant.now());
                ref = new TimelineSnapshotRef(persistSnapshot(computed, ContestScoreboardSnapshotKind.MANUAL,
                        view, SnapshotPersistMode.JSON_ONLY).snapshotId(), checksumFor(computed));
            }
            insertTimelineTick(contest.getId(), run.getId(), view, bucket, ref.snapshotId(), ref.checksum(), generationStartedAt);
            current++;
            if (current == expectedTicks || current % 10 == 0) {
                reportProgress(progressCallback, current, expectedTicks, "Writing scoreboard timeline");
            }
        }
        reportProgress(progressCallback, expectedTicks, expectedTicks, "Ready");
        return expectedTicks;
    }

    private NavigableSet<Long> timelineChangeBuckets(ContestEntity contest, ContestRunEntity run, ContestScoreboardView view,
                                                     long duration, long lastBucket) {
        NavigableSet<Long> buckets = new TreeSet<>();
        buckets.add(0L);
        buckets.add(lastBucket);
        long freezeMillis = freezeMillis(contest, run);
        if (view == ContestScoreboardView.PUBLIC && freezeMillis >= 0) {
            long freezeBucket = firstMinuteBucketAfter(freezeMillis, lastBucket);
            if (freezeBucket >= 0) {
                buckets.add(freezeBucket);
            }
        }
        for (SubmissionEntity submission : contestSubmissions(contest.getId(), run.getId(), duration)) {
            Long submittedAt = submission.getSubmittedAtContestMillis();
            if (submittedAt == null) {
                continue;
            }
            long bucket = firstMinuteBucketAtOrAfter(submittedAt, lastBucket);
            if (bucket >= 0) {
                buckets.add(bucket);
            }
        }
        return buckets;
    }

    private long firstMinuteBucketAtOrAfter(long millis, long lastBucket) {
        if (millis <= 0) {
            return 0;
        }
        long bucket = ((millis + MINUTE_MILLIS - 1) / MINUTE_MILLIS) * MINUTE_MILLIS;
        return bucket > lastBucket ? -1L : bucket;
    }

    private long firstMinuteBucketAfter(long millis, long lastBucket) {
        long bucket = ((Math.max(0, millis) / MINUTE_MILLIS) + 1) * MINUTE_MILLIS;
        return bucket > lastBucket ? -1L : bucket;
    }

    private <T> T withTimelineLock(Long runId, ContestScoreboardView view, Supplier<T> supplier) {
        String key = runId + ":" + view.name();
        ReentrantLock lock = timelineLocks.computeIfAbsent(key, ignored -> new ReentrantLock());
        lock.lock();
        try {
            return supplier.get();
        } finally {
            lock.unlock();
        }
    }

    private void reportProgress(TimelineProgressCallback callback, int current, int total, String message) {
        if (callback != null) {
            callback.update(current, Math.max(1, total), message);
        }
    }

    private ContestScoreboardResponse compute(ContestEntity contest, ContestScoreboardView view, long atMillis,
                                              ContestScoreboardSnapshotKind snapshotKind, Long snapshotId,
                                              Instant generatedAt) {
        return compute(contest, null, view, atMillis, snapshotKind, snapshotId, generatedAt);
    }

    private ContestScoreboardResponse compute(ContestEntity contest, ContestRunEntity run, ContestScoreboardView view, long atMillis,
                                              ContestScoreboardSnapshotKind snapshotKind, Long snapshotId,
                                              Instant generatedAt) {
        ContestMode mode = effectiveMode(contest, run);
        if (mode == ContestMode.IOI) {
            return computeIoi(contest, run, view, atMillis, snapshotKind, snapshotId, generatedAt);
        }
        if (mode != ContestMode.ACM) {
            throw new DomainException(ErrorCode.BAD_REQUEST, "Unsupported contest mode");
        }
        List<ContestProblemEntity> contestProblems = contestProblems(contest.getId());
        List<ContestParticipantEntity> participants = contestParticipants(contest.getId(), run == null ? null : run.getId());
        long freezeMillis = freezeMillis(contest, run);
        boolean frozen = view == ContestScoreboardView.PUBLIC && !publicScoreboardUnfrozen(run)
                && freezeMillis >= 0 && atMillis > freezeMillis;
        long scoringCutoff = frozen ? freezeMillis : atMillis;
        int penaltyMinutes = effectivePenaltyMinutes(contest, run);
        boolean cePenalty = effectiveCePenalty(contest, run);
        List<SubmissionEntity> submissions = contestSubmissions(contest.getId(), run == null ? null : run.getId(), atMillis);
        ScoreIndex scoreIndex = new ScoreIndex(submissions);
        List<ContestScoreboardProblemResponse> problemResponses = contestProblems.stream()
                .map(problem -> new ContestScoreboardProblemResponse(problem.getId(), problem.getProblemId(),
                        problem.getLabel(), problem.getDisplayTitle(), safeInt(problem.getScore()), safeInt(problem.getSortOrder())))
                .toList();
        List<ContestScoreboardRowResponse> unsorted = new ArrayList<>();
        for (ContestParticipantEntity participant : participants) {
            List<ContestScoreboardCellResponse> cells = new ArrayList<>();
            int solved = 0;
            int totalPenalty = 0;
            Long lastAccepted = null;
            for (ContestProblemEntity problem : contestProblems) {
                ContestScoreboardCellResponse cell = scoreCell(problem.getId(), scoreIndex.forCell(participant.getId(), problem.getId()),
                        scoringCutoff, frozen ? freezeMillis : -1L, atMillis, penaltyMinutes, cePenalty);
                cells.add(cell);
                if (cell.status() == ContestScoreboardCellStatus.SOLVED) {
                    solved++;
                    totalPenalty += cell.penaltyMinutes();
                    if (cell.acceptedAtMillis() != null) {
                        lastAccepted = lastAccepted == null ? cell.acceptedAtMillis() : Math.max(lastAccepted, cell.acceptedAtMillis());
                    }
                }
            }
            unsorted.add(new ContestScoreboardRowResponse(0, participant.getId(), participant.getUserId(),
                    participant.getAccountSnapshot(), participant.getDisplayNameSnapshot(), solved, totalPenalty,
                    lastAccepted, null, null, cells));
        }
        List<ContestScoreboardRowResponse> rows = rankRows(unsorted);
        return new ContestScoreboardResponse(contest.getId(), run == null ? null : run.getId(), mode, view, snapshotId,
                snapshotKind == null ? ContestScoreboardSnapshotKind.LIVE : snapshotKind, atMillis, generatedAt,
                frozen, freezeMillis >= 0 ? freezeMillis : null, penaltyMinutes, cePenalty, problemResponses, rows);
    }

    private ContestScoreboardResponse computeIoi(ContestEntity contest, ContestRunEntity run, ContestScoreboardView view, long atMillis,
                                                 ContestScoreboardSnapshotKind snapshotKind, Long snapshotId,
                                                 Instant generatedAt) {
        List<ContestProblemEntity> contestProblems = contestProblems(contest.getId());
        List<ContestParticipantEntity> participants = contestParticipants(contest.getId(), run == null ? null : run.getId());
        long freezeMillis = freezeMillis(contest, run);
        boolean frozen = view == ContestScoreboardView.PUBLIC && !publicScoreboardUnfrozen(run)
                && freezeMillis >= 0 && atMillis > freezeMillis;
        long scoringCutoff = frozen ? freezeMillis : atMillis;
        int penaltyMinutes = effectivePenaltyMinutes(contest, run);
        boolean cePenalty = effectiveCePenalty(contest, run);
        List<SubmissionEntity> submissions = contestSubmissions(contest.getId(), run == null ? null : run.getId(), atMillis);
        ScoreIndex scoreIndex = new ScoreIndex(submissions);
        List<ContestScoreboardProblemResponse> problemResponses = contestProblems.stream()
                .map(problem -> new ContestScoreboardProblemResponse(problem.getId(), problem.getProblemId(),
                        problem.getLabel(), problem.getDisplayTitle(), safeInt(problem.getScore()), safeInt(problem.getSortOrder())))
                .toList();
        List<ContestScoreboardRowResponse> unsorted = new ArrayList<>();
        for (ContestParticipantEntity participant : participants) {
            List<ContestScoreboardCellResponse> cells = new ArrayList<>();
            BigDecimal totalScore = ZERO_SCORE;
            int fullScoreProblems = 0;
            Long lastImprovedAt = null;
            for (ContestProblemEntity problem : contestProblems) {
                ContestScoreboardCellResponse cell = scoreIoiCell(problem, scoreIndex.forCell(participant.getId(), problem.getId()),
                        scoringMode(problem.getId()), scoringCutoff, frozen ? freezeMillis : -1L, atMillis);
                cells.add(cell);
                if (cell.score() != null) {
                    totalScore = totalScore.add(cell.score());
                }
                if (isFullScore(cell.score(), cell.maxScore())) {
                    fullScoreProblems++;
                }
                if (cell.lastScoreImprovedAtMillis() != null) {
                    lastImprovedAt = lastImprovedAt == null
                            ? cell.lastScoreImprovedAtMillis()
                            : Math.max(lastImprovedAt, cell.lastScoreImprovedAtMillis());
                }
            }
            unsorted.add(new ContestScoreboardRowResponse(0, participant.getId(), participant.getUserId(),
                    participant.getAccountSnapshot(), participant.getDisplayNameSnapshot(), fullScoreProblems, 0,
                    null, totalScore, lastImprovedAt, cells));
        }
        List<ContestScoreboardRowResponse> rows = rankIoiRows(unsorted);
        return new ContestScoreboardResponse(contest.getId(), run == null ? null : run.getId(), effectiveMode(contest, run), view, snapshotId,
                snapshotKind == null ? ContestScoreboardSnapshotKind.LIVE : snapshotKind, atMillis, generatedAt,
                frozen, freezeMillis >= 0 ? freezeMillis : null, penaltyMinutes, cePenalty, problemResponses, rows);
    }

    private ContestScoreboardCellResponse scoreIoiCell(ContestProblemEntity problem,
                                                       List<SubmissionEntity> submissions,
                                                       ContestProblemScoringMode scoringMode,
                                                       long scoringCutoff,
                                                       long freezeMillis,
                                                       long atMillis) {
        int attempts = 0;
        int pendingAttempts = 0;
        SubmissionEntity best = null;
        BigDecimal maxScore = contestProblemMaxScore(problem);
        BigDecimal bestScore = ZERO_SCORE;
        if (scoringMode == ContestProblemScoringMode.SUBTASK_MIN_CASE_MAX_OVER_SUBMISSIONS) {
            SubtaskAggregateScore subtaskScore = scoreIoiSubtasks(problem, submissions, scoringCutoff);
            best = subtaskScore.bestSubmission();
            bestScore = subtaskScore.score();
            maxScore = subtaskScore.maxScore();
        }
        for (SubmissionEntity submission : submissions) {
            long submittedAt = safeLong(submission.getSubmittedAtContestMillis());
            if (submittedAt > scoringCutoff) {
                continue;
            }
            attempts++;
            if (isPendingStatus(submission.getStatus())) {
                pendingAttempts++;
                continue;
            }
            if (scoringMode != ContestProblemScoringMode.SUBTASK_MIN_CASE_MAX_OVER_SUBMISSIONS) {
                BigDecimal submissionMax = firstNonNull(submission.getMaxScore(), maxScore);
                BigDecimal submissionScore = scoreForSubmission(submission, submissionMax);
                if (best == null
                        || submissionScore.compareTo(bestScore) > 0
                        || (submissionScore.compareTo(bestScore) == 0
                        && safeLong(submission.getSubmittedAtContestMillis()) < safeLong(best.getSubmittedAtContestMillis()))) {
                    best = submission;
                    bestScore = submissionScore;
                    maxScore = submissionMax;
                }
            }
        }
        int frozenPending = 0;
        if (freezeMillis >= 0) {
            for (SubmissionEntity submission : submissions) {
                long submittedAt = safeLong(submission.getSubmittedAtContestMillis());
                if (submittedAt > freezeMillis && submittedAt <= atMillis) {
                    frozenPending++;
                }
            }
        }
        attempts += frozenPending;
        pendingAttempts += frozenPending;
        ContestScoreboardCellStatus status = ContestScoreboardCellStatus.UNSOLVED;
        if (isFullScore(bestScore, maxScore)) {
            status = ContestScoreboardCellStatus.SOLVED;
        } else if (bestScore.compareTo(ZERO_SCORE) > 0 || attempts > 0) {
            status = ContestScoreboardCellStatus.ATTEMPTED;
        } else if (pendingAttempts > 0) {
            status = ContestScoreboardCellStatus.PENDING;
        }
        return new ContestScoreboardCellResponse(problem.getId(), status, attempts, 0, pendingAttempts,
                null, 0, bestScore, maxScore, best == null ? null : best.getId(),
                best == null ? null : best.getSubmittedAtContestMillis());
    }

    private SubtaskAggregateScore scoreIoiSubtasks(ContestProblemEntity problem, List<SubmissionEntity> submissions,
                                                   long scoringCutoff) {
        Map<String, BigDecimal> bestSubtaskScores = new HashMap<>();
        Map<String, BigDecimal> subtaskMaxScores = new HashMap<>();
        SubmissionEntity bestSubmission = null;
        BigDecimal bestTotal = ZERO_SCORE;
        for (SubmissionEntity submission : submissions) {
            long submittedAt = safeLong(submission.getSubmittedAtContestMillis());
            if (submittedAt > scoringCutoff || isPendingStatus(submission.getStatus())) {
                continue;
            }
            List<SubmissionCaseResultEntity> caseResults = caseResultMapper.selectList(new LambdaQueryWrapper<SubmissionCaseResultEntity>()
                    .eq(SubmissionCaseResultEntity::getSubmissionId, submission.getId())
                    .orderByAsc(SubmissionCaseResultEntity::getCaseIndex)
                    .orderByAsc(SubmissionCaseResultEntity::getId));
            if (caseResults.isEmpty()) {
                continue;
            }
            List<ProblemSubtaskEntity> subtasks = subtasksForSubmission(caseResults);
            if (subtasks.isEmpty()) {
                BigDecimal submissionMax = firstNonNull(submission.getMaxScore(), contestProblemMaxScore(problem));
                BigDecimal submissionScore = scoreForSubmission(submission, submissionMax);
                if (submissionScore.compareTo(bestTotal) > 0) {
                    bestTotal = submissionScore;
                    bestSubmission = submission;
                    subtaskMaxScores.put("_legacy", submissionMax);
                    bestSubtaskScores.put("_legacy", submissionScore);
                }
                continue;
            }
            Map<String, BigDecimal> submissionSubtaskScores = scoreSubmissionSubtasks(subtasks, caseResults);
            boolean improved = false;
            for (ProblemSubtaskEntity subtask : subtasks) {
                BigDecimal subtaskMax = firstNonNull(subtask.getScore(), ZERO_SCORE);
                subtaskMaxScores.put(subtask.getSubtaskKey(), subtaskMax);
                BigDecimal score = firstNonNull(submissionSubtaskScores.get(subtask.getSubtaskKey()), ZERO_SCORE);
                BigDecimal current = firstNonNull(bestSubtaskScores.get(subtask.getSubtaskKey()), ZERO_SCORE);
                if (score.compareTo(current) > 0) {
                    bestSubtaskScores.put(subtask.getSubtaskKey(), score);
                    improved = true;
                }
            }
            BigDecimal aggregate = sumScores(bestSubtaskScores.values());
            if (improved && (bestSubmission == null || aggregate.compareTo(bestTotal) >= 0)) {
                bestSubmission = submission;
                bestTotal = aggregate;
            }
        }
        return new SubtaskAggregateScore(bestSubmission, sumScores(bestSubtaskScores.values()),
                sumScores(subtaskMaxScores.values()));
    }

    private List<ProblemSubtaskEntity> subtasksForSubmission(List<SubmissionCaseResultEntity> caseResults) {
        Long packageId = caseResults.stream()
                .map(SubmissionCaseResultEntity::getTestcasePackageId)
                .filter(id -> id != null)
                .findFirst()
                .orElse(null);
        if (packageId == null) {
            return List.of();
        }
        return subtaskMapper.selectList(new LambdaQueryWrapper<ProblemSubtaskEntity>()
                .eq(ProblemSubtaskEntity::getTestcasePackageId, packageId)
                .orderByAsc(ProblemSubtaskEntity::getSortOrder)
                .orderByAsc(ProblemSubtaskEntity::getId));
    }

    private Map<String, BigDecimal> scoreSubmissionSubtasks(List<ProblemSubtaskEntity> subtasks,
                                                            List<SubmissionCaseResultEntity> caseResults) {
        Map<String, List<SubmissionCaseResultEntity>> casesBySubtask = caseResults.stream()
                .filter(result -> result.getSubtaskKey() != null)
                .collect(java.util.stream.Collectors.groupingBy(SubmissionCaseResultEntity::getSubtaskKey));
        Map<String, BigDecimal> scores = new HashMap<>();
        for (ProblemSubtaskEntity subtask : subtasks) {
            List<SubmissionCaseResultEntity> subtaskCases = casesBySubtask.getOrDefault(subtask.getSubtaskKey(), List.of());
            if (subtaskCases.isEmpty()) {
                scores.put(subtask.getSubtaskKey(), ZERO_SCORE);
                continue;
            }
            BigDecimal minRatio = BigDecimal.ONE;
            for (SubmissionCaseResultEntity testcase : subtaskCases) {
                BigDecimal caseScore = firstNonNull(testcase.getScore(), ZERO_SCORE);
                BigDecimal caseMax = firstNonNull(testcase.getMaxScore(), ZERO_SCORE);
                BigDecimal ratio = caseMax.compareTo(ZERO_SCORE) <= 0
                        ? ZERO_SCORE
                        : caseScore.divide(caseMax, 8, RoundingMode.HALF_UP);
                if (ratio.compareTo(minRatio) < 0) {
                    minRatio = ratio;
                }
            }
            scores.put(subtask.getSubtaskKey(), minRatio.multiply(firstNonNull(subtask.getScore(), ZERO_SCORE)));
        }
        return scores;
    }

    private List<ContestScoreboardRowResponse> rankIoiRows(List<ContestScoreboardRowResponse> rows) {
        List<ContestScoreboardRowResponse> sorted = rows.stream()
                .sorted(Comparator.comparing((ContestScoreboardRowResponse row) -> nullToZeroScore(row.totalScore())).reversed()
                        .thenComparing(row -> row.lastScoreImprovedAtMillis() == null
                                ? Long.MAX_VALUE
                                : row.lastScoreImprovedAtMillis())
                        .thenComparing(ContestScoreboardRowResponse::displayNameSnapshot)
                        .thenComparing(ContestScoreboardRowResponse::participantId))
                .toList();
        List<ContestScoreboardRowResponse> ranked = new ArrayList<>();
        int rank = 0;
        ContestScoreboardRowResponse previous = null;
        for (int index = 0; index < sorted.size(); index++) {
            ContestScoreboardRowResponse row = sorted.get(index);
            if (previous == null || !sameIoiScore(previous, row)) {
                rank = index + 1;
            }
            ranked.add(new ContestScoreboardRowResponse(rank, row.participantId(), row.userId(),
                    row.accountSnapshot(), row.displayNameSnapshot(), row.solvedCount(), row.penaltyMinutes(),
                    row.lastAcceptedAtMillis(), row.totalScore(), row.lastScoreImprovedAtMillis(), row.cells()));
            previous = row;
        }
        return ranked;
    }

    private boolean sameIoiScore(ContestScoreboardRowResponse left, ContestScoreboardRowResponse right) {
        long leftLast = left.lastScoreImprovedAtMillis() == null ? Long.MAX_VALUE : left.lastScoreImprovedAtMillis();
        long rightLast = right.lastScoreImprovedAtMillis() == null ? Long.MAX_VALUE : right.lastScoreImprovedAtMillis();
        return nullToZeroScore(left.totalScore()).compareTo(nullToZeroScore(right.totalScore())) == 0
                && leftLast == rightLast;
    }

    private ContestScoreboardCellResponse scoreCell(Long contestProblemId, List<SubmissionEntity> submissions, long scoringCutoff,
                                                    long freezeMillis, long atMillis, int penaltyMinutes,
                                                    boolean cePenalty) {
        int attempts = 0;
        int wrongAttempts = 0;
        int pendingAttempts = 0;
        Long acceptedAt = null;
        for (SubmissionEntity submission : submissions) {
            long submittedAt = safeLong(submission.getSubmittedAtContestMillis());
            if (submittedAt > scoringCutoff) {
                continue;
            }
            if (acceptedAt != null) {
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
        int frozenPending = 0;
        if (freezeMillis >= 0) {
            for (SubmissionEntity submission : submissions) {
                long submittedAt = safeLong(submission.getSubmittedAtContestMillis());
                if (submittedAt > freezeMillis && submittedAt <= atMillis) {
                    frozenPending++;
                }
            }
        }
        pendingAttempts += frozenPending;
        attempts += frozenPending;
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

    private ContestScoreboardResponse persistSnapshot(ContestScoreboardResponse response,
                                                      ContestScoreboardSnapshotKind kind,
                                                      ContestScoreboardView view) {
        return persistSnapshot(response, kind, view, SnapshotPersistMode.FULL);
    }

    private ContestScoreboardResponse persistSnapshot(ContestScoreboardResponse response,
                                                      ContestScoreboardSnapshotKind kind,
                                                      ContestScoreboardView view,
                                                      SnapshotPersistMode mode) {
        SnapshotPayload payload = new SnapshotPayload(response.penaltyMinutes(), response.cePenalty(),
                response.freezeAtContestMillis(), response.problems(), response.rows());
        String json = writePayload(payload);
        Instant now = Instant.now();
        ContestScoreboardSnapshotEntity snapshot = new ContestScoreboardSnapshotEntity();
        snapshot.setContestId(response.contestId());
        snapshot.setContestRunId(response.contestRunId());
        snapshot.setSnapshotKind(kind);
        snapshot.setViewType(view);
        snapshot.setSnapshotAt(now);
        snapshot.setContestTimeMillis(response.atContestMillis());
        snapshot.setScoringVersion(SCORING_VERSION);
        snapshot.setFrozen(response.frozen());
        snapshot.setRowsJson(json);
        snapshot.setChecksum(sha256(json));
        snapshot.setCreatedBy(SecuritySupport.currentUserId());
        snapshot.setCreatedAt(now);
        snapshotMapper.insert(snapshot);
        if (mode == SnapshotPersistMode.JSON_ONLY) {
            return new ContestScoreboardResponse(response.contestId(), response.contestRunId(), response.mode(), view, snapshot.getId(), kind,
                    response.atContestMillis(), snapshot.getSnapshotAt(), response.frozen(), response.freezeAtContestMillis(),
                    response.penaltyMinutes(), response.cePenalty(), response.problems(), response.rows());
        }
        for (ContestScoreboardRowResponse row : response.rows()) {
            ContestScoreboardRowEntity rowEntity = new ContestScoreboardRowEntity();
            rowEntity.setSnapshotId(snapshot.getId());
            rowEntity.setContestId(response.contestId());
            rowEntity.setContestRunId(response.contestRunId());
            rowEntity.setParticipantId(row.participantId());
            rowEntity.setRankNo(row.rank());
            rowEntity.setAccountSnapshot(row.accountSnapshot());
            rowEntity.setDisplayNameSnapshot(row.displayNameSnapshot());
            rowEntity.setSolvedCount(row.solvedCount());
            rowEntity.setPenaltyMinutes(row.penaltyMinutes());
            rowEntity.setLastAcceptedAtMillis(row.lastAcceptedAtMillis());
            rowEntity.setTotalScore(row.totalScore());
            rowEntity.setLastScoreImprovedAtMillis(row.lastScoreImprovedAtMillis());
            rowEntity.setCreatedAt(now);
            rowMapper.insert(rowEntity);
            for (ContestScoreboardCellResponse cell : row.cells()) {
                if (cell.contestProblemId() == null) {
                    continue;
                }
                ContestScoreboardCellEntity cellEntity = new ContestScoreboardCellEntity();
                cellEntity.setSnapshotId(snapshot.getId());
                cellEntity.setRowId(rowEntity.getId());
                cellEntity.setContestId(response.contestId());
                cellEntity.setContestRunId(response.contestRunId());
                cellEntity.setParticipantId(row.participantId());
                cellEntity.setContestProblemId(cell.contestProblemId());
                cellEntity.setStatus(cell.status());
                cellEntity.setAttempts(cell.attempts());
                cellEntity.setWrongAttempts(cell.wrongAttempts());
                cellEntity.setPendingAttempts(cell.pendingAttempts());
                cellEntity.setAcceptedAtMillis(cell.acceptedAtMillis());
                cellEntity.setPenaltyMinutes(cell.penaltyMinutes());
                cellEntity.setScore(cell.score());
                cellEntity.setMaxScore(cell.maxScore());
                cellEntity.setBestSubmissionId(cell.bestSubmissionId());
                cellEntity.setLastScoreImprovedAtMillis(cell.lastScoreImprovedAtMillis());
                cellEntity.setCreatedAt(now);
                cellMapper.insert(cellEntity);
            }
        }
        return new ContestScoreboardResponse(response.contestId(), response.contestRunId(), response.mode(), view, snapshot.getId(), kind,
                response.atContestMillis(), snapshot.getSnapshotAt(), response.frozen(), response.freezeAtContestMillis(),
                response.penaltyMinutes(), response.cePenalty(), response.problems(), response.rows());
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

    private void assertCanViewScoreboard(ContestEntity contest, ContestRunEntity run, ContestScoreboardView view) {
        if (view == ContestScoreboardView.PRIVATE) {
            assertCanManage(contest);
            return;
        }
        if (canManage(contest)) {
            return;
        }
        if (contest.getStatus() == ContestStatus.PUBLISHED && run != null
                && run.getStatus() != ContestRunStatus.DRAFT
                && run.getStatus() != ContestRunStatus.ARCHIVED
                && !Instant.now().isBefore(run.getStartAt())
                && (effectiveRegistrationAccess(run) == ContestRegistrationAccess.PUBLIC
                || isAllowedGroupMember(run.getId(), SecuritySupport.currentUserId())
                || isRunParticipant(run.getId(), SecuritySupport.currentUserId()))) {
            return;
        }
        if (contest.getStatus() == ContestStatus.PUBLISHED
                && contest.getVisibility() == ContestVisibility.GROUP
                && isGroupMember(contest.getScopeGroupId(), SecuritySupport.currentUserId())) {
            return;
        }
        throw new DomainException(ErrorCode.FORBIDDEN, "Cannot access contest scoreboard");
    }

    private void assertCanManage(ContestEntity contest) {
        if (!canManage(contest)) {
            throw new DomainException(ErrorCode.FORBIDDEN, "Cannot manage contest scoreboard");
        }
    }

    private boolean canManage(ContestEntity contest) {
        return SecuritySupport.hasRole(Role.ADMIN)
                || (SecuritySupport.hasRole(Role.TEACHER) && contest.getOwnerUserId().equals(SecuritySupport.currentUserId()));
    }

    private boolean isGroupMember(Long groupId, Long userId) {
        if (groupId == null || userId == null) {
            return false;
        }
        return learningGroupMemberMapper.selectCount(new LambdaQueryWrapper<LearningGroupMemberEntity>()
                .eq(LearningGroupMemberEntity::getGroupId, groupId)
                .eq(LearningGroupMemberEntity::getUserId, userId)
                .eq(LearningGroupMemberEntity::getRole, LearningGroupMemberRole.STUDENT)) > 0;
    }

    private boolean isAllowedGroupMember(Long runId, Long userId) {
        if (runId == null || userId == null) {
            return false;
        }
        List<Long> groupIds = allowedGroupMapper.selectList(new LambdaQueryWrapper<ContestRunAllowedGroupEntity>()
                        .eq(ContestRunAllowedGroupEntity::getContestRunId, runId))
                .stream()
                .map(ContestRunAllowedGroupEntity::getGroupId)
                .toList();
        if (groupIds.isEmpty()) {
            return false;
        }
        return learningGroupMemberMapper.selectCount(new LambdaQueryWrapper<LearningGroupMemberEntity>()
                .in(LearningGroupMemberEntity::getGroupId, groupIds)
                .eq(LearningGroupMemberEntity::getUserId, userId)
                .eq(LearningGroupMemberEntity::getRole, LearningGroupMemberRole.STUDENT)) > 0;
    }

    private boolean isRunParticipant(Long runId, Long userId) {
        return contestParticipantMapper.selectCount(new LambdaQueryWrapper<ContestParticipantEntity>()
                .eq(ContestParticipantEntity::getContestRunId, runId)
                .eq(ContestParticipantEntity::getUserId, userId)
                .eq(ContestParticipantEntity::getStatus, ContestParticipantStatus.ACTIVE)) > 0;
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
                .eq(runId != null, ContestParticipantEntity::getContestRunId, runId)
                .eq(ContestParticipantEntity::getStatus, ContestParticipantStatus.ACTIVE)
                .orderByAsc(ContestParticipantEntity::getRegisteredAt)
                .orderByAsc(ContestParticipantEntity::getId));
    }

    private List<SubmissionEntity> contestSubmissions(Long contestId, Long runId, long atMillis) {
        return submissionMapper.selectList(new LambdaQueryWrapper<SubmissionEntity>()
                .eq(SubmissionEntity::getContestId, contestId)
                .eq(runId != null, SubmissionEntity::getContestRunId, runId)
                .le(SubmissionEntity::getSubmittedAtContestMillis, atMillis)
                .eq(SubmissionEntity::getVisibleToParticipant, true)
                .orderByAsc(SubmissionEntity::getSubmittedAtContestMillis)
                .orderByAsc(SubmissionEntity::getId));
    }

    private long normalizeAtMillis(ContestEntity contest, ContestRunEntity run, Long atMillis, Instant now) {
        long duration = contestDurationMillis(contest, run);
        if (atMillis != null) {
            if (atMillis < 0 || atMillis > duration) {
                throw new DomainException(ErrorCode.BAD_REQUEST, "Scoreboard time must be inside contest duration");
            }
            return atMillis;
        }
        Instant startAt = startAt(contest, run);
        Instant endAt = endAt(contest, run);
        if (now.isBefore(startAt)) {
            return 0;
        }
        if (!now.isBefore(endAt)) {
            return duration;
        }
        return Duration.between(startAt, now).toMillis();
    }

    private long contestDurationMillis(ContestEntity contest, ContestRunEntity run) {
        return Math.max(0L, Duration.between(startAt(contest, run), endAt(contest, run)).toMillis());
    }

    private void requireConcreteScoreboardScope(ContestEntity contest, ContestRunEntity run) {
        if (run != null) {
            return;
        }
        if (contest.getStartAt() == null || contest.getEndAt() == null) {
            throw new DomainException(ErrorCode.BAD_REQUEST, "Contest run is required for run-based scoreboard");
        }
        Long activeRuns = contestRunMapper.selectCount(new LambdaQueryWrapper<ContestRunEntity>()
                .eq(ContestRunEntity::getContestId, contest.getId())
                .isNull(ContestRunEntity::getDeletedAt));
        if (activeRuns != null && activeRuns > 0) {
            throw new DomainException(ErrorCode.BAD_REQUEST, "Contest run is required for run-based scoreboard");
        }
    }

    private long freezeMillis(ContestEntity contest, ContestRunEntity run) {
        Instant freezeAt = freezeAt(contest, run);
        if (freezeAt == null) {
            return -1L;
        }
        return Math.max(0L, Duration.between(startAt(contest, run), freezeAt).toMillis());
    }

    private Instant startAt(ContestEntity contest, ContestRunEntity run) {
        return run == null ? contest.getStartAt() : run.getStartAt();
    }

    private Instant endAt(ContestEntity contest, ContestRunEntity run) {
        return run == null ? contest.getEndAt() : run.getEndAt();
    }

    private Instant freezeAt(ContestEntity contest, ContestRunEntity run) {
        return run == null ? contest.getFreezeAt() : run.getFreezeAt();
    }

    private ContestMode effectiveMode(ContestEntity contest, ContestRunEntity run) {
        return run != null && run.getModeSnapshot() != null ? run.getModeSnapshot() : contest.getMode();
    }

    private void assertRunFinishedAndSettled(Long contestId, ContestRunEntity run) {
        if (Instant.now().isBefore(run.getEndAt())) {
            throw new DomainException(ErrorCode.BAD_REQUEST, "Contest run must end before public scoreboard can be unfrozen");
        }
        long unfinished = submissionMapper.selectCount(new LambdaQueryWrapper<SubmissionEntity>()
                .eq(SubmissionEntity::getContestId, contestId)
                .eq(SubmissionEntity::getContestRunId, run.getId())
                .in(SubmissionEntity::getStatus, SubmissionStatus.QUEUED, SubmissionStatus.RUNNING, SubmissionStatus.SYSTEM_ERROR));
        if (unfinished > 0) {
            throw new DomainException(ErrorCode.BAD_REQUEST, "Contest run has unfinished or system-error submissions");
        }
    }

    private void clearPublicTimeline(Long runId) {
        clearTimeline(runId, ContestScoreboardView.PUBLIC);
    }

    private void clearTimeline(Long runId, ContestScoreboardView view) {
        timelineTickMapper.delete(new LambdaQueryWrapper<ContestScoreboardTimelineTickEntity>()
                .eq(ContestScoreboardTimelineTickEntity::getContestRunId, runId)
                .eq(ContestScoreboardTimelineTickEntity::getViewType, view));
    }

    private List<ContestScoreboardTimelineTickEntity> listTimelineTicks(Long runId, ContestScoreboardView view) {
        return timelineTickMapper.selectList(new LambdaQueryWrapper<ContestScoreboardTimelineTickEntity>()
                .eq(ContestScoreboardTimelineTickEntity::getContestRunId, runId)
                .eq(ContestScoreboardTimelineTickEntity::getViewType, view)
                .orderByAsc(ContestScoreboardTimelineTickEntity::getBucketMillis));
    }

    private ContestScoreboardTimelineTickEntity findTimelineTick(Long runId, ContestScoreboardView view, Long bucketMillis) {
        if (bucketMillis == null || bucketMillis % MINUTE_MILLIS != 0) {
            return null;
        }
        return timelineTickMapper.selectOne(new LambdaQueryWrapper<ContestScoreboardTimelineTickEntity>()
                .eq(ContestScoreboardTimelineTickEntity::getContestRunId, runId)
                .eq(ContestScoreboardTimelineTickEntity::getViewType, view)
                .eq(ContestScoreboardTimelineTickEntity::getBucketMillis, bucketMillis)
                .last("LIMIT 1"));
    }

    private boolean timelineComplete(List<ContestScoreboardTimelineTickEntity> ticks, int expectedTicks) {
        if (ticks == null || ticks.size() < expectedTicks || expectedTicks <= 0) {
            return false;
        }
        Set<Long> buckets = new HashSet<>();
        for (ContestScoreboardTimelineTickEntity tick : ticks) {
            Long bucket = tick.getBucketMillis();
            if (bucket != null && bucket >= 0 && bucket % MINUTE_MILLIS == 0) {
                buckets.add(bucket);
            }
        }
        if (buckets.size() < expectedTicks) {
            return false;
        }
        long expectedLastBucket = (long) (expectedTicks - 1) * MINUTE_MILLIS;
        for (long bucket = 0; bucket <= expectedLastBucket; bucket += MINUTE_MILLIS) {
            if (!buckets.contains(bucket)) {
                return false;
            }
        }
        return true;
    }

    private boolean timelineStale(Long contestId, Long runId, List<ContestScoreboardTimelineTickEntity> ticks) {
        if (ticks == null || ticks.isEmpty()) {
            return false;
        }
        Instant latestTickCreatedAt = ticks.stream()
                .map(ContestScoreboardTimelineTickEntity::getCreatedAt)
                .filter(createdAt -> createdAt != null)
                .max(Comparator.naturalOrder())
                .orElse(null);
        if (latestTickCreatedAt == null) {
            return true;
        }
        SubmissionEntity newerSubmission = submissionMapper.selectOne(new LambdaQueryWrapper<SubmissionEntity>()
                .eq(SubmissionEntity::getContestId, contestId)
                .eq(SubmissionEntity::getContestRunId, runId)
                .eq(SubmissionEntity::getVisibleToParticipant, true)
                .gt(SubmissionEntity::getUpdatedAt, latestTickCreatedAt)
                .orderByDesc(SubmissionEntity::getUpdatedAt)
                .last("LIMIT 1"));
        return newerSubmission != null;
    }

    private int expectedTimelineTickCount(ContestEntity contest, ContestRunEntity run) {
        long lastBucket = lastTimelineBucket(contestDurationMillis(contest, run));
        long count = (lastBucket / MINUTE_MILLIS) + 1;
        return count > Integer.MAX_VALUE ? Integer.MAX_VALUE : (int) Math.max(1, count);
    }

    private long lastTimelineBucket(long durationMillis) {
        return Math.max(0, durationMillis / MINUTE_MILLIS * MINUTE_MILLIS);
    }

    private void insertTimelineTick(Long contestId, Long runId, ContestScoreboardView view, long bucket,
                                    Long snapshotId, String checksum, Instant createdAt) {
        ContestScoreboardTimelineTickEntity tick = new ContestScoreboardTimelineTickEntity();
        tick.setContestId(contestId);
        tick.setContestRunId(runId);
        tick.setViewType(view);
        tick.setBucketMillis(bucket);
        tick.setSnapshotId(snapshotId);
        tick.setChecksum(checksum);
        tick.setCreatedAt(createdAt);
        try {
            timelineTickMapper.insert(tick);
        } catch (DuplicateKeyException ignored) {
            // A concurrent request may have completed the same tick first; the unique key keeps it safe.
        }
    }

    private boolean publicScoreboardUnfrozen(ContestRunEntity run) {
        return run != null && run.getPublicScoreboardUnfrozenAt() != null;
    }

    private ContestRunResponse toRunResponse(ContestRunEntity run) {
        return new ContestRunResponse(run.getId(), run.getContestId(), run.getRunKind(), run.getTitle(), run.getStatus(),
                run.getStartAt(), run.getEndAt(), run.getFreezeAt(), run.getSourceRunId(), run.getRegistrationPolicy(),
                effectiveRegistrationAccess(run), Boolean.TRUE.equals(run.getApprovalRequired()), allowedGroupIds(run.getId()),
                run.getRegistrationStartAt(), run.getRegistrationEndAt(), run.getMaxParticipants(),
                run.getContestTitleSnapshot(), run.getContestDescriptionSnapshot(), run.getModeSnapshot(),
                run.getPenaltyMinutesSnapshot(), run.getCePenaltySnapshot(),
                run.getArchivedAt(), run.getArchiveReason(), run.getStatusBeforeArchive(), run.getDeletedAt(),
                run.getDeletedBy(), run.getPublicScoreboardUnfrozenAt(), run.getPublicScoreboardUnfrozenBy(),
                run.getAiPolicyModeSnapshot(), run.getAiPolicyNotesSnapshot(),
                run.getCreatedBy(), run.getCreatedAt(), run.getUpdatedAt());
    }

    private List<Long> allowedGroupIds(Long runId) {
        return allowedGroupMapper.selectList(new LambdaQueryWrapper<ContestRunAllowedGroupEntity>()
                        .eq(ContestRunAllowedGroupEntity::getContestRunId, runId)
                        .orderByAsc(ContestRunAllowedGroupEntity::getId))
                .stream()
                .map(ContestRunAllowedGroupEntity::getGroupId)
                .toList();
    }

    private ContestRegistrationAccess effectiveRegistrationAccess(ContestRunEntity run) {
        if (run.getRegistrationAccess() != null) {
            return run.getRegistrationAccess();
        }
        return switch (run.getRegistrationPolicy() == null ? ContestRegistrationPolicy.INVITE_ONLY : run.getRegistrationPolicy()) {
            case PUBLIC_SELF_REGISTER, APPROVAL_REQUIRED -> ContestRegistrationAccess.PUBLIC;
            case GROUP_SELF_REGISTER -> ContestRegistrationAccess.GROUPS;
            case INVITE_ONLY -> ContestRegistrationAccess.INVITE_ONLY;
        };
    }

    private int effectivePenaltyMinutes(ContestEntity contest, ContestRunEntity run) {
        if (run != null && run.getPenaltyMinutesSnapshot() != null) {
            return run.getPenaltyMinutesSnapshot();
        }
        return contest.getPenaltyMinutes() == null ? 20 : contest.getPenaltyMinutes();
    }

    private boolean effectiveCePenalty(ContestEntity contest, ContestRunEntity run) {
        if (run != null && run.getCePenaltySnapshot() != null) {
            return Boolean.TRUE.equals(run.getCePenaltySnapshot());
        }
        return Boolean.TRUE.equals(contest.getCePenalty());
    }

    private ContestScoreboardView defaultSnapshotView(ContestScoreboardSnapshotKind kind) {
        return kind == ContestScoreboardSnapshotKind.PUBLIC_FROZEN ? ContestScoreboardView.PUBLIC : ContestScoreboardView.PRIVATE;
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

    private int safeInt(Integer value) {
        return value == null ? 0 : value;
    }

    private BigDecimal contestProblemMaxScore(ContestProblemEntity problem) {
        Integer score = problem == null ? null : problem.getScore();
        return score == null || score <= 0 ? ZERO_SCORE : BigDecimal.valueOf(score.longValue());
    }

    private BigDecimal scoreForSubmission(SubmissionEntity submission, BigDecimal maxScore) {
        if (submission.getScore() != null) {
            return submission.getScore();
        }
        if (submission.getStatus() == SubmissionStatus.ACCEPTED) {
            return firstNonNull(maxScore, ZERO_SCORE);
        }
        return ZERO_SCORE;
    }

    private ContestProblemScoringMode scoringMode(Long contestProblemId) {
        ContestProblemScoringRuleEntity rule = scoringRuleMapper.selectOne(new LambdaQueryWrapper<ContestProblemScoringRuleEntity>()
                .eq(ContestProblemScoringRuleEntity::getContestProblemId, contestProblemId)
                .last("LIMIT 1"));
        return rule == null || rule.getScoringMode() == null
                ? ContestProblemScoringMode.CASE_SUM_BEST_SUBMISSION
                : rule.getScoringMode();
    }

    private boolean isFullScore(BigDecimal score, BigDecimal maxScore) {
        BigDecimal safeScore = nullToZeroScore(score);
        BigDecimal safeMax = nullToZeroScore(maxScore);
        return safeMax.compareTo(ZERO_SCORE) > 0 && safeScore.compareTo(safeMax) >= 0;
    }

    private BigDecimal nullToZeroScore(BigDecimal value) {
        return value == null ? ZERO_SCORE : value;
    }

    private BigDecimal firstNonNull(BigDecimal value, BigDecimal fallback) {
        return value == null ? fallback : value;
    }

    private BigDecimal sumScores(Iterable<BigDecimal> scores) {
        BigDecimal sum = BigDecimal.ZERO;
        for (BigDecimal score : scores) {
            if (score != null) {
                sum = sum.add(score);
            }
        }
        return sum;
    }

    private long safeLong(Long value) {
        return value == null ? 0L : value;
    }

    private ContestScoreboardSnapshotResponse toSnapshotResponse(ContestScoreboardSnapshotEntity snapshot) {
        return new ContestScoreboardSnapshotResponse(snapshot.getId(), snapshot.getContestId(), snapshot.getContestRunId(),
                snapshot.getSnapshotKind(),
                snapshot.getViewType(), snapshot.getSnapshotAt(), snapshot.getContestTimeMillis(),
                snapshot.getScoringVersion(), Boolean.TRUE.equals(snapshot.getFrozen()), snapshot.getChecksum(),
                snapshot.getCreatedBy(), snapshot.getCreatedAt());
    }

    private ContestScoreboardTimelineTickResponse toTimelineResponse(ContestScoreboardTimelineTickEntity tick) {
        return new ContestScoreboardTimelineTickResponse(tick.getId(), tick.getContestId(), tick.getContestRunId(),
                tick.getViewType(), tick.getBucketMillis(), tick.getSnapshotId(), tick.getChecksum(), tick.getCreatedAt());
    }

    private String checksumFor(ContestScoreboardResponse response) {
        return sha256(writePayload(new SnapshotPayload(response.penaltyMinutes(), response.cePenalty(),
                response.freezeAtContestMillis(), response.problems(), response.rows())));
    }

    private String writePayload(SnapshotPayload payload) {
        try {
            return objectMapper.writeValueAsString(payload);
        } catch (JsonProcessingException ex) {
            throw new DomainException(ErrorCode.INTERNAL_ERROR, "Scoreboard snapshot serialization failed");
        }
    }

    private SnapshotPayload readPayload(String json) {
        try {
            return objectMapper.readValue(json, SnapshotPayload.class);
        } catch (JsonProcessingException ex) {
            throw new DomainException(ErrorCode.INTERNAL_ERROR, "Scoreboard snapshot payload is invalid");
        }
    }

    private String sha256(String value) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            byte[] bytes = digest.digest(value.getBytes(StandardCharsets.UTF_8));
            StringBuilder builder = new StringBuilder(bytes.length * 2);
            for (byte b : bytes) {
                builder.append(String.format("%02x", b));
            }
            return builder.toString();
        } catch (NoSuchAlgorithmException ex) {
            throw new DomainException(ErrorCode.INTERNAL_ERROR, "SHA-256 is unavailable");
        }
    }

    public record SnapshotPayload(
            int penaltyMinutes,
            boolean cePenalty,
            Long freezeAtContestMillis,
            List<ContestScoreboardProblemResponse> problems,
            List<ContestScoreboardRowResponse> rows
    ) {
    }

    private record SubtaskAggregateScore(
            SubmissionEntity bestSubmission,
            BigDecimal score,
            BigDecimal maxScore
    ) {
    }

    public interface TimelineProgressCallback {
        void update(int current, int total, String message);
    }

    private enum SnapshotPersistMode {
        FULL,
        JSON_ONLY
    }

    private record TimelineSnapshotRef(Long snapshotId, String checksum) {
    }

    private static final class ScoreIndex {
        private final Map<Long, Map<Long, List<SubmissionEntity>>> byParticipantProblem = new HashMap<>();

        private ScoreIndex(List<SubmissionEntity> submissions) {
            Set<Long> seen = new HashSet<>();
            for (SubmissionEntity submission : submissions) {
                if (submission.getContestParticipantId() == null || submission.getContestProblemId() == null) {
                    continue;
                }
                if (!seen.add(submission.getId())) {
                    continue;
                }
                byParticipantProblem
                        .computeIfAbsent(submission.getContestParticipantId(), ignored -> new HashMap<>())
                        .computeIfAbsent(submission.getContestProblemId(), ignored -> new ArrayList<>())
                        .add(submission);
            }
        }

        private List<SubmissionEntity> forCell(Long participantId, Long contestProblemId) {
            return byParticipantProblem.getOrDefault(participantId, Map.of())
                    .getOrDefault(contestProblemId, List.of());
        }
    }
}
