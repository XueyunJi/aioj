package com.aioj.next.ai.agent.problem.tool;

import com.aioj.next.ai.agent.context.DataClassification;
import com.aioj.next.ai.agent.policy.ContestPolicyView;
import com.aioj.next.ai.agent.policy.GuardDecision;
import com.aioj.next.ai.agent.policy.GuardDecisionRecorder;
import com.aioj.next.ai.agent.policy.GuardLayer;
import com.aioj.next.ai.agent.policy.ParticipantStatus;
import com.aioj.next.ai.agent.tool.ToolExecutionContext;
import com.aioj.next.ai.agent.tool.ToolRegistry;
import com.aioj.next.ai.agent.tool.ToolResult;
import com.aioj.next.ai.agent.tool.ToolStatus;
import com.aioj.next.ai.domain.ProblemServiceClient;
import com.aioj.next.common.error.DomainException;
import com.aioj.next.common.error.ErrorCode;
import com.aioj.next.contract.ai.AiProblemContextRequest;
import com.aioj.next.contract.ai.AiProblemContextResponse;
import com.aioj.next.contract.ai.ProblemTitleInfo;
import com.aioj.next.contract.contest.ContestAiPolicyMode;
import com.aioj.next.contract.contest.RunningContestProblemOccurrence;
import com.aioj.next.contract.problem.ProblemVisibility;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.LinkedHashMap;
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

class ProblemFetchAllowedViewToolTest {

    private static final ObjectMapper OBJECT_MAPPER = new ObjectMapper();
    private static final String UNIFIED = "没有找到当前账户可访问的匹配题目";

    private final ProblemServiceClient problemServiceClient = mock(ProblemServiceClient.class);
    private final GuardDecisionRecorder recorder = mock(GuardDecisionRecorder.class);
    private final ProblemFetchAllowedViewTool tool =
            new ProblemFetchAllowedViewTool(problemServiceClient, recorder, OBJECT_MAPPER);

    @Test
    void participantFetchesPublicContestProblemWithHintOnlyMarkers() throws Exception {
        ContestPolicyView view = participantView(
                problem(1001L, ProblemVisibility.PUBLIC, ContestAiPolicyMode.DEFAULT, "只提示", "比赛中禁止直接给答案"));

        ToolResult<Object> result = tool.execute(context(view), input("{\"problemId\":1001}"));

        assertThat(result.ok()).isTrue();
        assertThat(result.classification()).isEqualTo(DataClassification.CONTEST_PUBLIC_ACTIVE);
        assertThat(result.trustLevel().name()).isEqualTo("USER_PROVIDED");
        Map<String, Object> data = data(result);
        assertThat(data.get("problemId")).isEqualTo("1001");
        assertThat(data.get("source")).isEqualTo("CONTEST_SNAPSHOT");
        assertThat(data.get("assistanceLevel")).isEqualTo("HINT_ONLY");
        assertThat(data.get("aiPolicyMode")).isEqualTo("DEFAULT");
        assertThat(data.get("aiPolicyNotes")).isEqualTo("比赛中禁止直接给答案");
        assertThat(data.get("statement")).isEqualTo("只提示");
        verify(problemServiceClient, never()).aiProblemContext(any());
    }

    @Test
    void participantFetchingPrivateContestProblemGetsUnifiedDenialAndAudit() throws Exception {
        ContestPolicyView view = participantView(
                problem(1002L, ProblemVisibility.PRIVATE, ContestAiPolicyMode.DEFAULT, "私有题面", null));

        ToolResult<Object> result = tool.execute(context(view), input("{\"problemId\":1002}"));

        assertUnifiedDenial(result);
        verify(recorder).record(eq("t1"), eq(7L), eq("c1"),
                eq(GuardLayer.TOOL_ABAC), eq(GuardDecision.REFUSE), anyList(),
                eq("contest_problem_view_denied"), any(), eq(false), any());
    }

