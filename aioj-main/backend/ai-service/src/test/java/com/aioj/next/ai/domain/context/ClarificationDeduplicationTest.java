package com.aioj.next.ai.domain.context;

import com.aioj.next.ai.domain.AiCompletion;
import com.aioj.next.contract.ai.AiChatRequest;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

class ClarificationDeduplicationTest {
    private static final String QUESTION = "如果假设最小距离为 d，如何检查能否选出 m 个星港？";

    private final ObjectMapper objectMapper = new ObjectMapper();
    private final ConversationStateMerger merger = new ConversationStateMerger(objectMapper);
    private final ClarificationDeduplicator deduplicator = new ClarificationDeduplicator();

    @Test
    void exactAnsweredQuestionIsSkipped() {
        String state = answeredState("我知道了");

        ClarificationDeduplicator.DedupDecision decision = deduplicator.decide(clarification(QUESTION), merger.readState(state));

        assertThat(decision.skip()).isTrue();
        assertThat(decision.reason()).isEqualTo("similar_to_do_not_repeat_question");
    }

    @Test
    void highlySimilarAnsweredQuestionBecomesSpecificFollowUpWhenAnswerWasIncomplete() {
        String state = answeredState("是先用二分找距离，再检查距离是否合理吗？");

        ClarificationDeduplicator.DedupDecision decision = deduplicator.decide(
                clarification("如何检查这个距离是否合理？"),
                merger.readState(state)
        );

        assertThat(decision.skip()).isFalse();
        assertThat(decision.followUp()).isTrue();
        assertThat(decision.followUpQuestion()).contains("最靠左").contains("最远");
    }

    @Test
    void pendingSimilarQuestionIsSkipped() {
        AiCompletion.Clarification pending = clarification("请描述 check(d) 如何判断这个距离可行？");
        String state = merger.mergeAfterCompletion(null, null, request("这题怎么入手？"), new AiCompletion("请补充。", "mock", "mock", 1, 1, pending));

        ClarificationDeduplicator.DedupDecision decision = deduplicator.decide(
                clarification("如何检查 d 是否可行？"),
                merger.readState(state)
        );

        assertThat(decision.skip()).isTrue();
        assertThat(decision.reason()).isEqualTo("similar_to_do_not_repeat_question");
    }

    private String answeredState(String answerText) {
        String state = merger.mergeAfterCompletion(null, null, request("星港建设题，选 m 个点让最小距离最大，n <= 2e5，xi <= 1e9。"), new AiCompletion(
                "请回答。",
                "mock",
                "mock",
                1,
                1,
                clarification(QUESTION)
        ));
        return merger.mergeBeforePrompt(state, new AiChatRequest(
                "c-dedup",
                null,
                "已补充：" + answerText,
                "hint",
                null,
                null,
                new AiChatRequest.ClarificationAnswer("clarify_check", QUESTION, answerText, List.of(), null)
        )).stateJson();
    }

    private AiCompletion.Clarification clarification(String question) {
        return new AiCompletion.Clarification(
                "clarify_check",
                "helpful",
                "检查方式",
                question,
                new AiCompletion.ClarificationInput("free_text", true, List.of(), true, "free_text", ""),
                List.of(),
                "ask_user",
                null
        );
    }

    private AiChatRequest request(String message) {
        return new AiChatRequest("c-dedup", null, message, "hint", null, null, null);
    }
}
