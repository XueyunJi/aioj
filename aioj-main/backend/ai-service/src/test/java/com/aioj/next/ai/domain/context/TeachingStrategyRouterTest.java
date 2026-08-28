package com.aioj.next.ai.domain.context;

import com.aioj.next.contract.ai.AiChatRequest;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

class TeachingStrategyRouterTest {
    private final AiTeachingStrategyRouter router = new AiTeachingStrategyRouter();

    @Test
    void explicitCodeRequestRoutesToDirectCodeWhenProblemExists() {
        AiTeachingStrategyRouter.StrategyDecision decision = router.route(
                new AiChatRequest("c1", 1L, "我需要你先给出代码，再按照代码详细讲解", "hint", null, null, null),
                Map.of()
        );

        assertThat(decision.strategy()).isEqualTo(AiTeachingStrategyRouter.TeachingStrategy.DIRECT_CODE_THEN_EXPLAIN);
    }

    @Test
    void compositeThinkingAndCodeQuestionRoutesToDirectCode() {
        AiTeachingStrategyRouter.StrategyDecision decision = router.route(
                new AiChatRequest("c1", 1L, "能给我这道题你的解题思路和代码吗？按照你的思路讲解你的代码", "hint", null, null, null),
                Map.of()
        );

        assertThat(decision.strategy()).isEqualTo(AiTeachingStrategyRouter.TeachingStrategy.DIRECT_CODE_THEN_EXPLAIN);
        assertThat(decision.reason()).isEqualTo("composite_code_and_explanation_request");
    }

    @Test
    void knownAlgorithmsButCannotApplyRoutesToBridgeMode() {
        AiTeachingStrategyRouter.StrategyDecision decision = router.route(
                new AiChatRequest("c1", 1L, "这些算法我都知道，但是我不会在这道题里应用", "hint", null, null, null),
                Map.of()
        );

        assertThat(decision.strategy()).isEqualTo(AiTeachingStrategyRouter.TeachingStrategy.APPLY_KNOWN_ALGORITHM_TO_PROBLEM);
    }

    @Test
    void selectedCodeQuestionRoutesToDebugSelectedCode() {
        AiChatRequest request = new AiChatRequest(
                "c1",
                1L,
                "这个函数是不是没有返回给 quickSort？",
                "debug",
                null,
                null,
                null,
                "client-1",
                new AiChatRequest.SelectionContext(
                        "s1",
                        "c1",
                        "code_block",
                        "m1",
                        "assistant",
                        "void partition(int &pivotIndex) {}",
                        "```cpp\nvoid partition(int &pivotIndex) {}\n```",
                        new AiChatRequest.SelectionRange(0, 32, 12, 15),
                        null,
                        new AiChatRequest.SelectedCodeContext("cpp", "partition", "partition", "m1", "h1", true),
                        new AiChatRequest.SelectedProblemContext("1", "快速排序", List.of("quick_sort"), List.of()),
                        "debug_selection"
                )
        );

        assertThat(router.route(request, Map.of()).strategy()).isEqualTo(AiTeachingStrategyRouter.TeachingStrategy.DEBUG_SELECTED_CODE);
    }

    @Test
    void hintPreferenceBeatsCodeRequest() {
        AiTeachingStrategyRouter.StrategyDecision decision = router.route(
                new AiChatRequest("c1", 1L, "只提示，不要完整答案", "hint", null, null, null),
                Map.of()
        );

        assertThat(decision.strategy()).isEqualTo(AiTeachingStrategyRouter.TeachingStrategy.SOCRATIC_HINT);
    }

    @Test
    void missingProblemInfoRoutesToAskForProblemInfo() {
        AiTeachingStrategyRouter.StrategyDecision decision = router.route(
                new AiChatRequest("c1", null, "这题怎么入手？", "hint", null, null, null),
                Map.of()
        );

        assertThat(decision.strategy()).isEqualTo(AiTeachingStrategyRouter.TeachingStrategy.ASK_FOR_PROBLEM_INFO);
    }
}