    @Test
    void participantFetchingStrictModeProblemIsDeniedEvenWhenPublic() throws Exception {
        ContestPolicyView view = participantView(
                problem(1003L, ProblemVisibility.PUBLIC, ContestAiPolicyMode.STRICT, "公开但严格", null));

        ToolResult<Object> result = tool.execute(context(view), input("{\"problemId\":1003}"));

        assertUnifiedDenial(result);
        verify(recorder).record(eq("t1"), eq(7L), eq("c1"),
                eq(GuardLayer.TOOL_ABAC), eq(GuardDecision.REFUSE), anyList(),
                eq("contest_problem_view_denied"), any(), eq(false), any());
    }

    @Test
    void participantFetchingProblemOutsideContestSetGetsUnifiedDenialAndAudit() throws Exception {
        ContestPolicyView view = participantView(
                problem(1001L, ProblemVisibility.PUBLIC, ContestAiPolicyMode.DEFAULT, "题面", null));

        ToolResult<Object> result = tool.execute(context(view), input("{\"problemId\":9999}"));

        assertUnifiedDenial(result);
        verify(recorder).record(eq("t1"), eq(7L), eq("c1"),
                eq(GuardLayer.TOOL_ABAC), eq(GuardDecision.REFUSE), anyList(),
                eq("problem_outside_contest_scope"), any(), eq(false), isNull());
        verify(problemServiceClient, never()).aiProblemContext(any());
    }

    @Test
    void nonParticipantFetchesPublicPracticeProblem() throws Exception {
        when(problemServiceClient.problemTitles(List.of(1001L)))
                .thenReturn(List.of(new ProblemTitleInfo(1001L, "二分边界", ProblemVisibility.PUBLIC)));
        when(problemServiceClient.aiProblemContext(any(AiProblemContextRequest.class)))
                .thenReturn(practiceContext(1001L));

        ToolResult<Object> result = tool.execute(context(null), input("{\"problemId\":1001}"));

        assertThat(result.ok()).isTrue();
        assertThat(result.classification()).isEqualTo(DataClassification.PUBLIC);
        Map<String, Object> data = data(result);
        assertThat(data.get("source")).isEqualTo("PROBLEM");
        assertThat(data.get("assistanceLevel")).isEqualTo("FULL_TUTORING");
        assertThat(data.get("title")).isEqualTo("二分边界");
        assertThat(data.get("statement")).isEqualTo("练习赛题面");
        assertThat(data.get("tags")).isEqualTo(List.of("binary-search"));
    }

    @Test
    void nonParticipantFetchingPrivateProblemGetsUnifiedDenialAndAudit() throws Exception {
        when(problemServiceClient.problemTitles(List.of(1002L)))
                .thenReturn(List.of(new ProblemTitleInfo(1002L, "私有题", ProblemVisibility.PRIVATE)));

        ToolResult<Object> result = tool.execute(context(null), input("{\"problemId\":1002}"));

        assertUnifiedDenial(result);
        verify(recorder).record(eq("t1"), eq(7L), eq("c1"),
                eq(GuardLayer.TOOL_ABAC), eq(GuardDecision.REFUSE), anyList(),
                eq("problem_not_visible"), any(), eq(false), isNull());
        verify(problemServiceClient, never()).aiProblemContext(any());
    }

    @Test
    void nonParticipantFetchingMissingProblemGetsUnifiedPayloadWithoutGuardAudit() throws Exception {
        when(problemServiceClient.problemTitles(List.of(9999L))).thenReturn(List.of());
        when(problemServiceClient.aiProblemContext(any(AiProblemContextRequest.class)))
                .thenThrow(new DomainException(ErrorCode.NOT_FOUND, "Problem not found"));

        ToolResult<Object> result = tool.execute(context(null), input("{\"problemId\":9999}"));

        assertUnifiedDenial(result);
        verify(recorder, never()).record(any(), any(), any(), any(), any(), anyList(), any(), any(),
                any(Boolean.class), any());
    }

