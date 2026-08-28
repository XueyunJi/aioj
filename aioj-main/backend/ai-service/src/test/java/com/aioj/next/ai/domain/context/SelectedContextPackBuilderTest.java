package com.aioj.next.ai.domain.context;

import com.aioj.next.contract.ai.AiChatRequest;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class SelectedContextPackBuilderTest {
    private final ObjectMapper objectMapper = new ObjectMapper();
    private final ConversationStateMerger merger = new ConversationStateMerger(objectMapper);
    private final ConversationContextPackBuilder builder = new ConversationContextPackBuilder(merger, objectMapper);

    @Test
    void selectedCodeSpanEntersSelectedContextFocus() {
        AiChatRequest request = request(selection(
                "code_block",
                "assistant",
                "void partition(int arr[], int low, int high, int &pivotIndex) {}",
                "```cpp\nvoid partition(int arr[], int low, int high, int &pivotIndex) {}\n```",
                "debug_selection",
                new AiChatRequest.SelectedCodeContext("cpp", "partition", "quickSort / partition", "m1", "h1", true),
                new AiChatRequest.SelectedProblemContext("1", "快速排序", List.of("quick_sort"), List.of("n <= 2e5"))
        ));

        String pack = builder.build(request, merger.mergeBeforePrompt(null, request).stateJson(), List.of(), List.of(), "", "");

        assertThat(pack).contains("[Selected Context Focus]");
        assertThat(pack).contains("Source: code_block");
        assertThat(pack).contains("Function: partition");
        assertThat(pack).contains("Selected markdown");
        assertThat(pack).contains("Routed strategy: DEBUG_SELECTED_CODE");
        assertThat(pack).doesNotContain("[Relevant Long-Term Memories]");
    }

    @Test
    void selectedAssistantMessageCarriesSourceAndSurroundingContext() {
        AiChatRequest request = request(selection(
                "assistant_message",
                "assistant",
                "这里不是每次选最远位置。",
                null,
                "ask_about_selection",
                null,
                null
        ));

        String pack = builder.build(request, merger.mergeBeforePrompt(null, request).stateJson(), List.of(), List.of(), "", "");

        assertThat(pack).contains("[Selected Context Focus]");
        assertThat(pack).contains("Source message id: m1");
        assertThat(pack).contains("Before: before text");
        assertThat(pack).contains("After: after text");
    }

    @Test
    void selectedProblemConstraintCarriesProblemContext() {
        AiChatRequest request = request(selection(
                "problem_context",
                null,
                "2 <= m <= n <= 2e5",
                null,
                "explain_selection",
                null,
                new AiChatRequest.SelectedProblemContext("205", "星港建设", List.of("binary_search_on_answer"), List.of("2 <= m <= n <= 2e5"))
        ));

        String pack = builder.build(request, merger.mergeBeforePrompt(null, request).stateJson(), List.of(), List.of(), "", "");

        assertThat(pack).contains("Problem: 星港建设");
        assertThat(pack).contains("Problem tags: binary_search_on_answer");
        assertThat(pack).contains("Problem constraints: 2 <= m <= n <= 2e5");
    }

    private AiChatRequest request(AiChatRequest.SelectionContext selection) {
        return new AiChatRequest("c1", 1L, "解释我选中的部分", "debug", null, null, null, "client-1", selection);
    }

    private AiChatRequest.SelectionContext selection(
            String sourceType,
            String sourceRole,
            String selectedText,
            String selectedMarkdown,
            String uiIntent,
            AiChatRequest.SelectedCodeContext code,
            AiChatRequest.SelectedProblemContext problem
    ) {
        return new AiChatRequest.SelectionContext(
                "s1",
                "c1",
                sourceType,
                "m1",
                sourceRole,
                selectedText,
                selectedMarkdown,
                new AiChatRequest.SelectionRange(0, selectedText.length(), 12, 15),
                new AiChatRequest.SurroundingContext("before text", "after text", "代码实现", "message preview"),
                code,
                problem,
                uiIntent
        );
    }
}
