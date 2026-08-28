package com.aioj.next.ai.agent.problem.tool;

import com.aioj.next.ai.agent.context.DataClassification;
import com.aioj.next.ai.agent.policy.ContestPolicyView;
import com.aioj.next.ai.agent.policy.GuardDecision;
import com.aioj.next.ai.agent.policy.GuardDecisionRecorder;
import com.aioj.next.ai.agent.policy.GuardLayer;
import com.aioj.next.ai.agent.policy.ParticipantStatus;
import com.aioj.next.ai.agent.tool.ToolExecutionContext;
import com.aioj.next.ai.agent.tool.ToolResult;
import com.aioj.next.ai.agent.tool.ToolStatus;
import com.aioj.next.ai.domain.ProblemServiceClient;
import com.aioj.next.common.error.DomainException;
import com.aioj.next.common.error.ErrorCode;
import com.aioj.next.contract.ai.AiProblemContextResponse;
import com.aioj.next.contract.ai.AiSubmissionCaseContext;
import com.aioj.next.contract.ai.AiSubmissionContextRequest;
import com.aioj.next.contract.ai.AiSubmissionContextResponse;
import com.aioj.next.contract.contest.ContestAiPolicyMode;
import com.aioj.next.contract.contest.RunningContestProblemOccurrence;
import com.aioj.next.contract.problem.ProblemVisibility;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class SubmissionFetchAllowedViewToolTest {

    private static final ObjectMapper OBJECT_MAPPER = new ObjectMapper();
    private static final String UNIFIED = "没有找到当前账户可访问的匹配题目";

    private final ProblemServiceClient problemServiceClient = mock(ProblemServiceClient.class);
    private final GuardDecisionRecorder recorder = mock(GuardDecisionRecorder.class);
    private final SubmissionFetchAllowedViewTool tool =
            new SubmissionFetchAllowedViewTool(problemServiceClient, recorder, OBJECT_MAPPER);

    @Test
    void ownSubmissionReturnsFullView() throws Exception {
        when(problemServiceClient.aiSubmissionContext(any(AiSubmissionContextRequest.class)))
                .thenReturn(submission(true, false));

        ToolResult<Object> result = tool.execute(context(null), input("{\"submissionId\":5001}"));

        assertThat(result.ok()).isTrue();
        assertThat(result.classification()).isEqualTo(DataClassification.USER_PRIVATE);
        Map<String, Object> data = data(result);
        assertThat(data.get("submissionId")).isEqualTo("5001");
        assertThat(data.get("problemId")).isEqualTo("1001");
        assertThat(data.get("status")).isEqualTo("WRONG_ANSWER");
        assertThat(data.get("codeText")).isEqualTo("int main(){}");
        List<Map<String, Object>> cases = (List<Map<String, Object>>) data.get("caseResults");
        assertThat(cases).hasSize(1);
        assertThat(cases.get(0).get("status")).isEqualTo("WRONG_ANSWER");
        Map<String, Object> problem = (Map<String, Object>) data.get("problem");
        assertThat(problem.get("statement")).isEqualTo("题面");
        // The caller's server-side identity is the only owner reference sent downstream.
        ArgumentCaptor<AiSubmissionContextRequest> request = ArgumentCaptor.forClass(AiSubmissionContextRequest.class);
        verify(problemServiceClient).aiSubmissionContext(request.capture());
        assertThat(request.getValue().requestUserId()).isEqualTo(7L);
        assertThat(request.getValue().submissionId()).isEqualTo(5001L);
    }

    @Test
    void codeIsOmittedWhenPolicyDisallowsIt() throws Exception {
        when(problemServiceClient.aiSubmissionContext(any(AiSubmissionContextRequest.class)))
                .thenReturn(submission(false, true));

        ToolResult<Object> result = tool.execute(context(null), input("{\"submissionId\":5001}"));

        Map<String, Object> data = data(result);
        assertThat(data.get("codeAllowedToModel")).isEqualTo(false);
        assertThat(data).doesNotContainKey("codeText");
        assertThat(data.get("contestActive")).isEqualTo(true);
    }

    @Test
    void foreignSubmissionGetsUnifiedDenialAndAudit() throws Exception {
        when(problemServiceClient.aiSubmissionContext(any(AiSubmissionContextRequest.class)))
                .thenThrow(new DomainException(ErrorCode.FORBIDDEN, "Cannot analyze another user's submission"));

        ToolResult<Object> result = tool.execute(context(null), input("{\"submissionId\":5002}"));

        assertThat(result.status()).isEqualTo(ToolStatus.POLICY_DENIED);
        assertThat(result.errorCode()).isEqualTo("NOT_ACCESSIBLE");
        assertThat(result.errorMessage()).isEqualTo(UNIFIED);
        verify(recorder).record(eq("t1"), eq(7L), eq("c1"),
                eq(GuardLayer.TOOL_ABAC), eq(GuardDecision.REFUSE), anyList(),
                eq("foreign_submission_view_denied"), any(), eq(false), isNull());
    }

    @Test
    void missingSubmissionGetsUnifiedPayloadWithoutGuardAudit() throws Exception {
        when(problemServiceClient.aiSubmissionContext(any(AiSubmissionContextRequest.class)))
                .thenThrow(new DomainException(ErrorCode.NOT_FOUND, "Submission not found"));

        ToolResult<Object> result = tool.execute(context(null), input("{\"submissionId\":5003}"));

        assertThat(result.status()).isEqualTo(ToolStatus.POLICY_DENIED);
        assertThat(result.errorCode()).isEqualTo("NOT_ACCESSIBLE");
        assertThat(result.errorMessage()).isEqualTo(UNIFIED);
        verify(recorder, never()).record(any(), any(), any(), any(), any(), anyList(), any(), any(),
                any(Boolean.class), any());
    }

    @Test
    void stillJudgingSubmissionIsATemporaryFailureNotADenial() throws Exception {
        when(problemServiceClient.aiSubmissionContext(any(AiSubmissionContextRequest.class)))
                .thenThrow(new DomainException(ErrorCode.BAD_REQUEST, "Submission is still being judged"));

        ToolResult<Object> result = tool.execute(context(null), input("{\"submissionId\":5004}"));

        assertThat(result.status()).isEqualTo(ToolStatus.EXECUTION_ERROR);
        assertThat(result.errorCode()).isEqualTo("SUBMISSION_NOT_READY");
        assertThat(result.errorMessage()).contains("judged");
    }

    @Test
    void participantViewingOwnSubmissionOnDenyProblemGetsStatementWithheld() throws Exception {
        when(problemServiceClient.aiSubmissionContext(any(AiSubmissionContextRequest.class)))
                .thenReturn(submission(false, true));
        ContestPolicyView view = new ContestPolicyView(ParticipantStatus.PARTICIPANT_ACTIVE,
                Map.of(1001L, new ContestPolicyView.ContestProblemPolicy(1001L, ProblemVisibility.PRIVATE,
                        ContestAiPolicyMode.DEFAULT, null, "私有题面",
                        List.of(new RunningContestProblemOccurrence(5501L, 7701L, 99001L)))));

        ToolResult<Object> result = tool.execute(context(view), input("{\"submissionId\":5001}"));

        assertThat(result.ok()).isTrue();
        Map<String, Object> problem = (Map<String, Object>) data(result).get("problem");
        assertThat(problem.get("statementWithheld")).isEqualTo(true);
        assertThat(problem).doesNotContainKey("statement");
        assertThat(problem.get("title")).isEqualTo("题目标题");
    }

    @Test
    void participantViewingOwnSubmissionOnHintOnlyProblemKeepsStatement() throws Exception {
        when(problemServiceClient.aiSubmissionContext(any(AiSubmissionContextRequest.class)))
                .thenReturn(submission(false, true));
        ContestPolicyView view = new ContestPolicyView(ParticipantStatus.PARTICIPANT_ACTIVE,
                Map.of(1001L, new ContestPolicyView.ContestProblemPolicy(1001L, ProblemVisibility.PUBLIC,
                        ContestAiPolicyMode.DEFAULT, null, "公开题面",
                        List.of(new RunningContestProblemOccurrence(5501L, 7701L, 99001L)))));

        ToolResult<Object> result = tool.execute(context(view), input("{\"submissionId\":5001}"));

        Map<String, Object> problem = (Map<String, Object>) data(result).get("problem");
        assertThat(problem.get("statement")).isEqualTo("题面");
    }

    private AiSubmissionContextResponse submission(boolean codeAllowed, boolean contestActive) {
        return new AiSubmissionContextResponse(
                5001L, 7L, 1001L, null, null, null,
                contestActive ? "CONTEST" : "PRACTICE", contestActive,
                "cpp17", "WRONG_ANSWER", "wrong answer on case 3", "1 2\n", "", 0,
                12, 2048, 0.0, 100.0,
                codeAllowed, codeAllowed ? "int main(){}" : null, "hash-1",
                List.of(new AiSubmissionCaseContext(3, "case-3", "WRONG_ANSWER", 0.0, 20.0, 4, 1024, "wa")),
                new AiProblemContextResponse(1001L, null, null, null, "题目标题", "EASY",
                        "题面", "摘要", List.of(), List.of(), List.of(), 1000, 262144, "PROBLEM", Instant.now()),
                Instant.now(), Instant.now(), null);
    }

    private JsonNode input(String json) throws Exception {
        return OBJECT_MAPPER.readTree(json);
    }

    @SuppressWarnings("unchecked")
    private Map<String, Object> data(ToolResult<Object> result) {
        return (Map<String, Object>) result.data();
    }

    private ToolExecutionContext context(ContestPolicyView view) {
        return new ToolExecutionContext(7L, "c1", "t1", 1L, "ps-1", Set.of("AI_CHAT"), Instant.now(), "tr", view);
    }
}
