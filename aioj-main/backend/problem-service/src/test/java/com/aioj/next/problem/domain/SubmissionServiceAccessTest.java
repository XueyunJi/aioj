package com.aioj.next.problem.domain;

import com.aioj.next.common.security.Role;
import com.aioj.next.common.security.SecurityPrincipal;
import com.aioj.next.common.error.DomainException;
import com.aioj.next.common.error.ErrorCode;
import com.aioj.next.contract.ai.AiProblemContextRequest;
import com.aioj.next.contract.ai.AiProblemContextResponse;
import com.aioj.next.contract.ai.AiSubmissionContextRequest;
import com.aioj.next.contract.contest.ContestAiPolicyRequest;
import com.aioj.next.contract.contest.ContestAiPolicyResponse;
import com.aioj.next.contract.judge.JudgeTaskMessage;
import com.aioj.next.contract.submission.SubmissionCreateRequest;
import com.aioj.next.contract.submission.SubmissionScope;
import com.aioj.next.contract.submission.SubmissionStatus;
import com.aioj.next.problem.persistence.entity.ProblemEntity;
import com.aioj.next.problem.persistence.entity.SubmissionEntity;
import com.aioj.next.problem.persistence.mapper.SubmissionCaseResultMapper;
import com.aioj.next.problem.persistence.mapper.SubmissionMapper;
import com.baomidou.mybatisplus.core.conditions.Wrapper;
import com.baomidou.mybatisplus.core.MybatisConfiguration;
import com.baomidou.mybatisplus.core.metadata.TableInfoHelper;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import org.apache.ibatis.builder.MapperBuilderAssistant;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.springframework.amqp.core.MessagePostProcessor;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.amqp.rabbit.core.RabbitTemplate;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.context.SecurityContextHolder;

