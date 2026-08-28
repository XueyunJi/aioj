package com.aioj.next.problem.domain;

import com.aioj.next.contract.contest.ContestAiPolicyMode;
import com.aioj.next.contract.contest.ContestParticipantStatus;
import com.aioj.next.contract.contest.ContestRunStatus;
import com.aioj.next.contract.contest.RunningContestParticipation;
import com.aioj.next.contract.contest.RunningContestProblemStatement;
import com.aioj.next.contract.problem.ProblemVisibility;
import com.aioj.next.problem.config.ContestProperties;
import com.aioj.next.problem.persistence.entity.ContestParticipantEntity;
import com.aioj.next.problem.persistence.entity.ContestRunEntity;
import com.aioj.next.problem.persistence.entity.ContestRunProblemSnapshotEntity;
import com.aioj.next.problem.persistence.mapper.ContestParticipantMapper;
import com.aioj.next.problem.persistence.mapper.ContestProblemMapper;
import com.aioj.next.problem.persistence.mapper.ContestRunMapper;
import com.aioj.next.problem.persistence.mapper.ContestRunProblemSnapshotMapper;
import com.aioj.next.problem.persistence.mapper.ProblemMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.time.Instant;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class ContestProblemVisibilityServiceTest {
    @Mock
    private ContestRunMapper runMapper;
    @Mock
    private ContestProblemMapper contestProblemMapper;
    @Mock
    private ContestParticipantMapper participantMapper;
    @Mock
    private ProblemMapper problemMapper;
    @Mock
    private ContestRunProblemSnapshotMapper runProblemSnapshotMapper;

    private ContestProblemVisibilityService service;

    @BeforeEach
    void setUp() {
        service = new ContestProblemVisibilityService(runMapper, contestProblemMapper, participantMapper,
                problemMapper, runProblemSnapshotMapper, new ContestProperties());
    }

    @Test
    void runningStatementsMergeSameProblemAcrossRunsWithStrictestMode() {
        Instant now = Instant.now();
        ContestRunEntity runOne = run(401L, 301L, "Run One", now.minusSeconds(3600), now.plusSeconds(3600),
                ContestAiPolicyMode.DEFAULT, "notes one");
        ContestRunEntity runTwo = run(402L, 302L, "Run Two", now.minusSeconds(3600), now.plusSeconds(3600),
                ContestAiPolicyMode.STRICT, "notes two");
        when(runMapper.selectList(any())).thenReturn(List.of(runOne, runTwo));
        when(runProblemSnapshotMapper.selectList(any())).thenReturn(List.of(
                snapshot(801L, 301L, 401L, 601L, 1001L, "shared statement", ProblemVisibility.PUBLIC),
                snapshot(802L, 302L, 402L, 602L, 1001L, "shared statement", ProblemVisibility.PRIVATE),
                snapshot(803L, 301L, 401L, 603L, 1002L, "other statement", ProblemVisibility.PUBLIC)));

        List<RunningContestProblemStatement> statements = service.runningProblemStatements(now, 4000);

        assertEquals(2, statements.size());
        RunningContestProblemStatement merged = statements.get(0);
        assertEquals(1001L, merged.problemId());
        assertEquals(ProblemVisibility.PRIVATE, merged.visibility());
        assertEquals(ContestAiPolicyMode.STRICT, merged.aiPolicyMode());
        assertTrue(merged.aiPolicyNotes().contains("[Run One] notes one"));
        assertTrue(merged.aiPolicyNotes().contains("[Run Two] notes two"));
        assertEquals(2, merged.occurrences().size());
        assertEquals(301L, merged.contestId());
        assertEquals(401L, merged.contestRunId());
        assertEquals(601L, merged.contestProblemId());
        RunningContestProblemStatement other = statements.get(1);
        assertEquals(1002L, other.problemId());
        assertEquals(ProblemVisibility.PUBLIC, other.visibility());
        assertEquals(ContestAiPolicyMode.DEFAULT, other.aiPolicyMode());
        assertEquals("[Run One] notes one", other.aiPolicyNotes());
        assertEquals(1, other.occurrences().size());
    }

    @Test
    void runningStatementsMergeDefaultOverDisabledAcrossRuns() {
        // 多比赛归属确认（P3-6 frozen §5.5）：同一题被两个进行中的 run 使用、对话无法
        // 确认归属哪个 run 时，合并取更严格策略（取更严优先于 clarification，本轮不做澄清）。
        Instant now = Instant.now();
        ContestRunEntity defaultRun = run(401L, 301L, "Default Run", now.minusSeconds(3600), now.plusSeconds(3600),
                ContestAiPolicyMode.DEFAULT, null);
        ContestRunEntity disabledRun = run(402L, 302L, "Disabled Run", now.minusSeconds(3600), now.plusSeconds(3600),
                ContestAiPolicyMode.DISABLED, null);
        when(runMapper.selectList(any())).thenReturn(List.of(defaultRun, disabledRun));
        when(runProblemSnapshotMapper.selectList(any())).thenReturn(List.of(
                snapshot(801L, 301L, 401L, 601L, 1001L, "shared statement", ProblemVisibility.PUBLIC),
                snapshot(802L, 302L, 402L, 602L, 1001L, "shared statement", ProblemVisibility.PUBLIC)));

        List<RunningContestProblemStatement> statements = service.runningProblemStatements(now, 4000);

        assertEquals(1, statements.size());
        assertEquals(ContestAiPolicyMode.DEFAULT, statements.get(0).aiPolicyMode());
        assertEquals(2, statements.get(0).occurrences().size());
    }

    @Test
    void runningStatementsStayDisabledWhenEveryRunIsDisabled() {
        // 归属确认的反例边界：所有进行中 run 都 DISABLED 时合并仍为 DISABLED——取更严
        // 不等于一律收紧，DISABLED-only 题目不做全局粗暴禁止。
        Instant now = Instant.now();
        ContestRunEntity disabledOne = run(401L, 301L, "Disabled One", now.minusSeconds(3600), now.plusSeconds(3600),
                ContestAiPolicyMode.DISABLED, null);
        ContestRunEntity disabledTwo = run(402L, 302L, "Disabled Two", now.minusSeconds(3600), now.plusSeconds(3600),
                ContestAiPolicyMode.DISABLED, null);
        when(runMapper.selectList(any())).thenReturn(List.of(disabledOne, disabledTwo));
        when(runProblemSnapshotMapper.selectList(any())).thenReturn(List.of(
                snapshot(801L, 301L, 401L, 601L, 1001L, "shared statement", ProblemVisibility.PUBLIC),
                snapshot(802L, 302L, 402L, 602L, 1001L, "shared statement", ProblemVisibility.PUBLIC)));

        List<RunningContestProblemStatement> statements = service.runningProblemStatements(now, 4000);

        assertEquals(1, statements.size());
        assertEquals(ContestAiPolicyMode.DISABLED, statements.get(0).aiPolicyMode());
    }

    @Test
    void runningStatementsKeepRunsInsideGraceWindowOnly() {
        Instant now = Instant.now();
        ContestRunEntity withinGrace = run(401L, 301L, "Grace Run", now.minusSeconds(3600), now.minusSeconds(300),
                ContestAiPolicyMode.DEFAULT, null);
        ContestRunEntity pastGrace = run(402L, 302L, "Old Run", now.minusSeconds(3600), now.minusSeconds(700),
                ContestAiPolicyMode.DEFAULT, null);
        when(runMapper.selectList(any())).thenReturn(List.of(withinGrace, pastGrace));
        when(runProblemSnapshotMapper.selectList(any())).thenReturn(List.of(
                snapshot(801L, 301L, 401L, 601L, 1001L, "grace statement", ProblemVisibility.PUBLIC)));

        List<RunningContestProblemStatement> statements = service.runningProblemStatements(now, 4000);

        assertEquals(1, statements.size());
        assertEquals(1001L, statements.get(0).problemId());
        assertNull(statements.get(0).aiPolicyNotes());
    }

    @Test
    void runningParticipationsIncludeGraceWindowRunsAndExposeRunWindow() {
        Instant now = Instant.now();
        ContestRunEntity withinGrace = run(401L, 301L, "Grace Run", now.minusSeconds(3600), now.minusSeconds(300),
                ContestAiPolicyMode.DEFAULT, null);
        ContestRunEntity pastGrace = run(402L, 302L, "Old Run", now.minusSeconds(3600), now.minusSeconds(700),
                ContestAiPolicyMode.DEFAULT, null);
        when(runMapper.selectList(any())).thenReturn(List.of(withinGrace, pastGrace));
        ContestParticipantEntity participation = new ContestParticipantEntity();
        participation.setId(501L);
        participation.setContestId(301L);
        participation.setContestRunId(401L);
        participation.setUserId(9L);
        participation.setStatus(ContestParticipantStatus.ACTIVE);
        when(participantMapper.selectList(any())).thenReturn(List.of(participation));

        List<RunningContestParticipation> participations = service.runningParticipations(9L, now);

        assertEquals(1, participations.size());
        assertEquals(301L, participations.get(0).contestId());
        assertEquals(401L, participations.get(0).contestRunId());
        assertEquals(withinGrace.getStartAt(), participations.get(0).startAt());
        assertEquals(withinGrace.getEndAt(), participations.get(0).endAt());
    }

    @Test
    void runningParticipationsExcludeRunsOutsideGraceWindow() {
        Instant now = Instant.now();
        ContestRunEntity pastGrace = run(402L, 302L, "Old Run", now.minusSeconds(3600), now.minusSeconds(700),
                ContestAiPolicyMode.DEFAULT, null);
        when(runMapper.selectList(any())).thenReturn(List.of(pastGrace));

        List<RunningContestParticipation> participations = service.runningParticipations(9L, now);

        assertTrue(participations.isEmpty());
    }

    @Test
    void runningParticipationsSortInProgressRunsBeforeGraceTails() {
        Instant now = Instant.now();
        ContestRunEntity graceTail = run(401L, 301L, "Old Run", now.minusSeconds(3600), now.minusSeconds(300),
                ContestAiPolicyMode.DEFAULT, null);
        ContestRunEntity inProgress = run(402L, 301L, "New Run", now.minusSeconds(600), now.plusSeconds(3600),
                ContestAiPolicyMode.DEFAULT, null);
        when(runMapper.selectList(any())).thenReturn(List.of(graceTail, inProgress));
        when(participantMapper.selectList(any())).thenReturn(List.of(
                participation(501L, 301L, 401L), participation(502L, 301L, 402L)));

        List<RunningContestParticipation> participations = service.runningParticipations(9L, now);

        assertEquals(2, participations.size());
        assertEquals(402L, participations.get(0).contestRunId());
        assertEquals(401L, participations.get(1).contestRunId());
    }

    @Test
    void mergedParticipationStatementPrefersInProgressRunCoordinates() {
        Instant now = Instant.now();
        ContestRunEntity graceTail = run(401L, 301L, "Old Run", now.minusSeconds(3600), now.minusSeconds(300),
                ContestAiPolicyMode.DEFAULT, null);
        ContestRunEntity inProgress = run(402L, 301L, "New Run", now.minusSeconds(600), now.plusSeconds(3600),
                ContestAiPolicyMode.DEFAULT, null);
        when(runMapper.selectList(any())).thenReturn(List.of(graceTail, inProgress));
        when(participantMapper.selectList(any())).thenReturn(List.of(
                participation(501L, 301L, 401L), participation(502L, 301L, 402L)));
        when(runProblemSnapshotMapper.selectList(any())).thenReturn(List.of(
                snapshot(801L, 301L, 401L, 601L, 1001L, "shared statement", ProblemVisibility.PUBLIC),
                snapshot(802L, 301L, 402L, 602L, 1001L, "shared statement", ProblemVisibility.PUBLIC)));

        List<RunningContestProblemStatement> statements = service.runningParticipationProblemStatements(9L, now, 4000);

        assertEquals(1, statements.size());
        RunningContestProblemStatement merged = statements.get(0);
        assertEquals(1001L, merged.problemId());
        assertEquals(301L, merged.contestId());
        assertEquals(402L, merged.contestRunId());
        assertEquals(602L, merged.contestProblemId());
        assertEquals(2, merged.occurrences().size());
    }

    private ContestParticipantEntity participation(Long id, Long contestId, Long runId) {
        ContestParticipantEntity participation = new ContestParticipantEntity();
        participation.setId(id);
        participation.setContestId(contestId);
        participation.setContestRunId(runId);
        participation.setUserId(9L);
        participation.setStatus(ContestParticipantStatus.ACTIVE);
        return participation;
    }

    private ContestRunEntity run(Long id, Long contestId, String title, Instant startAt, Instant endAt,
                                 ContestAiPolicyMode aiPolicyMode, String aiPolicyNotes) {
        ContestRunEntity run = new ContestRunEntity();
        run.setId(id);
        run.setContestId(contestId);
        run.setTitle(title);
        run.setStatus(ContestRunStatus.SCHEDULED);
        run.setStartAt(startAt);
        run.setEndAt(endAt);
        run.setAiPolicyModeSnapshot(aiPolicyMode);
        run.setAiPolicyNotesSnapshot(aiPolicyNotes);
        return run;
    }

    private ContestRunProblemSnapshotEntity snapshot(Long id, Long contestId, Long runId, Long contestProblemId,
                                                     Long problemId, String statement, ProblemVisibility visibility) {
        ContestRunProblemSnapshotEntity snapshot = new ContestRunProblemSnapshotEntity();
        snapshot.setId(id);
        snapshot.setContestId(contestId);
        snapshot.setContestRunId(runId);
        snapshot.setContestProblemId(contestProblemId);
        snapshot.setProblemId(problemId);
        snapshot.setStatement(statement);
        snapshot.setVisibility(visibility);
        snapshot.setCreatedAt(Instant.now());
        return snapshot;
    }
}
