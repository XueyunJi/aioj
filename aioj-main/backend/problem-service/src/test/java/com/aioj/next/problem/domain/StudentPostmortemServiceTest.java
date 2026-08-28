package com.aioj.next.problem.domain;

import com.aioj.next.common.error.DomainException;
import com.aioj.next.common.error.ErrorCode;
import com.aioj.next.common.security.Role;
import com.aioj.next.common.security.SecurityPrincipal;
import com.aioj.next.contract.ai.StudentPostmortemAnalysisRequest;
import com.aioj.next.contract.ai.StudentPostmortemAnalysisResponse;
import com.aioj.next.contract.ai.StudentPostmortemWeaknessConfirmRequest;
import com.aioj.next.contract.ai.StudentPostmortemWeaknessConfirmResponse;
import com.aioj.next.contract.contest.ContestMode;
import com.aioj.next.contract.contest.ContestParticipantStatus;
import com.aioj.next.contract.contest.ContestPostmortemAiStatus;
import com.aioj.next.contract.contest.ContestPostmortemReportStatus;
import com.aioj.next.contract.contest.ContestRunKind;
import com.aioj.next.contract.contest.ContestRunStatus;
import com.aioj.next.contract.contest.ContestStudentPostmortemWeaknessCandidateStatus;
import com.aioj.next.contract.submission.SubmissionStatus;
import com.aioj.next.problem.domain.postmortem.StudentPostmortemAiClient;
import com.aioj.next.problem.persistence.entity.ContestEntity;
import com.aioj.next.problem.persistence.entity.ContestParticipantEntity;
import com.aioj.next.problem.persistence.entity.ContestRunEntity;
import com.aioj.next.problem.persistence.entity.ContestRunProblemSnapshotEntity;
import com.aioj.next.problem.persistence.entity.ContestStudentPostmortemReportEntity;
import com.aioj.next.problem.persistence.entity.ContestStudentPostmortemWeaknessCandidateEntity;
import com.aioj.next.problem.persistence.entity.SubmissionEntity;
import com.aioj.next.problem.persistence.mapper.ContestMapper;
import com.aioj.next.problem.persistence.mapper.ContestParticipantMapper;
import com.aioj.next.problem.persistence.mapper.ContestRunMapper;
import com.aioj.next.problem.persistence.mapper.ContestRunProblemSnapshotMapper;
import com.aioj.next.problem.persistence.mapper.ContestStudentPostmortemReportMapper;
import com.aioj.next.problem.persistence.mapper.ContestStudentPostmortemWeaknessCandidateMapper;
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

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class StudentPostmortemServiceTest {
    @Mock
    private ContestMapper contestMapper;
    @Mock
    private ContestRunMapper contestRunMapper;
    @Mock
    private ContestParticipantMapper participantMapper;
    @Mock
    private ContestRunProblemSnapshotMapper problemSnapshotMapper;
    @Mock
    private SubmissionMapper submissionMapper;
    @Mock
    private SubmissionCaseResultMapper caseResultMapper;
    @Mock
    private ContestStudentPostmortemReportMapper reportMapper;
    @Mock
    private ContestStudentPostmortemWeaknessCandidateMapper candidateMapper;
    @Mock
    private StudentPostmortemAiClient aiClient;
    @Mock
    private ContestProblemVisibilityService visibilityService;

    private StudentPostmortemService service;

    @BeforeEach
    void setUp() {
        org.mockito.Mockito.lenient().when(visibilityService.hiddenContestProblemIdsForRun(org.mockito.ArgumentMatchers.any(), org.mockito.ArgumentMatchers.any()))
                .thenReturn(java.util.Set.of());
        service = new StudentPostmortemService(contestMapper, contestRunMapper, participantMapper,
                problemSnapshotMapper, submissionMapper, caseResultMapper, reportMapper, candidateMapper,
                aiClient, new ObjectMapper().findAndRegisterModules(), visibilityService);
    }

    @AfterEach
    void tearDown() {
        SecurityContextHolder.clearContext();
    }

    @Test
    void acceptingOwnedWeaknessCandidateWritesConfirmedMemory() {
        authenticate(91L, Role.STUDENT);
        when(reportMapper.selectOne(any())).thenReturn(report(91L));
        when(candidateMapper.selectOne(any())).thenReturn(candidate(91L));
        when(aiClient.confirmWeakness(any())).thenReturn(new StudentPostmortemWeaknessConfirmResponse(701L, 801L));

        var response = service.acceptCandidate(301L, 401L, 501L, 601L);

        assertEquals(ContestStudentPostmortemWeaknessCandidateStatus.ACCEPTED, response.status());
        assertEquals(701L, response.memoryId());
        assertEquals(801L, response.weaknessId());
        assertNotNull(response.decidedAt());

        ArgumentCaptor<StudentPostmortemWeaknessConfirmRequest> requestCaptor =
                ArgumentCaptor.forClass(StudentPostmortemWeaknessConfirmRequest.class);
        verify(aiClient).confirmWeakness(requestCaptor.capture());
        assertEquals(91L, requestCaptor.getValue().userId());
        assertEquals("binary_search", requestCaptor.getValue().knowledgeNode());

        ArgumentCaptor<ContestStudentPostmortemWeaknessCandidateEntity> candidateCaptor =
                ArgumentCaptor.forClass(ContestStudentPostmortemWeaknessCandidateEntity.class);
        verify(candidateMapper).updateById(candidateCaptor.capture());
        assertEquals(ContestStudentPostmortemWeaknessCandidateStatus.ACCEPTED, candidateCaptor.getValue().getStatus());
        assertEquals(701L, candidateCaptor.getValue().getMemoryId());
        assertEquals(801L, candidateCaptor.getValue().getWeaknessId());
    }

    @Test
    void studentCannotAcceptAnotherStudentsWeaknessCandidate() {
        authenticate(92L, Role.STUDENT);
        when(reportMapper.selectOne(any())).thenReturn(report(91L));

        DomainException error = assertThrows(DomainException.class,
                () -> service.acceptCandidate(301L, 401L, 501L, 601L));

        assertEquals(ErrorCode.FORBIDDEN, error.errorCode());
        verify(aiClient, never()).confirmWeakness(any());
        verify(candidateMapper, never()).updateById(any(ContestStudentPostmortemWeaknessCandidateEntity.class));
    }

    @Test
    void acmReportSendsRepresentativeCodeAndScoreFreeStatisticsToAi() {
        authenticate(91L, Role.STUDENT);
        ContestEntity contest = contest(ContestMode.ACM);
        ContestRunEntity run = run(ContestMode.ACM);
        when(contestMapper.selectById(301L)).thenReturn(contest);
        when(contestRunMapper.selectOne(any())).thenReturn(run);
        when(participantMapper.selectOne(any())).thenReturn(participant());
        when(submissionMapper.selectCount(any())).thenReturn(0L);
        when(problemSnapshotMapper.selectList(any())).thenReturn(List.of(problemSnapshot()));
        when(submissionMapper.selectList(any())).thenReturn(List.of(
                submission(101L, SubmissionStatus.ACCEPTED, 1000L, "int old_ac() { return 0; }"),
                submission(102L, SubmissionStatus.ACCEPTED, 2000L, "int latest_ac() { return 0; }"),
                submission(103L, SubmissionStatus.WRONG_ANSWER, 3000L, "int later_wa() { return 1; }")
        ));
        when(caseResultMapper.selectList(any())).thenReturn(List.of());
        doAnswer(invocation -> {
            ContestStudentPostmortemReportEntity inserted = invocation.getArgument(0);
            inserted.setId(501L);
            return 1;
        }).when(reportMapper).insert(any(ContestStudentPostmortemReportEntity.class));
        when(aiClient.analyze(any())).thenReturn(new StudentPostmortemAnalysisResponse(
                "## 个人成绩概览", List.of(), List.of(), "mock", "mock-model", 12, 6, true, null));
        when(candidateMapper.selectList(any())).thenReturn(List.of());

        service.createMyReport(301L, 401L);

        ArgumentCaptor<StudentPostmortemAnalysisRequest> requestCaptor =
                ArgumentCaptor.forClass(StudentPostmortemAnalysisRequest.class);
        verify(aiClient).analyze(requestCaptor.capture());
        StudentPostmortemAnalysisRequest request = requestCaptor.getValue();
        assertEquals("ACM", request.mode());
        assertEquals(1, request.representativeCodeReferences().size());
        assertEquals(102L, request.representativeCodeReferences().get(0).submissionId());
        assertEquals("int latest_ac() { return 0; }", request.representativeCodeReferences().get(0).codeExcerpt());
        assertFalse(request.statisticsJson().contains("totalScore"));
        assertFalse(request.statisticsJson().contains("bestScore"));
        assertFalse(request.statisticsJson().contains("caseSummary"));
        assertFalse(request.summaryText().contains("总分"));
        assertFalse(request.summaryText().contains("得分"));
    }

    @Test
    void ioiReportKeepsScoreStatisticsAndDoesNotSendCodeReferences() {
        authenticate(91L, Role.STUDENT);
        ContestEntity contest = contest(ContestMode.IOI);
        ContestRunEntity run = run(ContestMode.IOI);
        when(contestMapper.selectById(301L)).thenReturn(contest);
        when(contestRunMapper.selectOne(any())).thenReturn(run);
        when(participantMapper.selectOne(any())).thenReturn(participant());
        when(submissionMapper.selectCount(any())).thenReturn(0L);
        when(problemSnapshotMapper.selectList(any())).thenReturn(List.of(problemSnapshot()));
        when(submissionMapper.selectList(any())).thenReturn(List.of(
                submission(101L, SubmissionStatus.WRONG_ANSWER, 1000L, "int partial() { return 0; }")
        ));
        when(caseResultMapper.selectList(any())).thenReturn(List.of());
        doAnswer(invocation -> {
            ContestStudentPostmortemReportEntity inserted = invocation.getArgument(0);
            inserted.setId(502L);
            return 1;
        }).when(reportMapper).insert(any(ContestStudentPostmortemReportEntity.class));
        when(aiClient.analyze(any())).thenReturn(new StudentPostmortemAnalysisResponse(
                "## 个人成绩概览", List.of(), List.of(), "mock", "mock-model", 12, 6, true, null));
        when(candidateMapper.selectList(any())).thenReturn(List.of());

        service.createMyReport(301L, 401L);

        ArgumentCaptor<StudentPostmortemAnalysisRequest> requestCaptor =
                ArgumentCaptor.forClass(StudentPostmortemAnalysisRequest.class);
        verify(aiClient).analyze(requestCaptor.capture());
        StudentPostmortemAnalysisRequest request = requestCaptor.getValue();
        assertEquals("IOI", request.mode());
        assertEquals(0, request.representativeCodeReferences().size());
        assertFalse(request.statisticsJson().isBlank());
        assertEquals(true, request.statisticsJson().contains("totalScore"));
        assertEquals(true, request.statisticsJson().contains("bestScore"));
    }

    @Test
    void systemErrorSubmissionIsTerminalAndDoesNotBlockStudentPostmortem() {
        authenticate(91L, Role.STUDENT);
        ContestEntity contest = contest(ContestMode.ACM);
        ContestRunEntity run = run(ContestMode.ACM);
        when(contestMapper.selectById(301L)).thenReturn(contest);
        when(contestRunMapper.selectOne(any())).thenReturn(run);
        when(participantMapper.selectOne(any())).thenReturn(participant());
        when(submissionMapper.selectCount(any())).thenReturn(0L);
        when(problemSnapshotMapper.selectList(any())).thenReturn(List.of(problemSnapshot()));
        when(submissionMapper.selectList(any())).thenReturn(List.of(
                submission(104L, SubmissionStatus.SYSTEM_ERROR, 4000L, "int transient_failure() { return 0; }")
        ));
        when(caseResultMapper.selectList(any())).thenReturn(List.of());
        doAnswer(invocation -> {
            ContestStudentPostmortemReportEntity inserted = invocation.getArgument(0);
            inserted.setId(503L);
            return 1;
        }).when(reportMapper).insert(any(ContestStudentPostmortemReportEntity.class));
        when(aiClient.analyze(any())).thenReturn(new StudentPostmortemAnalysisResponse(
                "## 个人复盘", List.of(), List.of(), "mock", "mock-model", 12, 6, true, null));
        when(candidateMapper.selectList(any())).thenReturn(List.of());

        service.createMyReport(301L, 401L);

        verify(aiClient).analyze(any());
    }

    private ContestStudentPostmortemReportEntity report(Long userId) {
        ContestStudentPostmortemReportEntity report = new ContestStudentPostmortemReportEntity();
        report.setId(501L);
        report.setContestId(301L);
        report.setContestRunId(401L);
        report.setContestParticipantId(201L);
        report.setUserId(userId);
        report.setStatus(ContestPostmortemReportStatus.COMPLETED);
        report.setAiStatus(ContestPostmortemAiStatus.COMPLETED);
        report.setStatisticsJson("{}");
        report.setPracticeSuggestionsJson("[]");
        report.setCreatedAt(Instant.parse("2026-06-07T01:00:00Z"));
        report.setUpdatedAt(Instant.parse("2026-06-07T01:01:00Z"));
        return report;
    }

    private ContestStudentPostmortemWeaknessCandidateEntity candidate(Long userId) {
        ContestStudentPostmortemWeaknessCandidateEntity candidate = new ContestStudentPostmortemWeaknessCandidateEntity();
        candidate.setId(601L);
        candidate.setReportId(501L);
        candidate.setContestId(301L);
        candidate.setContestRunId(401L);
        candidate.setContestParticipantId(201L);
        candidate.setUserId(userId);
        candidate.setStatus(ContestStudentPostmortemWeaknessCandidateStatus.PENDING);
        candidate.setKnowledgeNode("binary_search");
        candidate.setSymptom("二分答案边界不稳定");
        candidate.setTagsJson("[\"binary_search\"]");
        candidate.setEvidenceJson("[\"A 题多次 WA\"]");
        candidate.setConfidence(BigDecimal.valueOf(0.82));
        candidate.setCreatedAt(Instant.parse("2026-06-07T01:02:00Z"));
        candidate.setUpdatedAt(Instant.parse("2026-06-07T01:02:00Z"));
        return candidate;
    }

    private ContestEntity contest(ContestMode mode) {
        ContestEntity contest = new ContestEntity();
        contest.setId(301L);
        contest.setOwnerUserId(70L);
        contest.setTitle("Contest 101");
        contest.setMode(mode);
        return contest;
    }

    private ContestRunEntity run(ContestMode mode) {
        ContestRunEntity run = new ContestRunEntity();
        run.setId(401L);
        run.setContestId(301L);
        run.setTitle("Run 101");
        run.setRunKind(ContestRunKind.FORMAL);
        run.setStatus(ContestRunStatus.ENDED);
        run.setModeSnapshot(mode);
        run.setStartAt(Instant.parse("2026-06-06T01:00:00Z"));
        run.setEndAt(Instant.parse("2026-06-06T02:00:00Z"));
        return run;
    }

    private ContestParticipantEntity participant() {
        ContestParticipantEntity participant = new ContestParticipantEntity();
        participant.setId(201L);
        participant.setContestId(301L);
        participant.setContestRunId(401L);
        participant.setUserId(91L);
        participant.setStatus(ContestParticipantStatus.ACTIVE);
        participant.setAccountSnapshot("student");
        participant.setDisplayNameSnapshot("Student");
        return participant;
    }

    private ContestRunProblemSnapshotEntity problemSnapshot() {
        ContestRunProblemSnapshotEntity problem = new ContestRunProblemSnapshotEntity();
        problem.setId(30101L);
        problem.setContestId(301L);
        problem.setContestRunId(401L);
        problem.setContestProblemId(901L);
        problem.setProblemId(801L);
        problem.setLabel("A");
        problem.setDisplayTitle("星港间距");
        problem.setTags("[\"binary_search\",\"greedy\"]");
        problem.setScore(0);
        problem.setSortOrder(1);
        return problem;
    }

    private SubmissionEntity submission(Long id, SubmissionStatus status, Long submittedAtContestMillis, String code) {
        SubmissionEntity submission = new SubmissionEntity();
        submission.setId(id);
        submission.setContestId(301L);
        submission.setContestRunId(401L);
        submission.setContestProblemId(901L);
        submission.setContestParticipantId(201L);
        submission.setProblemId(801L);
        submission.setUserId(91L);
        submission.setSubmittedAtContestMillis(submittedAtContestMillis);
        submission.setLanguage("cpp");
        submission.setStatus(status);
        submission.setCode(code);
        submission.setCreatedAt(Instant.parse("2026-06-06T01:01:00Z"));
        return submission;
    }

    private void authenticate(Long userId, Role role) {
        SecurityPrincipal principal = new SecurityPrincipal(userId, "user" + userId, Set.of(role));
        SecurityContextHolder.getContext().setAuthentication(new UsernamePasswordAuthenticationToken(principal, "n/a",
                Set.of(new SimpleGrantedAuthority("ROLE_" + role.name()))));
    }
}
