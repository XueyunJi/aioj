package com.aioj.next.ai.domain.context;

import com.aioj.next.ai.persistence.entity.AiConversationSummaryEntity;
import com.aioj.next.ai.persistence.entity.AiMessageEntity;
import com.aioj.next.ai.domain.AiCompletion;
import com.aioj.next.contract.ai.AiChatRequest;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

import java.time.LocalDateTime;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class ConversationContextPackBuilderTest {
    private final ObjectMapper objectMapper = new ObjectMapper();
    private final ConversationStateMerger merger = new ConversationStateMerger(objectMapper);
    private final ConversationContextPackBuilder builder = new ConversationContextPackBuilder(merger, objectMapper);

    @Test
    void contextPackContainsClarificationAnswerStateLongTermMemoryAndNoAnsweredQuestionRepeat() {
        String question = "如果我们假设最小距离为某个值，我们如何检查这个距离是否能够满足选择 m 个星港的条件？";
        AiChatRequest request = new AiChatRequest(
                "c1",
                null,
                "已补充：是先用二分找距离，再检查距离是否合理吗？",
                "hint",
                null,
                null,
                new AiChatRequest.ClarificationAnswer("clarify_check", question, "是先用二分找距离，再检查距离是否合理吗？", List.of(), null)
        );
        String state = merger.mergeBeforePrompt(null, new AiChatRequest(
                "c1",
                null,
                "星港建设题：选择 m 个点让最小距离最大，n <= 2e5，xi <= 1e9。",
                "hint",
                null,
                null,
                null
        )).stateJson();
        state = merger.mergeBeforePrompt(state, request).stateJson();

        String pack = builder.build(
                request,
                state,
                List.of(message(1L, "user", "这题怎么入手？")),
                List.of(summary("用户已经粘贴星港题，算法方向是排序 + 二分答案 + 贪心。")),
                "[User Rules - Must Follow]\n默认用 C++。\n[Relevant Preferences]\n不要直接给完整答案，先给提示。",
                ""
        );

        assertThat(pack).contains("[Clarification Answer Just Submitted]");
        assertThat(pack).contains("用户是在回答你之前的问题");
        assertThat(pack).contains("最大化最小距离");
        assertThat(pack).contains("排序 + 二分答案 + 贪心");
        assertThat(pack).contains("默认用 C++");
        assertThat(pack).contains("不要直接给完整答案");
        assertThat(pack).contains("Do not repeat");
        assertThat(pack).doesNotContain("DISABLED");
    }

    @Test
    void continuationWordsAreRepresentedInStateAndPack() {
        String state = merger.mergeBeforePrompt(null, new AiChatRequest("c1", null, "继续讲上面的 check", "hint", null, null, null)).stateJson();
        String pack = builder.build(
                new AiChatRequest("c1", null, "继续", "hint", null, null, null),
                state,
                List.of(message(1L, "assistant", "刚才讲到 check(d)。")),
                List.of(),
                "",
                ""
        );

        assertThat(pack).contains("continue_current_context");
        assertThat(pack).contains("优先承接当前会话状态");
        assertThat(pack).contains("刚才讲到 check(d)");
    }

    @Test
    void selectedSubmissionPackCarriesLatestSubmissionAndAssistantCodeResponsibility() {
        AiChatRequest codeRequest = new AiChatRequest("c1", 99L, "给我一版代码", "assist", null, null, null);
        String state = merger.mergeBeforePrompt(null, codeRequest).stateJson();
        state = merger.mergeAfterCompletion(state, null, codeRequest, new AiCompletion("""
                ```cpp
                #include <bits/stdc++.h>
                using namespace std;
                int main() { return 0; }
                ```
                """, "mock", "mock-model", 10, 20));
        AiChatRequest submissionRequest = new AiChatRequest(
                "c1",
                99L,
                "我提交了，答案错误",
                "assist",
                null,
                null,
                null,
                "client-submission",
                null,
                null,
                new AiChatRequest.SubmissionContext(123L, "EXPLAIN_ERROR", true, "status: WRONG_ANSWER")
        );
        state = merger.mergeBeforePrompt(state, submissionRequest).stateJson();

        String pack = builder.build(
                submissionRequest,
                state,
                List.of(message(1L, "assistant", "我刚才给过一版 C++ 代码。")),
                List.of(),
                "",
                ""
        );

        assertThat(pack)
                .contains("[Submission Focus]")
                .contains("submissionId: 123")
                .contains("latestSubmissionId")
                .contains("Important responsibility context")
                .contains("previous assistant-provided code may be wrong");
    }

    private AiMessageEntity message(Long id, String role, String content) {
        AiMessageEntity message = new AiMessageEntity();
        message.setId(id);
        message.setConversationId("c1");
        message.setUserId(1L);
        message.setRole(role);
        message.setContent(content);
        message.setCreatedAt(LocalDateTime.now());
        return message;
    }

    private AiConversationSummaryEntity summary(String narrative) {
        AiConversationSummaryEntity summary = new AiConversationSummaryEntity();
        summary.summaryType = "compact";
        summary.narrativeSummary = narrative;
        summary.messageStartId = 1L;
        summary.messageEndId = 2L;
        return summary;
    }
}
