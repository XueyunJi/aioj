package com.aioj.next.ai.domain.context;

import com.aioj.next.contract.ai.AiChatRequest;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class ConversationLongTermMemoryPackIntegrationTest {
    private final ObjectMapper objectMapper = new ObjectMapper();
    private final ConversationStateMerger merger = new ConversationStateMerger(objectMapper);
    private final ConversationContextPackBuilder builder = new ConversationContextPackBuilder(merger, objectMapper);

    @Test
    void activeRuleAndPreferenceEnterContextPackButDisabledMemoryIsExcludedByRecallInput() {
        String state = merger.mergeBeforePrompt(null, new AiChatRequest(
                "c-memory",
                null,
                "给我完整正确代码并分步讲解。",
                "hint",
                null,
                null,
                null
        )).stateJson();
        String recalledActiveMemories = """
                [User Rules - Must Follow]
                完整代码默认 C++。
                [Relevant Preferences]
                先给提示，不要直接完整答案。
                """;

        String pack = builder.build(
                new AiChatRequest("c-memory", null, "给我完整正确代码并分步讲解。", "hint", null, null, null),
                state,
                List.of(),
                List.of(),
                recalledActiveMemories,
                ""
        );

        assertThat(pack).contains("完整代码默认 C++");
        assertThat(pack).contains("先给提示");
        assertThat(pack).doesNotContain("默认使用 Python");
        assertThat(pack).doesNotContain("DISABLED");
    }

    @Test
    void hintPreferenceIsAvailableForHowToStartQuestion() {
        String pack = builder.build(
                new AiChatRequest("c-memory", null, "这题怎么入手？", "hint", null, null, null),
                "{}",
                List.of(),
                List.of(),
                "[Relevant Preferences]\n先给提示，不要直接完整答案。",
                ""
        );

        assertThat(pack).contains("Long-term preference: give hints first");
    }

    @Test
    void casualChatCanUseEmptyMemoryRecallWithoutLearningNoise() {
        String pack = builder.build(
                new AiChatRequest("c-memory", null, "随便聊聊天。", "hint", null, null, null),
                "{}",
                List.of(),
                List.of(),
                "",
                ""
        );

        assertThat(pack).doesNotContain("[Relevant Long-Term Memories]");
        assertThat(pack).doesNotContain("二分").doesNotContain("动态规划");
    }
}