    @Test
    void degradedVisibilityLookupFailsClosedWithDegradedAudit() throws Exception {
        // Title lookup returns nothing (best-effort endpoint), yet the problem exists:
        // visibility is unverifiable, so the fetch must fail closed (Q5) and audit degraded.
        when(problemServiceClient.problemTitles(List.of(1001L))).thenReturn(List.of());
        when(problemServiceClient.aiProblemContext(any(AiProblemContextRequest.class)))
                .thenReturn(practiceContext(1001L));

        ToolResult<Object> result = tool.execute(context(null), input("{\"problemId\":1001}"));

        assertUnifiedDenial(result);
        verify(recorder).record(eq("t1"), eq(7L), eq("c1"),
                eq(GuardLayer.TOOL_ABAC), eq(GuardDecision.REFUSE), anyList(),
                eq("problem_visibility_check_degraded"), any(), eq(true), isNull());
    }

    @Test
    void unifiedPayloadIsIdenticalForMissingPrivateAndOutOfScope() throws Exception {
        when(problemServiceClient.problemTitles(List.of(9999L))).thenReturn(List.of());
        when(problemServiceClient.aiProblemContext(any(AiProblemContextRequest.class)))
                .thenThrow(new DomainException(ErrorCode.NOT_FOUND, "Problem not found"));
        ToolResult<Object> missing = tool.execute(context(null), input("{\"problemId\":9999}"));

        when(problemServiceClient.problemTitles(List.of(1002L)))
                .thenReturn(List.of(new ProblemTitleInfo(1002L, "私有题", ProblemVisibility.PRIVATE)));
        ToolResult<Object> notVisible = tool.execute(context(null), input("{\"problemId\":1002}"));

        ToolResult<Object> outOfScope = tool.execute(context(participantView(
                problem(1001L, ProblemVisibility.PUBLIC, ContestAiPolicyMode.DEFAULT, "题面", null))),
                input("{\"problemId\":1002}"));

        for (ToolResult<Object> denial : List.of(notVisible, outOfScope)) {
            assertThat(denial.status()).isEqualTo(missing.status());
            assertThat(denial.errorCode()).isEqualTo(missing.errorCode());
            assertThat(denial.errorMessage()).isEqualTo(missing.errorMessage());
        }
    }

    @Test
    void allThreeProblemToolDescriptorsPassRegistryStartupValidation() {
        // The production registry fails startup on any contract violation; pin that the
        // P3-3 tool surface (names, MFJS-safe schemas, no identity fields) is legal.
        ToolRegistry registry = new ToolRegistry(java.util.List.of(
                tool,
                new ProblemSearchTool(recorder, OBJECT_MAPPER, 60_000L, 5),
                new SubmissionFetchAllowedViewTool(problemServiceClient, recorder, OBJECT_MAPPER)));

        assertThat(registry.descriptors()).extracting(com.aioj.next.ai.agent.tool.ToolDescriptor::name)
                .containsExactly("problem.fetch_allowed_view", "problem.search", "submission.fetch_allowed_view");
    }

    private void assertUnifiedDenial(ToolResult<Object> result) {
        assertThat(result.status()).isEqualTo(ToolStatus.POLICY_DENIED);
        assertThat(result.errorCode()).isEqualTo("NOT_ACCESSIBLE");
        assertThat(result.errorMessage()).isEqualTo(UNIFIED);
        assertThat(result.data()).isNull();
    }

    private AiProblemContextResponse practiceContext(long problemId) {
        return new AiProblemContextResponse(problemId, null, null, null, "二分边界", "MEDIUM",
                "练习赛题面", "题面摘要", List.of("binary-search"), List.of(), List.of(),
                1000, 262144, "PROBLEM", Instant.now());
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

    private ContestPolicyView participantView(ContestPolicyView.ContestProblemPolicy... problems) {
        Map<Long, ContestPolicyView.ContestProblemPolicy> map = new LinkedHashMap<>();
        for (ContestPolicyView.ContestProblemPolicy problem : problems) {
            map.put(problem.problemId(), problem);
        }
        return new ContestPolicyView(ParticipantStatus.PARTICIPANT_ACTIVE, map);
    }

    private ContestPolicyView.ContestProblemPolicy problem(long problemId, ProblemVisibility visibility,
                                                           ContestAiPolicyMode mode, String statement, String notes) {
        return new ContestPolicyView.ContestProblemPolicy(problemId, visibility, mode, notes, statement,
                List.of(new RunningContestProblemOccurrence(5501L, 7701L, 99001L + problemId)));
    }
}
