package com.aioj.next.ai.domain.context;

import com.aioj.next.ai.persistence.entity.AiMessageEntity;
import com.aioj.next.contract.ai.AiChatRequest;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

class ConversationReferenceResolutionTest {
    private final ObjectMapper objectMapper = new ObjectMapper();
    private final ConversationStateMerger merger = new ConversationStateMerger(objectMapper);
    private final ConversationContextPackBuilder builder = new ConversationContextPackBuilder(merger, objectMapper);

    @Test
    void continueUsesCurrentTeachingStepAndStuckPoint() {
        String state = merger.mergeBeforePrompt(null, request("星港建设：选 m 个星港让最小距离最大，n <= 2e5，xi <= 1e9。", null)).stateJson();
        state = merger.mergeBeforePrompt(state, request(
                "已补充：是先用二分找距离，再检查距离是否合理吗？",
                new AiChatRequest.ClarificationAnswer("clarify_check", "如何检查 d？", "是先用二分找距离，再检查距离是否合理吗？", List.of(), null)
        )).stateJson();
        state = merger.mergeBeforePrompt(state, request("继续", null)).stateJson();

        String pack = builder.build(request("继续", null), state, List.of(message(1L, "assistant", "刚才讲到 check(d)。")), List.of(), "", "");
        Map<String, Object> flow = map(merger.readState(state).get("learningFlow"));

        assertThat(flow.get("currentGoal")).isEqualTo("continue_current_context");
        assertThat(pack).contains("explain_greedy_check_d");
        assertThat(pack).contains("最靠左可行位置");
        assertThat(pack).doesNotContain("请提供题目描述、数据范围或样例");
    }

    @Test
    void aboveCodeQuestionUsesLatestCodeSnapshotAndErrorType() {
        String state = merger.mergeBeforePrompt(null, request("这段代码 WA 了，上面的代码为什么错？", null)).stateJson();
        state = merger.mergeAfterCompletion(state, 123L, new AiChatRequest(
                "c-ref",
                null,
                "上面的代码为什么 WA？",
                "debug",
                null,
                new AiChatRequest.CodeContext("cpp", "#include <bits/stdc++.h>\nint main(){return 0;}"),
                null
        ), new com.aioj.next.ai.domain.AiCompletion("看起来需要检查边界。", "mock", "mock", 1, 1));

        String pack = builder.build(request("上面的代码为什么错？", null), state, List.of(), List.of(), "", "");

        assertThat(pack).contains("latestCodeSnapshotId");
        assertThat(pack).contains("123");
        assertThat(pack).contains("WRONG_ANSWER");
    }

    @Test
    void recentlyAnsweredClarificationCanBeTargetedByJustNowReference() {
        String question = "如果我们假设最小距离为某个值，我们如何检查这个距离是否能够满足选择 m 个星港的条件？";
        String state = merger.mergeBeforePrompt(null, request("星港建设题，选 m 个星港让最小距离最大，n <= 2e5，xi <= 1e9。", null)).stateJson();
        state = merger.mergeBeforePrompt(state, request(
                "已补充：是先用二分找距离，再检查距离是否合理吗？",
                new AiChatRequest.ClarificationAnswer("clarify_check", question, "是先用二分找距离，再检查距离是否合理吗？", List.of(), null)
        )).stateJson();
        state = merger.mergeBeforePrompt(state, request("刚才那个我还是不懂", null)).stateJson();

        String pack = builder.build(request("刚才那个我还是不懂", null), state, List.of(), List.of(), "", "");

        assertThat(pack).contains("clarify_check");
        assertThat(pack).contains("用户仍卡住").contains("check(d)");
        assertThat(pack).contains("continue_current_context");
    }

    private AiChatRequest request(String message, AiChatRequest.ClarificationAnswer answer) {
        return new AiChatRequest("c-ref", null, message, "hint", null, null, answer);
    }

    private AiMessageEntity message(Long id, String role, String content) {
        AiMessageEntity message = new AiMessageEntity();
        message.setId(id);
        message.setConversationId("c-ref");
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
}
