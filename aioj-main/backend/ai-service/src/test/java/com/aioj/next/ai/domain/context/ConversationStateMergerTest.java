package com.aioj.next.ai.domain.context;

import com.aioj.next.ai.domain.AiCompletion;
import com.aioj.next.contract.ai.AiChatRequest;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

class ConversationStateMergerTest {
    private final ConversationStateMerger merger = new ConversationStateMerger(new ObjectMapper());

    @Test
    void pastedStarportProblemUpdatesProblemSummaryConstraintsAndTags() {
        AiChatRequest request = request("""
                星港建设：在一条直线上有 n 个候选坐标，需要选择 m 个星港，
                使任意两个星港之间的最小距离尽可能大。2 <= m <= n <= 2e5，xi <= 1e9。
                """, null);

        ConversationStateMerger.MergeResult result = merger.mergeBeforePrompt(null, request);
        Map<String, Object> state = merger.readState(result.stateJson());
        Map<String, Object> problem = map(state.get("problem"));
        Map<String, Object> flow = map(state.get("learningFlow"));
        Map<String, Object> algorithm = map(state.get("algorithmState"));

        assertThat(problem.get("statementSummary")).asString().contains("任意两个被选星港之间的最小距离最大");
        assertThat(list(problem.get("constraints"))).contains("2 <= m <= n <= 2e5", "0 <= xi <= 1e9");
        assertThat(list(problem.get("tags"))).contains("sorting", "binary_search_on_answer", "greedy");
        assertThat(flow.get("currentStep")).isEqualTo("explain_feasibility_check");
        assertThat(algorithm.get("candidateApproach")).isEqualTo("排序 + 二分答案 + 贪心可行性检查");
    }

    @Test
    void clarificationAnswerUpdatesAnsweredPendingDoNotRepeatAndKnownPoints() {
        AiCompletion.Clarification clarification = new AiCompletion.Clarification(
                "clarify_check",
                "helpful",
                "检查方式",
                "如果我们假设最小距离为某个值，我们如何检查这个距离是否能够满足选择 m 个星港的条件？",
                new AiCompletion.ClarificationInput("free_text", true, List.of(), true, "free_text", ""),
                List.of(),
                "ask_user",
                null
        );
        String withPending = merger.mergeAfterCompletion(null, null, request("这题怎么入手？", null), new AiCompletion("", "mock", "mock", 1, 1, clarification));
        AiChatRequest answerRequest = request("已补充：是先用二分找距离，再检查距离是否合理吗？", new AiChatRequest.ClarificationAnswer(
                "clarify_check",
                clarification.prompt(),
                "是先用二分找距离，再检查距离是否合理吗？",
                List.of(),
                null
        ));

        ConversationStateMerger.MergeResult result = merger.mergeBeforePrompt(withPending, answerRequest);
        Map<String, Object> state = merger.readState(result.stateJson());
        Map<String, Object> flow = map(state.get("learningFlow"));
        Map<String, Object> algorithm = map(state.get("algorithmState"));

        assertThat(list(flow.get("answeredClarificationIds"))).contains("clarify_check");
        assertThat(list(flow.get("pendingClarificationIds"))).doesNotContain("clarify_check");
        assertThat(list(flow.get("doNotRepeatQuestions"))).contains(clarification.prompt());
        assertThat(list(flow.get("userKnownPoints"))).anySatisfy(item -> assertThat(String.valueOf(item)).contains("二分候选距离"));
        assertThat(list(flow.get("userStuckPoints"))).anySatisfy(item -> assertThat(String.valueOf(item)).contains("check(d)"));
        assertThat(flow.get("currentStep")).isEqualTo("explain_greedy_check_d");
        assertThat(list(algorithm.get("decisions"))).anySatisfy(item -> assertThat(String.valueOf(item)).contains("二分候选最小距离 d"));
    }

    @Test
    void userCorrectionIsKeptAsCurrentIntentInsteadOfConflictingGoal() {
        String state = merger.mergeBeforePrompt(null, request("这题先不要给代码", null)).stateJson();
        state = merger.mergeBeforePrompt(state, request("现在可以给完整代码并分步讲解", null)).stateJson();

        Map<String, Object> flow = map(merger.readState(state).get("learningFlow"));
        assertThat(flow.get("currentGoal")).isEqualTo("provide_code_after_explanation");
        assertThat(flow.get("nextTeachingAction")).asString().contains("完整代码");
    }

    @Test
    void assistantClarificationIsTrackedButNotSavedAsProblemFact() {
        AiCompletion.Clarification clarification = new AiCompletion.Clarification(
                "clarify_code",
                "blocking",
                "需要代码",
                "请粘贴当前代码",
                new AiCompletion.ClarificationInput("code", true, List.of(), true, "code", "粘贴代码"),
                List.of(),
                "ask_user",
                null
        );
        String stateJson = merger.mergeAfterCompletion(null, null, request("哪里错了？", null), new AiCompletion("请粘贴代码。", "mock", "mock", 1, 1, clarification));
        Map<String, Object> state = merger.readState(stateJson);

        assertThat(list(map(state.get("learningFlow")).get("pendingClarificationIds"))).contains("clarify_code");
        assertThat(map(state.get("problem")).get("statementSummary")).isNull();
        assertThat(list(map(state.get("algorithmState")).get("unverifiedAssistantClaims"))).isEmpty();
    }

    private AiChatRequest request(String message, AiChatRequest.ClarificationAnswer answer) {
        return new AiChatRequest("c1", null, message, "hint", null, null, answer);
    }

    @SuppressWarnings("unchecked")
    private Map<String, Object> map(Object value) {
        return (Map<String, Object>) value;
    }

    @SuppressWarnings("unchecked")
    private List<Object> list(Object value) {
        return (List<Object>) value;
    }
}
