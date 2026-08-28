package com.aioj.next.problem.domain;

import com.aioj.next.common.error.DomainException;
import com.aioj.next.common.error.ErrorCode;
import com.aioj.next.common.security.Role;
import com.aioj.next.common.security.SecurityPrincipal;
import com.aioj.next.contract.contest.ContestMode;
import com.aioj.next.contract.contest.ContestParticipantStatus;
import com.aioj.next.contract.contest.ContestParticipantType;
import com.aioj.next.contract.contest.ContestResolverStepType;
import com.aioj.next.contract.contest.ContestRunKind;
import com.aioj.next.contract.contest.ContestRunStatus;
import com.aioj.next.contract.contest.ContestScoreboardCellStatus;
import com.aioj.next.contract.contest.ContestScoreboardResponse;
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
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.context.SecurityContextHolder;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class ContestResolverServiceTest {
    @Mock
    private ContestMapper contestMapper;
    @Mock
    private ContestRunMapper contestRunMapper;
    @Mock
    private ContestProblemMapper contestProblemMapper;
    @Mock
    private ContestParticipantMapper contestParticipantMapper;
    @Mock
    private SubmissionMapper submissionMapper;
    @Mock
    private ContestResolverSessionMapper sessionMapper;
    @Mock
    private ContestResolverStepMapper stepMapper;
    @Mock
    private ContestScoreboardService scoreboardService;

    private ContestResolverService service;
    private final AtomicReference<ContestResolverSessionEntity> storedSession = new AtomicReference<>();
    private final List<ContestResolverStepEntity> storedSteps = new ArrayList<>();

    @BeforeEach
    void setUp() {
        ObjectMapper objectMapper = new ObjectMapper();
        objectMapper.registerModule(new JavaTimeModule());
        service = new ContestResolverService(contestMapper, contestRunMapper, contestProblemMapper,
                contestParticipantMapper, submissionMapper, sessionMapper, stepMapper, scoreboardService, objectMapper);
        lenient().doAnswer(invocation -> {
            ContestResolverSessionEntity entity = invocation.getArgument(0);
            entity.setId(900L);
            storedSession.set(entity);
            return 1;
        }).when(sessionMapper).insert(any(ContestResolverSessionEntity.class));
        lenient().doAnswer(invocation -> {
            ContestResolverSessionEntity entity = invocation.getArgument(0);
            storedSession.set(entity);
            return 1;
        }).when(sessionMapper).updateById(any(ContestResolverSessionEntity.class));
        lenient().when(sessionMapper.selectById(900L)).thenAnswer(invocation -> storedSession.get());
        lenient().doAnswer(invocation -> {
            ContestResolverStepEntity entity = invocation.getArgument(0);
            entity.setId(10_000L + entity.getStepOrder());
            storedSteps.add(entity);
            return 1;
        }).when(stepMapper).insert(any(ContestResolverStepEntity.class));
        lenient().when(stepMapper.selectList(any())).thenAnswer(invocation -> List.copyOf(storedSteps));
    }

    @AfterEach
    void tearDown() {
        SecurityContextHolder.clearContext();
    }

    @Test
    void acmResolverRevealsPostFreezeSubmissionsFromFrozenBottom() {
        authenticate(7L, Role.TEACHER);
        ContestEntity contest = contest(ContestMode.ACM);
        ContestRunEntity run = run(ContestMode.ACM);
        when(contestMapper.selectById(301L)).thenReturn(contest);
        when(contestRunMapper.selectById(401L)).thenReturn(run);
        when(submissionMapper.selectCount(any())).thenReturn(0L);
        when(scoreboardService.createSnapshot(any(), any(), any())).thenReturn(snapshot(7001L), snapshot(7002L));
        when(contestProblemMapper.selectList(any())).thenReturn(List.of(problem()));
        when(contestParticipantMapper.selectList(any())).thenReturn(List.of(
                participant(601L, 91L, "Alice"),
                participant(602L, 92L, "Bob")
        ));
        when(submissionMapper.selectList(any())).thenReturn(List.of(
                submission(1L, 602L, 501L, 30, SubmissionStatus.ACCEPTED),
                submission(2L, 601L, 501L, 70, SubmissionStatus.ACCEPTED),
                submission(3L, 602L, 501L, 80, SubmissionStatus.WRONG_ANSWER)
        ));

        var detail = service.createSession(301L, 401L, null);

        assertEquals(3, detail.steps().size());
        assertEquals(ContestResolverStepType.INITIAL, detail.steps().get(0).stepType());
        assertEquals(ContestResolverStepType.REVEAL, detail.steps().get(1).stepType());
        assertEquals(601L, detail.steps().get(1).participantId());
        assertEquals(2L, detail.steps().get(1).submissionId());
        assertEquals(ContestResolverStepType.FINAL, detail.steps().get(2).stepType());
        assertEquals(602L, detail.steps().get(2).scoreboard().rows().get(0).participantId());
        assertEquals(601L, detail.steps().get(2).scoreboard().rows().get(1).participantId());
    }

    @Test
    void acmResolverCoalescesSameCellToFirstAcceptedSubmission() {
        authenticate(7L, Role.TEACHER);
        when(contestMapper.selectById(301L)).thenReturn(contest(ContestMode.ACM));
        when(contestRunMapper.selectById(401L)).thenReturn(run(ContestMode.ACM));
        when(submissionMapper.selectCount(any())).thenReturn(0L);
        when(scoreboardService.createSnapshot(any(), any(), any())).thenReturn(snapshot(7001L), snapshot(7002L));
        when(contestProblemMapper.selectList(any())).thenReturn(List.of(problem()));
        when(contestParticipantMapper.selectList(any())).thenReturn(List.of(participant(601L, 91L, "Alice")));
        when(submissionMapper.selectList(any())).thenReturn(List.of(
                submission(10L, 601L, 501L, 61, SubmissionStatus.WRONG_ANSWER),
                submission(11L, 601L, 501L, 62, SubmissionStatus.TIME_LIMIT_EXCEEDED),
                submission(12L, 601L, 501L, 63, SubmissionStatus.ACCEPTED),
                submission(13L, 601L, 501L, 64, SubmissionStatus.ACCEPTED)
        ));

        var detail = service.createSession(301L, 401L, null);

        assertEquals(3, detail.steps().size());
        assertEquals(ContestResolverStepType.REVEAL, detail.steps().get(1).stepType());
        assertEquals(12L, detail.steps().get(1).submissionId());
        var cell = detail.steps().get(1).scoreboard().rows().get(0).cells().get(0);
        assertEquals(ContestScoreboardCellStatus.SOLVED, cell.status());
        assertEquals(3, cell.attempts());
        assertEquals(2, cell.wrongAttempts());
        assertEquals(0, cell.pendingAttempts());
        assertEquals(63 * 60_000L, cell.acceptedAtMillis());
    }

    @Test
    void acmResolverCoalescesSameCellFailuresToLastSubmission() {
        authenticate(7L, Role.TEACHER);
        when(contestMapper.selectById(301L)).thenReturn(contest(ContestMode.ACM));
        when(contestRunMapper.selectById(401L)).thenReturn(run(ContestMode.ACM));
        when(submissionMapper.selectCount(any())).thenReturn(0L);
        when(scoreboardService.createSnapshot(any(), any(), any())).thenReturn(snapshot(7001L), snapshot(7002L));
        when(contestProblemMapper.selectList(any())).thenReturn(List.of(problem()));
        when(contestParticipantMapper.selectList(any())).thenReturn(List.of(participant(601L, 91L, "Alice")));
        when(submissionMapper.selectList(any())).thenReturn(List.of(
                submission(20L, 601L, 501L, 61, SubmissionStatus.WRONG_ANSWER),
                submission(21L, 601L, 501L, 62, SubmissionStatus.RUNTIME_ERROR),
                submission(22L, 601L, 501L, 63, SubmissionStatus.COMPILE_ERROR)
        ));

        var detail = service.createSession(301L, 401L, null);

        assertEquals(3, detail.steps().size());
        assertEquals(ContestResolverStepType.REVEAL, detail.steps().get(1).stepType());
        assertEquals(22L, detail.steps().get(1).submissionId());
        var cell = detail.steps().get(1).scoreboard().rows().get(0).cells().get(0);
        assertEquals(ContestScoreboardCellStatus.ATTEMPTED, cell.status());
        assertEquals(3, cell.attempts());
        assertEquals(2, cell.wrongAttempts());
        assertEquals(0, cell.pendingAttempts());
    }

    @Test
    void ioiRunIsRejected() {
        authenticate(7L, Role.TEACHER);
        when(contestMapper.selectById(301L)).thenReturn(contest(ContestMode.IOI));
        when(contestRunMapper.selectById(401L)).thenReturn(run(ContestMode.IOI));

        DomainException error = assertThrows(DomainException.class, () -> service.createSession(301L, 401L, null));

        assertEquals(ErrorCode.BAD_REQUEST, error.errorCode());
    }

    @Test
    void acmRunWithoutFreezeTimeIsRejectedWithSpecificMessage() {
        authenticate(7L, Role.TEACHER);
        ContestRunEntity run = run(ContestMode.ACM);
        run.setFreezeAt(null);
        when(contestMapper.selectById(301L)).thenReturn(contest(ContestMode.ACM));
        when(contestRunMapper.selectById(401L)).thenReturn(run);

        DomainException error = assertThrows(DomainException.class, () -> service.createSession(301L, 401L, null));

        assertEquals(ErrorCode.BAD_REQUEST, error.errorCode());
        assertEquals("Resolver requires a freeze time", error.getMessage());
    }

    private ContestEntity contest(ContestMode mode) {
        ContestEntity contest = new ContestEntity();
        contest.setId(301L);
        contest.setOwnerUserId(7L);
        contest.setTitle("Resolver Contest");
        contest.setMode(mode);
        contest.setStatus(ContestStatus.PUBLISHED);
        contest.setPenaltyMinutes(20);
        contest.setCePenalty(false);
        return contest;
    }

    private ContestRunEntity run(ContestMode mode) {
        Instant start = Instant.now().minusSeconds(10_800);
        ContestRunEntity run = new ContestRunEntity();
        run.setId(401L);
        run.setContestId(301L);
        run.setRunKind(ContestRunKind.FORMAL);
        run.setTitle("Final");
        run.setStatus(ContestRunStatus.ENDED);
        run.setStartAt(start);
        run.setFreezeAt(start.plusSeconds(3600));
        run.setEndAt(start.plusSeconds(7200));
        run.setModeSnapshot(mode);
        run.setPenaltyMinutesSnapshot(20);
        run.setCePenaltySnapshot(false);
        run.setCreatedBy(7L);
        return run;
    }

    private ContestProblemEntity problem() {
        ContestProblemEntity problem = new ContestProblemEntity();
        problem.setId(501L);
        problem.setContestId(301L);
        problem.setProblemId(801L);
        problem.setLabel("A");
        problem.setDisplayTitle("A + B");
        problem.setScore(1);
        problem.setSortOrder(1);
        return problem;
    }

    private ContestParticipantEntity participant(Long id, Long userId, String name) {
        ContestParticipantEntity participant = new ContestParticipantEntity();
        participant.setId(id);
        participant.setContestId(301L);
        participant.setContestRunId(401L);
        participant.setUserId(userId);
        participant.setParticipantType(ContestParticipantType.INDIVIDUAL);
        participant.setStatus(ContestParticipantStatus.ACTIVE);
        participant.setAccountSnapshot(name.toLowerCase());
        participant.setDisplayNameSnapshot(name);
        return participant;
    }

    private SubmissionEntity submission(Long id, Long participantId, Long contestProblemId, long minute, SubmissionStatus status) {
        SubmissionEntity submission = new SubmissionEntity();
        submission.setId(id);
        submission.setContestId(301L);
        submission.setContestRunId(401L);
        submission.setContestParticipantId(participantId);
        submission.setContestProblemId(contestProblemId);
        submission.setSubmittedAtContestMillis(minute * 60_000L);
        submission.setVisibleToParticipant(true);
        submission.setStatus(status);
        return submission;
    }

    private ContestScoreboardResponse snapshot(Long id) {
        return new ContestScoreboardResponse(301L, 401L, ContestMode.ACM, ContestScoreboardView.PUBLIC, id,
                ContestScoreboardSnapshotKind.MANUAL, 7_200_000L, Instant.now(), false, 3_600_000L,
                20, false, List.of(), List.of());
    }

    private void authenticate(Long userId, Role role) {
        SecurityPrincipal principal = new SecurityPrincipal(userId, "user-" + userId, Set.of(role));
        SecurityContextHolder.getContext().setAuthentication(new UsernamePasswordAuthenticationToken(
                principal, "n/a", Set.of(new SimpleGrantedAuthority("ROLE_" + role.name()))));
    }
}
