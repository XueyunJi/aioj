package com.aioj.next.ai.domain.context;

import com.aioj.next.contract.ai.AiChatRequest;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

class ConversationProblemSwitchTest {
    private final ObjectMapper objectMapper = new ObjectMapper();
    private final ConversationStateMerger merger = new ConversationStateMerger(objectMapper);
    private final ConversationContextPackBuilder builder = new ConversationContextPackBuilder(merger, objectMapper);

    @Test
    void sameConversationSwitchesCurrentProblemAndKeepsPreviousInactive() {
        String state = merger.mergeBeforePrompt(null, request("""
                星港建设：选择 m 个星港，使任意两个星港之间的最小距离最大。n <= 2e5，xi <= 1e9。
                """)).stateJson();
        state = merger.mergeBeforePrompt(state, request("""
                换一题：有 n 个物品，每个物品有重量和价值，背包容量为 W。
                请在容量限制下让总价值最大，用动态规划求解。
                """)).stateJson();

        Map<String, Object> parsed = merger.readState(state);
        Map<String, Object> problem = map(parsed.get("problem"));
        Map<String, Object> algorithm = map(parsed.get("algorithmState"));
        List<Object> previous = list(parsed.get("previousProblems"));
        String pack = builder.build(request("继续分析这道背包题"), state, List.of(), List.of(), "", "");

        assertThat(problem.get("title")).asString().contains("背包");
        assertThat(list(problem.get("tags"))).contains("dynamic_programming", "knapsack");
        assertThat(algorithm.get("candidateApproach")).isEqualTo("动态规划 / 0-1 背包状态转移");
        assertThat(previous).hasSize(1);
        assertThat(map(previous.get(0)).get("status")).isEqualTo("inactive");
        assertThat(map(previous.get(0)).get("title")).asString().contains("星港");
        assertThat(pack).contains("动态规划 / 0-1 背包状态转移");
    }

    @Test
    void explicitPreviousProblemReferenceRestoresOldProblem() {
        String state = merger.mergeBeforePrompt(null, request("星港建设：选 m 个星港，让最小距离最大，n <= 2e5，xi <= 1e9。")).stateJson();
        state = merger.mergeBeforePrompt(state, request("换一题：背包容量 W，物品有重量和价值，求最大价值。")).stateJson();
        state = merger.mergeBeforePrompt(state, request("继续刚才星港那题")).stateJson();

        Map<String, Object> parsed = merger.readState(state);
        Map<String, Object> problem = map(parsed.get("problem"));
        Map<String, Object> algorithm = map(parsed.get("algorithmState"));

        assertThat(problem.get("title")).asString().contains("星港");
        assertThat(problem.get("status")).isEqualTo("active");
        assertThat(problem.get("switchReason")).isEqualTo("restored_previous_problem");
        assertThat(algorithm.get("candidateApproach")).isEqualTo("排序 + 二分答案 + 贪心可行性检查");
    }

    @Test
    void newConversationStartsWithoutPreviousProblemState() {
        String state = merger.mergeBeforePrompt(null, request("这题怎么入手？")).stateJson();
        Map<String, Object> parsed = merger.readState(state);

        assertThat(list(parsed.get("previousProblems"))).isEmpty();
        assertThat(map(parsed.get("problem")).get("title")).isNull();
    }

    private AiChatRequest request(String message) {
        return new AiChatRequest("c-switch", null, message, "hint", null, null, null);
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
