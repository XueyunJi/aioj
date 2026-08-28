package com.aioj.next.ai.domain.context;

import com.aioj.next.ai.persistence.entity.AiMessageEntity;
import com.aioj.next.contract.ai.AiChatRequest;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

class ClarificationAnswerFlowTest {
    private static final String STARPORT_STATEMENT = """
            星港建设：在一条直线上有 n 个候选坐标，需要选择 m 个星港，使任意两个被选星港之间的最小距离最大。
            输入第一行 n,m，接下来 n 行为坐标 xi。2 <= m <= n <= 2e5，0 <= xi <= 1e9，xi 两两不同。
            输出最大可能的最小距离。
            """;
    private static final String CLARIFICATION_QUESTION = "如果我们假设最小距离为某个值，我们如何检查这个距离是否能够满足选择 m 个星港的条件？";

    private final ObjectMapper objectMapper = new ObjectMapper();
    private final ConversationStateMerger merger = new ConversationStateMerger(objectMapper);
    private final ConversationContextPackBuilder builder = new ConversationContextPackBuilder(merger, objectMapper);

    @Test
    void starportBadCaseBuildsPromptThatMustContinueFromUserAnswer() {
        String state = merger.mergeBeforePrompt(null, new AiChatRequest(
                "c-starport",
                null,
                "这题怎么入手？",
                "hint",
                null,
                null,
                null
        )).stateJson();
        state = merger.mergeBeforePrompt(state, new AiChatRequest(
                "c-starport",
                null,
                STARPORT_STATEMENT,
                "hint",
                null,
                null,
                null
        )).stateJson();

        AiChatRequest answer = new AiChatRequest(
                "c-starport",
                null,
                "已补充：是先用二分找距离，再检查距离是否合理吗？",
                "hint",
                null,
                null,
                new AiChatRequest.ClarificationAnswer(
                        "clarify_feasibility_check",
                        CLARIFICATION_QUESTION,
                        "是先用二分找距离，再检查距离是否合理吗？",
                        List.of(),
                        null
                )
        );
        state = merger.mergeBeforePrompt(state, answer).stateJson();
        String pack = builder.build(
                answer,
                state,
                List.of(
                        message(1L, "user", "这题怎么入手？"),
                        message(2L, "assistant", CLARIFICATION_QUESTION),
                        message(3L, "user", "已补充：是先用二分找距离，再检查距离是否合理吗？")
                ),
                List.of(),
                "[User Rules - Must Follow]\n完整代码默认 C++。\n[Relevant Preferences]\n先给提示，不要直接给完整答案。",
                ""
        );
        Map<String, Object> flow = map(merger.readState(state).get("learningFlow"));

        assertThat(pack).contains("Clarification Answer Just Submitted");
        assertThat(pack).contains("用户是在回答你之前的问题");
        assertThat(pack).contains("知道可以先二分候选距离");
        assertThat(pack).contains("check(d)");
        assertThat(pack).contains("最大化最小距离");
        assertThat(pack).contains("n <= 2e5");
        assertThat(pack).contains("xi <= 1e9");
        assertThat(pack).contains("排序 + 二分答案 + 贪心");
        assertThat(pack).contains("Do not repeat");
        assertThat(flow.get("currentStep")).isEqualTo("explain_greedy_check_d");
        assertThat(list(flow.get("userKnownPoints"))).anySatisfy(item -> assertThat(String.valueOf(item)).contains("二分候选距离"));
        assertThat(list(flow.get("userStuckPoints"))).anySatisfy(item -> assertThat(String.valueOf(item)).contains("最靠左可行位置"));
    }

    @Test
    void expectedAssistantReplyShapeForBadCaseIsEnforcedByContextPack() {
        AiChatRequest answer = new AiChatRequest(
                "c-starport",
                null,
                "已补充：是先用二分找距离，再检查距离是否合理吗？",
                "hint",
                null,
                null,
                new AiChatRequest.ClarificationAnswer("clarify_feasibility_check", CLARIFICATION_QUESTION, "是先用二分找距离，再检查距离是否合理吗？", List.of(), null)
        );
        String state = merger.mergeBeforePrompt(null, new AiChatRequest("c-starport", null, STARPORT_STATEMENT, "hint", null, null, null)).stateJson();
        state = merger.mergeBeforePrompt(state, answer).stateJson();
        String pack = builder.build(answer, state, List.of(), List.of(), "", "");

        assertThat(pack).contains("First evaluate whether it is correct");
        assertThat(pack).contains("explain check(d)");
        assertThat(pack).contains("sort, scan left to right");
        assertThat(pack).contains("pick the leftmost feasible next position");
        assertThat(pack).contains("not choosing the farthest");
    }

    private AiMessageEntity message(Long id, String role, String content) {
        AiMessageEntity message = new AiMessageEntity();
        message.setId(id);
        message.setConversationId("c-starport");
        message.setUserId(1L);
        message.setRole(role);
        message.setContent(content);
        message.setCreatedAt(LocalDateTime.now());
        return message;
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
