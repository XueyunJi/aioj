package com.aioj.next.problem.domain;

import com.aioj.next.common.error.DomainException;
import com.aioj.next.common.error.ErrorCode;
import com.aioj.next.common.security.Role;
import com.aioj.next.common.security.SecurityPrincipal;
import com.aioj.next.contract.contest.ContestMode;
import com.aioj.next.contract.contest.ContestParticipantStatus;
import com.aioj.next.contract.contest.ContestParticipantType;
import com.aioj.next.contract.contest.ContestStatus;
import com.aioj.next.contract.contest.ContestVisibility;
import com.aioj.next.contract.contest.SubmissionCodeAccessRequest;
import com.aioj.next.contract.submission.SubmissionStatus;
import com.aioj.next.problem.persistence.entity.ContestEntity;
import com.aioj.next.problem.persistence.entity.ContestParticipantEntity;
import com.aioj.next.problem.persistence.entity.ContestProblemEntity;
import com.aioj.next.problem.persistence.entity.SubmissionCodeAccessLogEntity;
import com.aioj.next.problem.persistence.entity.SubmissionEntity;
import com.aioj.next.problem.persistence.entity.UserAccountEntity;
import com.aioj.next.problem.persistence.mapper.ContestMapper;
import com.aioj.next.problem.persistence.mapper.ContestParticipantMapper;
import com.aioj.next.problem.persistence.mapper.ContestProblemMapper;
import com.aioj.next.problem.persistence.mapper.SubmissionCaseResultMapper;
import com.aioj.next.problem.persistence.mapper.SubmissionCodeAccessLogMapper;
import com.aioj.next.problem.persistence.mapper.SubmissionMapper;
import com.aioj.next.problem.persistence.mapper.UserAccountMapper;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
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