import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class SubmissionServiceAccessTest {
    @Mock
    private ProblemCatalog problemCatalog;
    @Mock
    private ContestService contestService;
    @Mock
    private SubmissionMapper submissionMapper;
    @Mock
    private SubmissionCaseResultMapper caseResultMapper;
    @Mock
    private SubmissionRequestFingerprintService fingerprintService;
    @Mock
    private ContestAiPolicyService contestAiPolicyService;
    @Mock
    private ContestProblemVisibilityService visibilityService;
    @Mock
    private RabbitTemplate rabbitTemplate;

    private SubmissionService service;

    @BeforeEach
    void setUp() {
        TableInfoHelper.initTableInfo(new MapperBuilderAssistant(new MybatisConfiguration(), ""), SubmissionEntity.class);
        lenient().when(visibilityService.isPrivate(any())).thenReturn(false);
        lenient().when(visibilityService.hiddenRunProblemPairs(any(), any(), any())).thenReturn(java.util.Map.of());
        service = new SubmissionService(problemCatalog, contestService, submissionMapper, caseResultMapper, fingerprintService, contestAiPolicyService, visibilityService, rabbitTemplate);
        lenient().when(caseResultMapper.selectList(any())).thenReturn(java.util.List.of());
    }

    @AfterEach
    void tearDown() {
        SecurityContextHolder.clearContext();
    }

    @Test
    void ownerCanReadOwnSubmissionSourceThroughNormalDetail() {
        authenticate(91L, Role.STUDENT);
        when(submissionMapper.selectById(801L)).thenReturn(submission());

        var result = service.get(801L);

        assertEquals("int main(){}", result.code());
        assertEquals("stderr", result.stderrExcerpt());
        assertEquals(0, result.exitStatus());
    }

    @Test
    void staffCannotReadOtherUsersSourceThroughNormalDetail() {
        authenticate(7L, Role.TEACHER);
        when(submissionMapper.selectById(801L)).thenReturn(submission());

        var result = service.get(801L);

        assertNull(result.code());
        assertNull(result.stderrExcerpt());
        assertNull(result.exitStatus());
    }

    @Test
    void aiSubmissionContextRejectsAnotherUsersSubmission() {
        when(submissionMapper.selectById(801L)).thenReturn(submission());

        assertThrows(DomainException.class, () -> service.aiSubmissionContext(
                new AiSubmissionContextRequest(7L, 801L, 1001L, null, null, null, "ANALYZE_FAILURE")));
    }

    @Test
    void aiSubmissionContextRejectsProblemMismatch() {
        when(submissionMapper.selectById(801L)).thenReturn(submission());

        DomainException exception = assertThrows(DomainException.class, () -> service.aiSubmissionContext(
                new AiSubmissionContextRequest(91L, 801L, 2002L, null, null, null, "ANALYZE_FAILURE")));

        assertEquals(ErrorCode.BAD_REQUEST, exception.errorCode());
    }

    @Test
    void aiSubmissionContextRejectsQueuedAndRunningSubmissions() {
        SubmissionEntity queued = practiceSubmission();
        queued.setStatus(SubmissionStatus.QUEUED);
        SubmissionEntity running = practiceSubmission();
        running.setStatus(SubmissionStatus.RUNNING);
        when(submissionMapper.selectById(801L)).thenReturn(queued, running);

        DomainException queuedException = assertThrows(DomainException.class, () -> service.aiSubmissionContext(
                new AiSubmissionContextRequest(91L, 801L, 1001L, null, null, null, "ANALYZE_FAILURE")));
        DomainException runningException = assertThrows(DomainException.class, () -> service.aiSubmissionContext(
                new AiSubmissionContextRequest(91L, 801L, 1001L, null, null, null, "ANALYZE_FAILURE")));

        assertEquals(ErrorCode.BAD_REQUEST, queuedException.errorCode());
        assertEquals(ErrorCode.BAD_REQUEST, runningException.errorCode());
        verifyNoInteractions(contestAiPolicyService, problemCatalog);
    }

    @Test
    void aiSubmissionContextIncludesOwnPracticeCode() {
        when(submissionMapper.selectById(801L)).thenReturn(practiceSubmission());
        when(contestAiPolicyService.check(any(ContestAiPolicyRequest.class))).thenReturn(ContestAiPolicyResponse.inactive());
        when(problemCatalog.aiProblemContext(any(AiProblemContextRequest.class))).thenReturn(problemContext());

        var result = service.aiSubmissionContext(
                new AiSubmissionContextRequest(91L, 801L, 1001L, null, null, null, "ANALYZE_FAILURE"));

        assertEquals("int main(){}", result.codeText());
        assertEquals("stderr", result.stderrExcerpt());
        org.assertj.core.api.Assertions.assertThat(result.codeAllowedToModel()).isTrue();
    }

    @Test
    void aiSubmissionContextAllowsSystemErrorAsFinalState() {
        SubmissionEntity submission = practiceSubmission();
        submission.setStatus(SubmissionStatus.SYSTEM_ERROR);
        when(submissionMapper.selectById(801L)).thenReturn(submission);
        when(contestAiPolicyService.check(any(ContestAiPolicyRequest.class))).thenReturn(ContestAiPolicyResponse.inactive());
        when(problemCatalog.aiProblemContext(any(AiProblemContextRequest.class))).thenReturn(problemContext());

        var result = service.aiSubmissionContext(
                new AiSubmissionContextRequest(91L, 801L, 1001L, null, null, null, "ANALYZE_FAILURE"));

        assertEquals("SYSTEM_ERROR", result.status());
        assertEquals("int main(){}", result.codeText());
    }

    @Test
    void aiSubmissionContextRedactsCodeDuringActiveContestProblem() {
        when(submissionMapper.selectById(801L)).thenReturn(submission());
        when(contestAiPolicyService.check(any(ContestAiPolicyRequest.class))).thenReturn(new ContestAiPolicyResponse(
                true,
                301L,
                302L,
                401L,
                1001L,
                "contest",
                "run",
                "problem"));
        when(problemCatalog.aiProblemContext(any(AiProblemContextRequest.class))).thenReturn(problemContext());

        var result = service.aiSubmissionContext(
                new AiSubmissionContextRequest(91L, 801L, 1001L, 301L, 302L, 401L, "ANALYZE_FAILURE"));

        assertNull(result.codeText());
        assertNull(result.stdoutExcerpt());
        assertNull(result.stderrExcerpt());
        org.assertj.core.api.Assertions.assertThat(result.contestActive()).isTrue();
        org.assertj.core.api.Assertions.assertThat(result.codeAllowedToModel()).isFalse();
        org.assertj.core.api.Assertions.assertThat(result.judgeMessage()).isEqualTo("Accepted");
        org.assertj.core.api.Assertions.assertThat(result.codeHash()).isNotBlank();
        org.assertj.core.api.Assertions.assertThat(result.policyMessage()).contains("不能提供完整可提交代码");
    }

    @Test
    void aiSubmissionContextIncludesOwnCodeWhenContestPolicyInactive() {
        when(submissionMapper.selectById(801L)).thenReturn(submission());
        when(contestAiPolicyService.check(any(ContestAiPolicyRequest.class))).thenReturn(ContestAiPolicyResponse.inactive());
        when(problemCatalog.aiProblemContext(any(AiProblemContextRequest.class))).thenReturn(problemContext());

        var result = service.aiSubmissionContext(
                new AiSubmissionContextRequest(91L, 801L, 1001L, 301L, 302L, 401L, "ANALYZE_FAILURE"));

        assertEquals("int main(){}", result.codeText());
        assertEquals("stdout", result.stdoutExcerpt());
        assertEquals("stderr", result.stderrExcerpt());
        org.assertj.core.api.Assertions.assertThat(result.contestActive()).isFalse();
        org.assertj.core.api.Assertions.assertThat(result.codeAllowedToModel()).isTrue();
    }

    @Test
    void submitPublishesJudgeTaskWithLanguageEffectiveTimeLimit() {
        authenticate(91L, Role.STUDENT);
        ProblemEntity problem = activeProblem();
        when(problemCatalog.findActive(1001L)).thenReturn(Optional.of(problem));
        when(problemCatalog.effectiveTimeLimitMillis(any(ProblemEntity.class), anyString()))
                .thenAnswer(invocation -> switch ((String) invocation.getArgument(1)) {
                    case "python" -> 2500;
                    case "java" -> 1500;
                    default -> 1000;
                });
        doAnswer(invocation -> {
            SubmissionEntity submission = invocation.getArgument(0);
            submission.setId(900L + List.of("cpp", "python", "java").indexOf(submission.getLanguage()));
            return 1;
        }).when(submissionMapper).insert(any(SubmissionEntity.class));

        service.submit(new SubmissionCreateRequest(1001L, "cpp", "int main(){}", null, null, null));
        service.submit(new SubmissionCreateRequest(1001L, "python", "print(1)", null, null, null));
        service.submit(new SubmissionCreateRequest(1001L, "java", "class Main {}", null, null, null));

        ArgumentCaptor<JudgeTaskMessage> captor = ArgumentCaptor.forClass(JudgeTaskMessage.class);
        verify(rabbitTemplate, times(3)).convertAndSend(anyString(), anyString(), captor.capture(), any(MessagePostProcessor.class));
        assertEquals(List.of(1000, 2500, 1500), captor.getAllValues().stream().map(JudgeTaskMessage::timeLimitMillis).toList());
        verify(problemCatalog, times(3)).effectiveTimeLimitMillis(any(ProblemEntity.class), anyString());
    }

    @Test
    void practiceScopeRejectsContestFilters() {
        authenticate(91L, Role.STUDENT);

        assertThrows(DomainException.class, () -> service.list(1, 20, null, null, null, null,
                null, 301L, null, null, SubmissionScope.PRACTICE));
    }

    @Test
    @SuppressWarnings({"rawtypes", "unchecked"})
    void practiceScopeFiltersOutContestSubmissions() {
        authenticate(91L, Role.STUDENT);
        when(submissionMapper.selectPage(any(Page.class), any(Wrapper.class))).thenAnswer(invocation -> invocation.getArgument(0));

        service.list(1, 20, null, null, null, null, null, null, null, null, SubmissionScope.PRACTICE);

        ArgumentCaptor<Wrapper> wrapperCaptor = ArgumentCaptor.forClass(Wrapper.class);
        verify(submissionMapper).selectPage(any(Page.class), wrapperCaptor.capture());
        String sqlSegment = wrapperCaptor.getValue().getSqlSegment();
        org.assertj.core.api.Assertions.assertThat(sqlSegment)
                .contains("contest_id IS NULL")
                .contains("contest_run_id IS NULL")
                .contains("contest_problem_id IS NULL");
    }

    @Test
    @SuppressWarnings({"rawtypes", "unchecked"})
    void contestScopeFiltersToContestSubmissions() {
        authenticate(91L, Role.STUDENT);
        when(submissionMapper.selectPage(any(Page.class), any(Wrapper.class))).thenAnswer(invocation -> invocation.getArgument(0));

        service.list(1, 20, null, null, null, null, null, null, null, null, SubmissionScope.CONTEST);

        ArgumentCaptor<Wrapper> wrapperCaptor = ArgumentCaptor.forClass(Wrapper.class);
        verify(submissionMapper).selectPage(any(Page.class), wrapperCaptor.capture());
        String sqlSegment = wrapperCaptor.getValue().getSqlSegment();
        org.assertj.core.api.Assertions.assertThat(sqlSegment)
                .contains("contest_id IS NOT NULL")
                .contains("contest_run_id IS NOT NULL")
                .contains("contest_problem_id IS NOT NULL");
    }

    private SubmissionEntity submission() {
        SubmissionEntity submission = new SubmissionEntity();
        submission.setId(801L);
        submission.setProblemId(1001L);
        submission.setUserId(91L);
        submission.setContestId(301L);
        submission.setContestProblemId(401L);
        submission.setContestParticipantId(601L);
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

    private SubmissionEntity practiceSubmission() {
        SubmissionEntity submission = submission();
        submission.setContestId(null);
        submission.setContestRunId(null);
        submission.setContestProblemId(null);
        submission.setContestParticipantId(null);
        submission.setSubmittedAtContestMillis(null);
        return submission;
    }

    private ProblemEntity activeProblem() {
        ProblemEntity problem = new ProblemEntity();
        problem.setId(1001L);
        problem.setTimeLimitMillis(1000);
        problem.setMemoryLimitKb(262144);
        return problem;
    }

    private AiProblemContextResponse problemContext() {
        return new AiProblemContextResponse(
                1001L,
                null,
                null,
                null,
                "Problem",
                "medium",
                "Statement",
                "Statement summary",
                List.of("binary-search"),
                List.of("n <= 100000"),
                List.of(),
                1000,
                262144,
                "ACTIVE_PROBLEM",
                Instant.parse("2026-06-10T09:00:00Z")
        );
    }

    private void authenticate(Long userId, Role role) {
        SecurityPrincipal principal = new SecurityPrincipal(userId, "user" + userId, Set.of(role));
        SecurityContextHolder.getContext().setAuthentication(new UsernamePasswordAuthenticationToken(principal, "n/a",
                Set.of(new SimpleGrantedAuthority("ROLE_" + role.name()))));
    }
}
