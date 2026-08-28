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

class ProblemSearchToolTest {

    private static final ObjectMapper OBJECT_MAPPER = new ObjectMapper();
    private static final String UNIFIED = "没有找到当前账户可访问的匹配题目";

    private final GuardDecisionRecorder recorder = mock(GuardDecisionRecorder.class);
    private final ProblemSearchTool tool = new ProblemSearchTool(recorder, OBJECT_MAPPER, 60_000L, 5);

    @Test
    void participantSearchHitsPublicSnapshotProblemWithHintOnlyMarker() throws Exception {
        ToolResult<Object> result = tool.execute(context(participantView(
                        problem(1001L, ProblemVisibility.PUBLIC, ContestAiPolicyMode.DEFAULT,
                                "给定数组求二分边界，注意 +1 的取舍。"),
                        problem(1002L, ProblemVisibility.PUBLIC, ContestAiPolicyMode.DEFAULT,
                                "完全无关的最短路题面。"))),
                input("{\"query\":\"二分\"}"));

        assertThat(result.ok()).isTrue();
        assertThat(result.classification()).isEqualTo(DataClassification.CONTEST_PUBLIC_ACTIVE);
        Map<String, Object> data = data(result);
        assertThat(data.get("hitCount")).isEqualTo(1);
        List<Map<String, Object>> hits = hits(data);
        assertThat(hits.get(0).get("problemId")).isEqualTo("1001");
        assertThat(hits.get(0).get("assistanceLevel")).isEqualTo("HINT_ONLY");
        assertThat(hits.get(0).get("aiPolicyMode")).isEqualTo("DEFAULT");
        assertThat(hits.get(0).get("excerpt").toString()).contains("二分");
        assertThat(hits.get(0).get("contestRunId")).isEqualTo("7701");
        assertThat(result.sources()).hasSize(1);
        // A plain hit is not a guard decision.
        verify(recorder, never()).record(any(), any(), any(), any(), any(), anyList(), any(), any(), eq(true), any());
    }

    @Test
    void privateAndStrictProblemsNeverAppearInHits() throws Exception {
        ToolResult<Object> result = tool.execute(context(participantView(
                        problem(1001L, ProblemVisibility.PRIVATE, ContestAiPolicyMode.DEFAULT, "二分私有题面"),
                        problem(1002L, ProblemVisibility.PUBLIC, ContestAiPolicyMode.STRICT, "二分严格题面"))),
                input("{\"query\":\"二分\"}"));

        assertThat(result.ok()).isTrue();
        Map<String, Object> data = data(result);
        assertThat(data.get("hitCount")).isEqualTo(0);
        assertThat(hits(data)).isEmpty();
    }

    @Test
    void disabledModeProblemIsSearchableWithFullTutoring() throws Exception {
        ToolResult<Object> result = tool.execute(context(participantView(
                        problem(1001L, ProblemVisibility.PRIVATE, ContestAiPolicyMode.DISABLED, "二分放开题面"))),
                input("{\"query\":\"二分\"}"));

        assertThat(result.ok()).isTrue();
        Map<String, Object> data = data(result);
        assertThat(data.get("hitCount")).isEqualTo(1);
        assertThat(hits(data).get(0).get("assistanceLevel")).isEqualTo("FULL_TUTORING");
    }

    @Test
    void nonParticipantGetsAnEmptySearchSpace() throws Exception {
        assertThat(data(tool.execute(context(null), input("{\"query\":\"二分\"}"))).get("hitCount")).isEqualTo(0);
        assertThat(data(tool.execute(context(ContestPolicyView.nonParticipant()), input("{\"query\":\"二分\"}")))
                .get("hitCount")).isEqualTo(0);
    }

    @Test
    void topKCapsTheNumberOfHits() throws Exception {
        ContestPolicyView view = participantView(
                problem(1001L, ProblemVisibility.PUBLIC, ContestAiPolicyMode.DEFAULT, "二分甲"),
                problem(1002L, ProblemVisibility.PUBLIC, ContestAiPolicyMode.DEFAULT, "二分乙"),
                problem(1003L, ProblemVisibility.PUBLIC, ContestAiPolicyMode.DEFAULT, "二分丙"));

        ToolResult<Object> result = tool.execute(context(view), input("{\"query\":\"二分\",\"topK\":2}"));

        assertThat(data(result).get("hitCount")).isEqualTo(2);
    }

    @Test
    void rateLimitExceededReturnsUnifiedPayloadAndAuditsToolAbac() throws Exception {
        ProblemSearchTool limited = new ProblemSearchTool(recorder, OBJECT_MAPPER, 60_000L, 2);
        ToolExecutionContext context = context(participantView(
                problem(1001L, ProblemVisibility.PUBLIC, ContestAiPolicyMode.DEFAULT, "二分题面")));
        JsonNode input = input("{\"query\":\"二分\"}");
        limited.execute(context, input);
        limited.execute(context, input);

        ToolResult<Object> third = limited.execute(context, input);

        assertThat(third.status()).isEqualTo(ToolStatus.POLICY_DENIED);
        assertThat(third.errorCode()).isEqualTo("NOT_ACCESSIBLE");
        assertThat(third.errorMessage()).isEqualTo(UNIFIED);
        verify(recorder).record(eq("t1"), eq(7L), eq("c1"),
                eq(GuardLayer.TOOL_ABAC), eq(GuardDecision.REFUSE), anyList(),
                eq("contest_search_rate_limited"), any(), eq(false), isNull());
    }

    private JsonNode input(String json) throws Exception {
        return OBJECT_MAPPER.readTree(json);
    }

    @SuppressWarnings("unchecked")
    private Map<String, Object> data(ToolResult<Object> result) {
        return (Map<String, Object>) result.data();
    }

    @SuppressWarnings("unchecked")
    private List<Map<String, Object>> hits(Map<String, Object> data) {
        return (List<Map<String, Object>>) data.get("hits");
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
                                                           ContestAiPolicyMode mode, String statement) {
        return new ContestPolicyView.ContestProblemPolicy(problemId, visibility, mode, null, statement,
                List.of(new RunningContestProblemOccurrence(5501L, 7701L, 99001L + problemId)));
    }
}
