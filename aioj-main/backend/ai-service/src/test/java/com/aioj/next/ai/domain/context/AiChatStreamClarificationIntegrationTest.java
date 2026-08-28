package com.aioj.next.ai.domain.context;

import com.aioj.next.ai.domain.AiCompletion;
import com.aioj.next.contract.ai.AiChatRequest;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

class AiChatStreamClarificationIntegrationTest {
    private static final String QUESTION = "如果我们假设最小距离为某个值，我们如何检查这个距离是否能够满足选择 m 个星港的条件？";

    private final ObjectMapper objectMapper = new ObjectMapper();
    private final ConversationStateMerger merger = new ConversationStateMerger(objectMapper);
    private final ConversationContextPackBuilder builder = new ConversationContextPackBuilder(merger, objectMapper);

    @Test
    void streamClarificationAnswerFlowProducesContextPackWithoutQuestionAnswerConcatenation() throws Exception {
        String state = merger.mergeBeforePrompt(null, new AiChatRequest(
                "c-stream",
                null,
                "星港建设：选 m 个星港让最小距离最大，2 <= m <= n <= 2e5，xi <= 1e9。",
                "hint",
                null,
                null,
                null
        )).stateJson();
        AiCompletion.Clarification clarification = new AiCompletion.Clarification(
                "clarify_feasibility_check",
                "helpful",
                "检查方式",
                QUESTION,
                new AiCompletion.ClarificationInput("free_text", true, List.of(), true, "free_text", ""),
                List.of(),
                "ask_user",
                null
        );
        String clarificationSsePayload = objectMapper.writeValueAsString(clarification);
        state = merger.mergeAfterCompletion(state, null, new AiChatRequest("c-stream", null, "这题怎么入手？", "hint", null, null, null), new AiCompletion("请先想想 check(d)。", "mock", "mock", 1, 1, clarification));

        String visibleMessage = "已补充：是先用二分找距离，再检查距离是否合理吗？";
        AiChatRequest answerRequest = new AiChatRequest(
                "c-stream",
                null,
                visibleMessage,
                "hint",
                null,
                null,
                new AiChatRequest.ClarificationAnswer(
                        "clarify_feasibility_check",
                        QUESTION,
                        "是先用二分找距离，再检查距离是否合理吗？",
                        List.of(),
                        null
                )
        );
        state = merger.mergeBeforePrompt(state, answerRequest).stateJson();
        String contextPack = builder.build(answerRequest, state, List.of(), List.of(), "[User Rules]\n完整代码默认 C++。", "");
        Map<String, Object> flow = map(merger.readState(state).get("learningFlow"));

        assertThat(clarificationSsePayload).contains("clarify_feasibility_check").contains("free_text");
        assertThat(visibleMessage).startsWith("已补充：");
        assertThat(visibleMessage).doesNotContain(QUESTION);
        assertThat(contextPack).contains("Clarification Answer Just Submitted");
        assertThat(contextPack).contains("用户是在回答你之前的问题");
        assertThat(contextPack).contains("完整代码默认 C++");
        assertThat(list(flow.get("answeredClarificationIds"))).contains("clarify_feasibility_check");
        assertThat(list(flow.get("pendingClarificationIds"))).doesNotContain("clarify_feasibility_check");
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