import java.time.Instant;
import java.util.List;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class ContestSubmissionAccessServiceTest {
    @Mock
    private ContestMapper contestMapper;
    @Mock
    private ContestProblemMapper contestProblemMapper;
    @Mock
    private ContestParticipantMapper contestParticipantMapper;
    @Mock
    private SubmissionMapper submissionMapper;
    @Mock
    private SubmissionCaseResultMapper caseResultMapper;
    @Mock
    private SubmissionCodeAccessLogMapper accessLogMapper;
    @Mock
    private UserAccountMapper userAccountMapper;
    @Mock
    private OperationAuditService operationAuditService;

    private ContestSubmissionAccessService service;

    @BeforeEach
    void setUp() {
        service = new ContestSubmissionAccessService(contestMapper, contestProblemMapper, contestParticipantMapper,
                submissionMapper, caseResultMapper, accessLogMapper, userAccountMapper, operationAuditService);
        lenient().when(caseResultMapper.selectList(any())).thenReturn(List.of());
    }

    @AfterEach
    void tearDown() {
        SecurityContextHolder.clearContext();
    }

    @Test
    void teacherCanListOwnContestSubmissionsWithoutSourceCode() {
        authenticate(7L, Role.TEACHER);
        when(contestMapper.selectById(301L)).thenReturn(contest(7L));
        when(submissionMapper.selectPage(any(), any())).thenReturn(page(List.of(submission(801L))));
        when(contestProblemMapper.selectList(any())).thenReturn(List.of(problem()));
        when(contestParticipantMapper.selectList(any())).thenReturn(List.of(participant()));

        var result = service.listSubmissions(301L, 1, 20, null, null, null, null, null);

        assertEquals(1, result.total());
        assertEquals("A", result.records().get(0).problemLabel());
        assertEquals("Alice", result.records().get(0).displayNameSnapshot());
        assertFalse(result.records().get(0).codeIncluded());
        assertNull(result.records().get(0).code());
    }

    @Test
    void unrelatedTeacherCannotListContestSubmissions() {
        authenticate(8L, Role.TEACHER);
        when(contestMapper.selectById(301L)).thenReturn(contest(7L));

        DomainException error = assertThrows(DomainException.class,
                () -> service.listSubmissions(301L, 1, 20, null, null, null, null, null));

        assertEquals(ErrorCode.FORBIDDEN, error.errorCode());
    }

    @Test
    void adminCanReadAnyContestSubmissionDetailWithoutSourceCode() {
        authenticate(99L, Role.ADMIN);
        when(contestMapper.selectById(301L)).thenReturn(contest(7L));
        when(submissionMapper.selectOne(any())).thenReturn(submission(801L));
        when(contestProblemMapper.selectList(any())).thenReturn(List.of(problem()));
        when(contestParticipantMapper.selectList(any())).thenReturn(List.of(participant()));

        var result = service.getSubmission(301L, 801L);

        assertEquals(801L, result.id());
        assertFalse(result.codeIncluded());
        assertNull(result.code());
        assertNull(result.stdoutExcerpt());
    }

    @Test
    void sourceCodeAccessWritesAuditLogAndReturnsCode() {
        authenticate(7L, Role.TEACHER);
        when(contestMapper.selectById(301L)).thenReturn(contest(7L));
        when(submissionMapper.selectOne(any())).thenReturn(submission(801L));
        when(contestProblemMapper.selectList(any())).thenReturn(List.of(problem()));
        when(contestParticipantMapper.selectList(any())).thenReturn(List.of(participant()));
        doAnswer(invocation -> {
            SubmissionCodeAccessLogEntity log = invocation.getArgument(0);
            log.setId(901L);
            return 1;
        }).when(accessLogMapper).insert(any(SubmissionCodeAccessLogEntity.class));

        var result = service.accessCode(301L, 801L, new SubmissionCodeAccessRequest(""));

        assertEquals(901L, result.auditLogId());
        assertEquals("int main(){}", result.submission().code());
        assertEquals("stdout", result.submission().stdoutExcerpt());
        ArgumentCaptor<SubmissionCodeAccessLogEntity> captor = ArgumentCaptor.forClass(SubmissionCodeAccessLogEntity.class);
        verify(accessLogMapper).insert(captor.capture());
        assertEquals(7L, captor.getValue().getViewerUserId());
        assertEquals(91L, captor.getValue().getTargetUserId());
        assertEquals(601L, captor.getValue().getContestParticipantId());
        assertNull(captor.getValue().getReason());
        assertNotNull(captor.getValue().getCreatedAt());
    }

    @Test
    void submissionOutsideContestIsNotExposed() {
        authenticate(7L, Role.TEACHER);
        when(contestMapper.selectById(301L)).thenReturn(contest(7L));
        when(submissionMapper.selectOne(any())).thenReturn(null);

        DomainException error = assertThrows(DomainException.class,
                () -> service.accessCode(301L, 999L, new SubmissionCodeAccessRequest("review")));

        assertEquals(ErrorCode.NOT_FOUND, error.errorCode());
    }

    @Test
    void studentCannotUseManagedContestSubmissionAccess() {
        authenticate(91L, Role.STUDENT);
        when(contestMapper.selectById(301L)).thenReturn(contest(7L));

        DomainException error = assertThrows(DomainException.class,
                () -> service.listSubmissions(301L, 1, 20, null, null, null, null, null));

        assertEquals(ErrorCode.FORBIDDEN, error.errorCode());
    }

    @Test
    void accessLogListIncludesViewerAndSubmissionSnapshots() {
        authenticate(7L, Role.TEACHER);
        when(contestMapper.selectById(301L)).thenReturn(contest(7L));
        when(accessLogMapper.selectPage(any(), any())).thenReturn(logPage());
        when(submissionMapper.selectBatchIds(any())).thenReturn(List.of(submission(801L)));
        when(userAccountMapper.selectBatchIds(any())).thenReturn(List.of(viewer()));
        when(contestProblemMapper.selectList(any())).thenReturn(List.of(problem()));
        when(contestParticipantMapper.selectList(any())).thenReturn(List.of(participant()));

        var result = service.listAccessLogs(301L, 1, 20, null, null, null);

        assertEquals(1, result.total());
        assertEquals("teacher", result.records().get(0).viewerAccount());
        assertEquals("Alice", result.records().get(0).targetDisplayNameSnapshot());
        assertEquals("A", result.records().get(0).problemLabel());
    }

    private Page<SubmissionEntity> page(List<SubmissionEntity> records) {
        Page<SubmissionEntity> page = new Page<>(1, 20);
        page.setRecords(records);
        page.setTotal(records.size());
        return page;
    }

    private Page<SubmissionCodeAccessLogEntity> logPage() {
        SubmissionCodeAccessLogEntity log = new SubmissionCodeAccessLogEntity();
        log.setId(901L);
        log.setContestId(301L);
        log.setSubmissionId(801L);
        log.setViewerUserId(7L);
        log.setTargetUserId(91L);
        log.setContestParticipantId(601L);
        log.setReason("review");
        log.setTraceId("trace-1");
        log.setCreatedAt(Instant.parse("2026-06-10T10:00:00Z"));
        Page<SubmissionCodeAccessLogEntity> page = new Page<>(1, 20);
        page.setRecords(List.of(log));
        page.setTotal(1);
        return page;
    }

    private ContestEntity contest(Long ownerId) {
        ContestEntity contest = new ContestEntity();
        contest.setId(301L);
        contest.setOwnerUserId(ownerId);
        contest.setScopeGroupId(101L);
        contest.setTitle("Spring Invitational");
        contest.setMode(ContestMode.ACM);
        contest.setStatus(ContestStatus.PUBLISHED);
        contest.setVisibility(ContestVisibility.GROUP);
        contest.setStartAt(Instant.parse("2026-06-10T09:00:00Z"));
        contest.setEndAt(Instant.parse("2026-06-10T14:00:00Z"));
        return contest;
    }

    private ContestProblemEntity problem() {
        ContestProblemEntity problem = new ContestProblemEntity();
        problem.setId(401L);
        problem.setContestId(301L);
        problem.setProblemId(1001L);
        problem.setLabel("A");
        problem.setDisplayTitle("Warmup");
        problem.setScore(0);
        problem.setSortOrder(0);
        return problem;
    }

    private ContestParticipantEntity participant() {
        ContestParticipantEntity participant = new ContestParticipantEntity();
        participant.setId(601L);
        participant.setContestId(301L);
        participant.setUserId(91L);
        participant.setParticipantType(ContestParticipantType.INDIVIDUAL);
        participant.setStatus(ContestParticipantStatus.ACTIVE);
        participant.setAccountSnapshot("alice");
        participant.setDisplayNameSnapshot("Alice");
        participant.setEmailSnapshot("alice@example.com");
        return participant;
    }

    private SubmissionEntity submission(Long id) {
        SubmissionEntity submission = new SubmissionEntity();
        submission.setId(id);
        submission.setContestId(301L);
        submission.setContestProblemId(401L);
        submission.setContestParticipantId(601L);
        submission.setProblemId(1001L);
        submission.setUserId(91L);
        submission.setSubmittedAtContestMillis(1_800_000L);
        submission.setLanguage("cpp");
        submission.setCode("int main(){}");
        submission.setStatus(SubmissionStatus.ACCEPTED);
        submission.setJudgeMessage("Accepted");
        submission.setTimeMillis(12L);
        submission.setMemoryKb(2048L);
        submission.setStdoutExcerpt("stdout");
        submission.setStderrExcerpt("stderr");
        submission.setExitStatus(0);
        submission.setRunTimeMillis(12L);
        submission.setCreatedAt(Instant.parse("2026-06-10T09:30:00Z"));
        submission.setJudgedAt(Instant.parse("2026-06-10T09:31:00Z"));
        return submission;
    }

    private UserAccountEntity viewer() {
        UserAccountEntity user = new UserAccountEntity();
        user.setId(7L);
        user.setAccount("teacher");
        user.setDisplayName("Teacher");
        user.setEnabled(true);
        return user;
    }

    private void authenticate(Long userId, Role role) {
        SecurityPrincipal principal = new SecurityPrincipal(userId, "user" + userId, Set.of(role));
        SecurityContextHolder.getContext().setAuthentication(new UsernamePasswordAuthenticationToken(principal, "n/a",
                Set.of(new SimpleGrantedAuthority("ROLE_" + role.name()))));
    }
}
