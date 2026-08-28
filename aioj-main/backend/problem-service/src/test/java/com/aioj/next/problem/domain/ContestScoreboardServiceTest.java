package com.aioj.next.problem.domain;

import com.aioj.next.common.error.DomainException;
import com.aioj.next.common.error.ErrorCode;
import com.aioj.next.common.security.Role;
import com.aioj.next.common.security.SecurityPrincipal;
import com.aioj.next.contract.contest.ContestMode;
import com.aioj.next.contract.contest.ContestParticipantStatus;
import com.aioj.next.contract.contest.ContestParticipantType;
import com.aioj.next.contract.contest.ContestScoreboardCellStatus;
import com.aioj.next.contract.contest.ContestScoreboardSnapshotCreateRequest;
import com.aioj.next.contract.contest.ContestScoreboardSnapshotKind;
import com.aioj.next.contract.contest.ContestScoreboardTimelineStatus;
import com.aioj.next.contract.contest.ContestScoreboardView;
import com.aioj.next.contract.contest.ContestRunStatus;
import com.aioj.next.contract.contest.ContestStatus;
import com.aioj.next.contract.contest.ContestVisibility;
import com.aioj.next.contract.operation.OperationJobResponse;
import com.aioj.next.contract.operation.OperationJobStatus;
import com.aioj.next.contract.operation.OperationJobType;
import com.aioj.next.contract.submission.SubmissionStatus;
import com.aioj.next.problem.persistence.entity.ContestEntity;
import com.aioj.next.problem.persistence.entity.ContestParticipantEntity;
import com.aioj.next.problem.persistence.entity.ContestProblemEntity;
import com.aioj.next.problem.persistence.entity.ContestRunEntity;
import com.aioj.next.problem.persistence.entity.ContestScoreboardCellEntity;
import com.aioj.next.problem.persistence.entity.ContestScoreboardRowEntity;
import com.aioj.next.problem.persistence.entity.ContestScoreboardSnapshotEntity;
import com.aioj.next.problem.persistence.entity.ContestScoreboardTimelineTickEntity;
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
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.context.SecurityContextHolder;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import java.util.Set;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class ContestScoreboardServiceTest {
    @Mock
    private ContestMapper contestMapper;
    @Mock
    private ContestRunMapper contestRunMapper;
    @Mock
    private ContestRunAllowedGroupMapper contestRunAllowedGroupMapper;
    @Mock
    private ContestProblemMapper contestProblemMapper;
    @Mock
    private ContestProblemScoringRuleMapper scoringRuleMapper;
    @Mock
    private ContestParticipantMapper contestParticipantMapper;
    @Mock
    private SubmissionMapper submissionMapper;
    @Mock
    private SubmissionCaseResultMapper caseResultMapper;
    @Mock
    private ProblemSubtaskMapper problemSubtaskMapper;
    @Mock
    private LearningGroupMemberMapper learningGroupMemberMapper;
    @Mock
    private ContestScoreboardSnapshotMapper snapshotMapper;
    @Mock
    private ContestScoreboardRowMapper rowMapper;
    @Mock
    private ContestScoreboardCellMapper cellMapper;
    @Mock
    private ContestScoreboardTimelineTickMapper timelineTickMapper;
    @Mock
    private OperationJobService operationJobService;
    @Mock
    private ContestProblemVisibilityService visibilityService;

    private final ObjectMapper objectMapper = new ObjectMapper();
    private ContestScoreboardService service;

    @BeforeEach
    void setUp() {
        org.mockito.Mockito.lenient().when(visibilityService.hiddenContestProblemIdsForRun(org.mockito.ArgumentMatchers.any(), org.mockito.ArgumentMatchers.any()))
                .thenReturn(java.util.Set.of());
        service = new ContestScoreboardService(contestMapper, contestRunMapper, contestRunAllowedGroupMapper, contestProblemMapper,
                scoringRuleMapper, contestParticipantMapper, submissionMapper, caseResultMapper, problemSubtaskMapper, learningGroupMemberMapper,
                snapshotMapper, rowMapper, cellMapper, timelineTickMapper, operationJobService, objectMapper, visibilityService);
    }

    @AfterEach
    void tearDown() {
        SecurityContextHolder.clearContext();
    }

    @Test
    void acmRankingUsesSolvedPenaltyAndCeDefaultDoesNotPenalty() {
        authenticate(7L, Role.TEACHER);
        ContestEntity contest = contest(false);
        when(contestMapper.selectById(301L)).thenReturn(contest);
        when(contestProblemMapper.selectList(any())).thenReturn(List.of(problem(401L, "A")));
        when(contestParticipantMapper.selectList(any())).thenReturn(List.of(participant(601L, 91L, "Alice"), participant(602L, 92L, "Bob")));
        when(submissionMapper.selectList(any())).thenReturn(List.of(
                submission(1L, 601L, 401L, 10, SubmissionStatus.WRONG_ANSWER),
                submission(2L, 601L, 401L, 15, SubmissionStatus.COMPILE_ERROR),
                submission(3L, 601L, 401L, 30, SubmissionStatus.ACCEPTED),
                submission(4L, 602L, 401L, 40, SubmissionStatus.ACCEPTED)
        ));

        var scoreboard = service.scoreboard(301L, ContestScoreboardView.PRIVATE, minutes(120), null);

        assertEquals(602L, scoreboard.rows().get(0).participantId());
        assertEquals(40, scoreboard.rows().get(0).penaltyMinutes());
        assertEquals(601L, scoreboard.rows().get(1).participantId());
        assertEquals(50, scoreboard.rows().get(1).penaltyMinutes());
        assertEquals(ContestScoreboardCellStatus.SOLVED, scoreboard.rows().get(1).cells().get(0).status());
        assertEquals(1, scoreboard.rows().get(1).cells().get(0).wrongAttempts());
    }

    @Test
    void runBasedBlueprintRequiresRunForScoreboard() {
        authenticate(7L, Role.TEACHER);
        ContestEntity contest = contest(false);
        contest.setStartAt(null);
        contest.setEndAt(null);
        when(contestMapper.selectById(301L)).thenReturn(contest);

        DomainException error = assertThrows(DomainException.class,
                () -> service.scoreboard(301L, ContestScoreboardView.PRIVATE, minutes(10), null));

        assertEquals(ErrorCode.BAD_REQUEST, error.errorCode());
    }

    @Test
    void contestWithRunsRequiresConcreteRunForScoreboard() {
        authenticate(7L, Role.TEACHER);
        ContestEntity contest = contest(false);
        when(contestMapper.selectById(301L)).thenReturn(contest);
        when(contestRunMapper.selectCount(any())).thenReturn(1L);

        DomainException error = assertThrows(DomainException.class,
                () -> service.scoreboard(301L, ContestScoreboardView.PRIVATE, minutes(10), null));

        assertEquals(ErrorCode.BAD_REQUEST, error.errorCode());
    }

    @Test
    void ceCanBeConfiguredAsPenalty() {
        authenticate(7L, Role.TEACHER);
        ContestEntity contest = contest(true);
        when(contestMapper.selectById(301L)).thenReturn(contest);
        when(contestProblemMapper.selectList(any())).thenReturn(List.of(problem(401L, "A")));
        when(contestParticipantMapper.selectList(any())).thenReturn(List.of(participant(601L, 91L, "Alice")));
        when(submissionMapper.selectList(any())).thenReturn(List.of(
                submission(1L, 601L, 401L, 10, SubmissionStatus.WRONG_ANSWER),
                submission(2L, 601L, 401L, 15, SubmissionStatus.COMPILE_ERROR),
                submission(3L, 601L, 401L, 30, SubmissionStatus.ACCEPTED)
        ));

        var scoreboard = service.scoreboard(301L, ContestScoreboardView.PRIVATE, minutes(120), null);

        assertEquals(70, scoreboard.rows().get(0).penaltyMinutes());
        assertEquals(2, scoreboard.rows().get(0).cells().get(0).wrongAttempts());
    }

    @Test
    void sameSolvedAndPenaltyUsesLastAcceptedTimeAsTieBreak() {
        authenticate(7L, Role.TEACHER);
        ContestEntity contest = contest(false);
        when(contestMapper.selectById(301L)).thenReturn(contest);
        when(contestProblemMapper.selectList(any())).thenReturn(List.of(problem(401L, "A"), problem(402L, "B")));
        when(contestParticipantMapper.selectList(any())).thenReturn(List.of(participant(601L, 91L, "Alice"), participant(602L, 92L, "Bob")));
        when(submissionMapper.selectList(any())).thenReturn(List.of(
                submission(1L, 601L, 401L, 20, SubmissionStatus.ACCEPTED),
                submission(2L, 601L, 402L, 40, SubmissionStatus.ACCEPTED),
                submission(3L, 602L, 401L, 30, SubmissionStatus.ACCEPTED),
                submission(4L, 602L, 402L, 30, SubmissionStatus.ACCEPTED)
        ));

        var scoreboard = service.scoreboard(301L, ContestScoreboardView.PRIVATE, minutes(120), null);

        assertEquals(60, scoreboard.rows().get(0).penaltyMinutes());
        assertEquals(60, scoreboard.rows().get(1).penaltyMinutes());
        assertEquals(602L, scoreboard.rows().get(0).participantId());
        assertEquals(601L, scoreboard.rows().get(1).participantId());
    }

    @Test
    void publicFrozenBoardHidesPostFreezeVerdictButPrivateBoardShowsIt() {
        authenticate(7L, Role.TEACHER);
        ContestEntity contest = contest(false);
        contest.setFreezeAt(contest.getStartAt().plusSeconds(3600));
        when(contestMapper.selectById(301L)).thenReturn(contest);
        when(contestProblemMapper.selectList(any())).thenReturn(List.of(problem(401L, "A")));
        when(contestParticipantMapper.selectList(any())).thenReturn(List.of(participant(601L, 91L, "Alice")));
        when(submissionMapper.selectList(any())).thenReturn(List.of(
                submission(1L, 601L, 401L, 70, SubmissionStatus.ACCEPTED)
        ));

        var publicBoard = service.scoreboard(301L, ContestScoreboardView.PUBLIC, minutes(120), null);
        var privateBoard = service.scoreboard(301L, ContestScoreboardView.PRIVATE, minutes(120), null);

        assertEquals(true, publicBoard.frozen());
        assertEquals(0, publicBoard.rows().get(0).solvedCount());
        assertEquals(ContestScoreboardCellStatus.PENDING, publicBoard.rows().get(0).cells().get(0).status());
        assertEquals(1, publicBoard.rows().get(0).cells().get(0).pendingAttempts());
        assertEquals(1, privateBoard.rows().get(0).solvedCount());
        assertEquals(ContestScoreboardCellStatus.SOLVED, privateBoard.rows().get(0).cells().get(0).status());
    }

    @Test
    void unfreezePublicScoreboardRevealsPostFreezeVerdictAndClearsTimeline() {
        authenticate(7L, Role.TEACHER);
        ContestEntity contest = contest(false);
        Instant started = Instant.now().minusSeconds(14_400);
        contest.setStartAt(started);
        contest.setEndAt(started.plusSeconds(10_800));
        contest.setFreezeAt(started.plusSeconds(3_600));
        ContestRunEntity run = run(501L, contest);
        when(contestMapper.selectById(301L)).thenReturn(contest);
        when(contestRunMapper.selectById(501L)).thenReturn(run);
        when(submissionMapper.selectCount(any())).thenReturn(0L);
        when(contestRunAllowedGroupMapper.selectList(any())).thenReturn(List.of());
        when(contestProblemMapper.selectList(any())).thenReturn(List.of(problem(401L, "A")));
        when(contestParticipantMapper.selectList(any())).thenReturn(List.of(participant(601L, 91L, "Alice")));
        when(submissionMapper.selectList(any())).thenReturn(List.of(
                submission(1L, 601L, 401L, 70, SubmissionStatus.ACCEPTED)
        ));

        var response = service.unfreezePublicScoreboard(301L, 501L);
        var publicBoard = service.scoreboard(301L, 501L, ContestScoreboardView.PUBLIC, minutes(120), null);

        assertEquals(501L, response.id());
        assertEquals(false, publicBoard.frozen());
        assertEquals(1, publicBoard.rows().get(0).solvedCount());
        assertEquals(ContestScoreboardCellStatus.SOLVED, publicBoard.rows().get(0).cells().get(0).status());
        verify(contestRunMapper).updateById(run);
        verify(timelineTickMapper).delete(any());
    }

    @Test
    void unfreezePublicScoreboardRejectsRunWithoutFreezeTime() {
        authenticate(7L, Role.TEACHER);
        ContestEntity contest = contest(false);
        ContestRunEntity run = run(501L, contest);
        when(contestMapper.selectById(301L)).thenReturn(contest);
        when(contestRunMapper.selectById(501L)).thenReturn(run);

        DomainException error = assertThrows(DomainException.class,
                () -> service.unfreezePublicScoreboard(301L, 501L));

        assertEquals(ErrorCode.BAD_REQUEST, error.errorCode());
        assertEquals("Contest run has no scoreboard freeze", error.getMessage());
        verify(contestRunMapper, never()).updateById(any(ContestRunEntity.class));
        verify(timelineTickMapper, never()).delete(any());
    }

    @Test
    void exactMinuteAtMillisUsesTimelineSnapshotWhenAvailable() throws Exception {
        authenticate(7L, Role.TEACHER);
        ContestEntity contest = contest(false);
        ContestRunEntity run = run(501L, contest);
        when(contestMapper.selectById(301L)).thenReturn(contest);
        when(contestRunMapper.selectById(501L)).thenReturn(run);

        ContestScoreboardTimelineTickEntity tick = new ContestScoreboardTimelineTickEntity();
        tick.setContestId(301L);
        tick.setContestRunId(501L);
        tick.setViewType(ContestScoreboardView.PUBLIC);
        tick.setBucketMillis(minutes(60));
        tick.setSnapshotId(901L);
        when(timelineTickMapper.selectOne(any())).thenReturn(tick);

        ContestScoreboardSnapshotEntity snapshot = new ContestScoreboardSnapshotEntity();
        snapshot.setId(901L);
        snapshot.setContestId(301L);
        snapshot.setContestRunId(501L);
        snapshot.setSnapshotKind(ContestScoreboardSnapshotKind.MANUAL);
        snapshot.setViewType(ContestScoreboardView.PUBLIC);
        snapshot.setSnapshotAt(Instant.parse("2026-06-10T10:00:00Z"));
        snapshot.setContestTimeMillis(minutes(60));
        snapshot.setFrozen(false);
        snapshot.setRowsJson(objectMapper.writeValueAsString(new ContestScoreboardService.SnapshotPayload(
                20, false, null, List.of(), List.of())));
        when(snapshotMapper.selectById(901L)).thenReturn(snapshot);

        var response = service.scoreboard(301L, 501L, ContestScoreboardView.PUBLIC, minutes(60), null);

        assertEquals(901L, response.snapshotId());
        assertEquals(minutes(60), response.atContestMillis());
        verify(submissionMapper, never()).selectList(any());
    }

    @Test
    void timelineFirstAccessReturnsGeneratingAndQueuesJob() {
        authenticate(7L, Role.TEACHER);
        ContestEntity contest = contest(false);
        ContestRunEntity run = run(501L, contest);
        when(contestMapper.selectById(301L)).thenReturn(contest);
        when(contestRunMapper.selectById(501L)).thenReturn(run);
        when(timelineTickMapper.selectList(any())).thenReturn(List.of());
        OperationJobResponse job = new OperationJobResponse(801L, OperationJobType.GENERATE_SCOREBOARD_TIMELINE,
                OperationJobStatus.QUEUED, "CONTEST_RUN", 501L, 301L, 501L, 7L, null,
                0, 3, 0, 301, "Queued", null, null, null, null, Instant.now(), Instant.now());
        when(operationJobService.findOrCreateScoreboardTimelineJob(301L, 501L, ContestScoreboardView.PUBLIC))
                .thenReturn(job);

        var response = service.timeline(301L, 501L, ContestScoreboardView.PUBLIC);

        assertEquals(ContestScoreboardTimelineStatus.GENERATING, response.status());
        assertEquals(801L, response.jobId());
        assertEquals(0, response.ticks().size());
        verify(snapshotMapper, never()).insert(any(ContestScoreboardSnapshotEntity.class));
    }

    @Test
    void timelineGenerationUsesJsonOnlySnapshotsAndReusesUnchangedSnapshot() {
        authenticate(7L, Role.TEACHER);
        ContestEntity contest = contest(false);
        contest.setEndAt(contest.getStartAt().plusSeconds(7200));
        ContestRunEntity run = run(501L, contest);
        when(contestMapper.selectById(301L)).thenReturn(contest);
        when(contestRunMapper.selectById(501L)).thenReturn(run);
        when(timelineTickMapper.selectList(any())).thenReturn(List.of());
        when(contestProblemMapper.selectList(any())).thenReturn(List.of());
        when(contestParticipantMapper.selectList(any())).thenReturn(List.of());
        when(submissionMapper.selectList(any())).thenReturn(List.of());
        AtomicLong snapshotId = new AtomicLong(900L);
        doAnswer(invocation -> {
            ContestScoreboardSnapshotEntity snapshot = invocation.getArgument(0);
            snapshot.setId(snapshotId.incrementAndGet());
            return 1;
        }).when(snapshotMapper).insert(any(ContestScoreboardSnapshotEntity.class));

        int ticks = service.generateTimelineForOperation(301L, 501L, ContestScoreboardView.PUBLIC, null);

        assertEquals(121, ticks);
        verify(snapshotMapper, times(1)).insert(any(ContestScoreboardSnapshotEntity.class));
        verify(rowMapper, never()).insert(any(ContestScoreboardRowEntity.class));
        verify(cellMapper, never()).insert(any(ContestScoreboardCellEntity.class));
        verify(timelineTickMapper, times(121)).insert(any(ContestScoreboardTimelineTickEntity.class));
    }

    @Test
    void ioiRankingUsesBestScoreSubmissionAndLastImprovementTieBreak() {
        authenticate(7L, Role.TEACHER);
        ContestEntity contest = contest(false);
        contest.setMode(ContestMode.IOI);
        ContestProblemEntity problem = problem(401L, "A");
        problem.setScore(100);
        when(contestMapper.selectById(301L)).thenReturn(contest);
        when(contestProblemMapper.selectList(any())).thenReturn(List.of(problem));
        when(contestParticipantMapper.selectList(any())).thenReturn(List.of(participant(601L, 91L, "Alice"), participant(602L, 92L, "Bob")));
        when(submissionMapper.selectList(any())).thenReturn(List.of(
                ioiSubmission(1L, 601L, 401L, 10, 30, 100, SubmissionStatus.WRONG_ANSWER),
                ioiSubmission(2L, 601L, 401L, 40, 70, 100, SubmissionStatus.WRONG_ANSWER),
                ioiSubmission(3L, 602L, 401L, 20, 70, 100, SubmissionStatus.WRONG_ANSWER)
        ));

        var scoreboard = service.scoreboard(301L, ContestScoreboardView.PRIVATE, minutes(120), null);

        assertEquals(ContestMode.IOI, scoreboard.mode());
        assertEquals(602L, scoreboard.rows().get(0).participantId());
        assertEquals(new BigDecimal("70"), scoreboard.rows().get(0).totalScore());
        assertEquals(3L, scoreboard.rows().get(0).cells().get(0).bestSubmissionId());
        assertEquals(601L, scoreboard.rows().get(1).participantId());
        assertEquals(new BigDecimal("70"), scoreboard.rows().get(1).totalScore());
        assertEquals(2L, scoreboard.rows().get(1).cells().get(0).bestSubmissionId());
        assertEquals(ContestScoreboardCellStatus.ATTEMPTED, scoreboard.rows().get(1).cells().get(0).status());
    }

    @Test
    void ioiPublicFrozenBoardDoesNotRevealPostFreezeScoreImprovement() {
        authenticate(7L, Role.TEACHER);
        ContestEntity contest = contest(false);
        contest.setMode(ContestMode.IOI);
        contest.setFreezeAt(contest.getStartAt().plusSeconds(3600));
        ContestProblemEntity problem = problem(401L, "A");
        problem.setScore(100);
        when(contestMapper.selectById(301L)).thenReturn(contest);
        when(contestProblemMapper.selectList(any())).thenReturn(List.of(problem));
        when(contestParticipantMapper.selectList(any())).thenReturn(List.of(participant(601L, 91L, "Alice")));
        when(submissionMapper.selectList(any())).thenReturn(List.of(
                ioiSubmission(1L, 601L, 401L, 30, 40, 100, SubmissionStatus.WRONG_ANSWER),
                ioiSubmission(2L, 601L, 401L, 70, 100, 100, SubmissionStatus.ACCEPTED)
        ));

        var publicBoard = service.scoreboard(301L, ContestScoreboardView.PUBLIC, minutes(120), null);
        var privateBoard = service.scoreboard(301L, ContestScoreboardView.PRIVATE, minutes(120), null);

        assertEquals(new BigDecimal("40"), publicBoard.rows().get(0).totalScore());
        assertEquals(1, publicBoard.rows().get(0).cells().get(0).pendingAttempts());
        assertEquals(new BigDecimal("100"), privateBoard.rows().get(0).totalScore());
        assertEquals(ContestScoreboardCellStatus.SOLVED, privateBoard.rows().get(0).cells().get(0).status());
    }

    @Test
    void studentCannotReadPrivateScoreboard() {
        authenticate(9L, Role.STUDENT);
        when(contestMapper.selectById(301L)).thenReturn(contest(false));

        DomainException error = assertThrows(DomainException.class,
                () -> service.scoreboard(301L, ContestScoreboardView.PRIVATE, minutes(10), null));

        assertEquals(ErrorCode.FORBIDDEN, error.errorCode());
    }

    @Test
    void snapshotKeepsRowsJsonStableAfterLaterSubmissions() {
        authenticate(7L, Role.TEACHER);
        ContestEntity contest = contest(false);
        when(contestMapper.selectById(301L)).thenReturn(contest);
        when(contestProblemMapper.selectList(any())).thenReturn(List.of(problem(401L, "A")));
        when(contestParticipantMapper.selectList(any())).thenReturn(List.of(participant(601L, 91L, "Alice")));
        when(submissionMapper.selectList(any())).thenReturn(List.of(
                submission(1L, 601L, 401L, 20, SubmissionStatus.ACCEPTED)
        ));
        AtomicReference<ContestScoreboardSnapshotEntity> stored = new AtomicReference<>();
        doAnswer(invocation -> {
            ContestScoreboardSnapshotEntity snapshot = invocation.getArgument(0);
            snapshot.setId(901L);
            stored.set(snapshot);
            return 1;
        }).when(snapshotMapper).insert(any(ContestScoreboardSnapshotEntity.class));
        doAnswer(invocation -> {
            ContestScoreboardRowEntity row = invocation.getArgument(0);
            row.setId(902L);
            return 1;
        }).when(rowMapper).insert(any(ContestScoreboardRowEntity.class));

        var snapshot = service.createSnapshot(301L, new ContestScoreboardSnapshotCreateRequest(
                ContestScoreboardSnapshotKind.MANUAL, ContestScoreboardView.PRIVATE, minutes(120)));
        when(snapshotMapper.selectById(901L)).thenReturn(stored.get());

        var loaded = service.snapshot(301L, snapshot.snapshotId());

        assertEquals(901L, loaded.snapshotId());
        assertEquals(1, loaded.rows().get(0).solvedCount());
        assertEquals(20, loaded.rows().get(0).penaltyMinutes());
        ArgumentCaptor<ContestScoreboardSnapshotEntity> captor = ArgumentCaptor.forClass(ContestScoreboardSnapshotEntity.class);
        verify(snapshotMapper).insert(captor.capture());
        assertEquals(ContestScoreboardSnapshotKind.MANUAL, captor.getValue().getSnapshotKind());
    }

    private ContestEntity contest(boolean cePenalty) {
        Instant start = Instant.parse("2026-06-10T09:00:00Z");
        ContestEntity contest = new ContestEntity();
        contest.setId(301L);
        contest.setOwnerUserId(7L);
        contest.setScopeGroupId(101L);
        contest.setTitle("Spring Invitational");
        contest.setMode(ContestMode.ACM);
        contest.setStatus(ContestStatus.PUBLISHED);
        contest.setVisibility(ContestVisibility.GROUP);
        contest.setStartAt(start);
        contest.setEndAt(start.plusSeconds(18_000));
        contest.setPenaltyMinutes(20);
        contest.setCePenalty(cePenalty);
        return contest;
    }

    private ContestRunEntity run(Long id, ContestEntity contest) {
        ContestRunEntity run = new ContestRunEntity();
        run.setId(id);
        run.setContestId(contest.getId());
        run.setTitle("Run");
        run.setStatus(ContestRunStatus.ENDED);
        run.setStartAt(contest.getStartAt());
        run.setEndAt(contest.getEndAt());
        run.setFreezeAt(contest.getFreezeAt());
        run.setModeSnapshot(contest.getMode());
        run.setPenaltyMinutesSnapshot(contest.getPenaltyMinutes());
        run.setCePenaltySnapshot(contest.getCePenalty());
        run.setContestTitleSnapshot(contest.getTitle());
        run.setContestDescriptionSnapshot(contest.getDescription());
        return run;
    }

    private ContestProblemEntity problem(Long id, String label) {
        ContestProblemEntity problem = new ContestProblemEntity();
        problem.setId(id);
        problem.setContestId(301L);
        problem.setProblemId(1001L);
        problem.setLabel(label);
        problem.setDisplayTitle("Problem " + label);
        problem.setScore(0);
        problem.setSortOrder(label.charAt(0) - 'A');
        return problem;
    }

    private ContestParticipantEntity participant(Long id, Long userId, String name) {
        ContestParticipantEntity participant = new ContestParticipantEntity();
        participant.setId(id);
        participant.setContestId(301L);
        participant.setUserId(userId);
        participant.setParticipantType(ContestParticipantType.INDIVIDUAL);
        participant.setStatus(ContestParticipantStatus.ACTIVE);
        participant.setAccountSnapshot(name.toLowerCase());
        participant.setDisplayNameSnapshot(name);
        participant.setRegisteredAt(Instant.parse("2026-06-10T08:00:00Z"));
        return participant;
    }

    private SubmissionEntity submission(Long id, Long participantId, Long contestProblemId, long minute, SubmissionStatus status) {
        SubmissionEntity submission = new SubmissionEntity();
        submission.setId(id);
        submission.setContestId(301L);
        submission.setContestProblemId(contestProblemId);
        submission.setContestParticipantId(participantId);
        submission.setSubmittedAtContestMillis(minutes(minute));
        submission.setVisibleToParticipant(true);
        submission.setStatus(status);
        return submission;
    }

    private SubmissionEntity ioiSubmission(Long id, Long participantId, Long contestProblemId, long minute,
                                           int score, int maxScore, SubmissionStatus status) {
        SubmissionEntity submission = submission(id, participantId, contestProblemId, minute, status);
        submission.setScore(BigDecimal.valueOf(score));
        submission.setMaxScore(BigDecimal.valueOf(maxScore));
        return submission;
    }

    private long minutes(long value) {
        return value * 60_000L;
    }

    private void authenticate(Long userId, Role role) {
        SecurityPrincipal principal = new SecurityPrincipal(userId, "user-" + userId, Set.of(role));
        SecurityContextHolder.getContext().setAuthentication(new UsernamePasswordAuthenticationToken(
                principal,
                "n/a",
                List.of(new SimpleGrantedAuthority("ROLE_" + role.name()))
        ));
    }
}
