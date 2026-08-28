package com.aioj.next.ai.domain.context;

import com.aioj.next.ai.domain.AiCompletion;
import com.aioj.next.contract.ai.AiChatRequest;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

class ClarificationPolicyTest {
    private final ClarificationPolicy policy = new ClarificationPolicy();
    private final AiTeachingStrategyRouter router = new AiTeachingStrategyRouter();

    @Test
    void directCodeRequestBlocksHelpfulClarification() {
        AiChatRequest request = new AiChatRequest("c1", 1L, "先给代码，不要反问", "hint", null, null, null);
        AiCompletion completion = completion("请描述你的题目理解。", "helpful");

        AiCompletion filtered = policy.apply(completion, request, Map.of(), router.route(request, Map.of()));

        assertThat(filtered.hasClarification()).isFalse();
    }

    @Test
    void knownProblemContextBlocksRepeatedProblemRequest() {
        AiChatRequest request = new AiChatRequest("c1", 1L, "继续讲", "hint", null, null, null);
        AiCompletion completion = completion("请提供完整题目和数据范围。", "helpful");

        AiCompletion filtered = policy.apply(completion, request, Map.of(), router.route(request, Map.of()));

        assertThat(filtered.hasClarification()).isFalse();
    }

    @Test
    void knownCodeContextBlocksRepeatedCodeRequest() {
        AiChatRequest request = new AiChatRequest(
                "c1",
                1L,
                "帮我看哪里 WA",
                "debug",
                null,
                new AiChatRequest.CodeContext("cpp", "int main(){}"),
                null
        );
        AiCompletion completion = completion("请提供完整代码。", "helpful");

        AiCompletion filtered = policy.apply(completion, request, Map.of(), router.route(request, Map.of()));

        assertThat(filtered.hasClarification()).isFalse();
    }

    @Test
    void blockingClarificationIsKept() {
        AiChatRequest request = new AiChatRequest("c1", null, "给代码", "hint", null, null, null);
        AiCompletion completion = completion("请先提供题目。", "blocking");

        AiCompletion filtered = policy.apply(completion, request, Map.of(), router.route(request, Map.of()));

        assertThat(filtered.hasClarification()).isTrue();
    }

    private AiCompletion completion(String question, String priority) {
        return new AiCompletion(
                "正文",
                "mock",
                "mock",
                1,
                1,
                new AiCompletion.Clarification(
                        "clarify_test",
                        priority,
                        "确认",
                        question,
                        new AiCompletion.ClarificationInput("free_text", false, List.of(), true, "free_text", ""),
                        List.of(),
                        "ask_user",
                        null
                )
        );
    }
}
