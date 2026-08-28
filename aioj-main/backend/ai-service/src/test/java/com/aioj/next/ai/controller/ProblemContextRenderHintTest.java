package com.aioj.next.ai.controller;

import com.aioj.next.contract.ai.AiChatRequest;
import com.aioj.next.contract.problem.TestCaseDto;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Method;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

class ProblemContextRenderHintTest {
    @Test
    void problemContextPayloadKeepsCompactRenderHintsAndConstraintSummary() throws Exception {
        AiController controller = new AiController(
                null,
                null,
                null,
                null,
                null,
                null,
                null,
                null,
                null,
                null,
                null,
                null,
                null,
                new ObjectMapper().findAndRegisterModules()
        );

        Map<String, Object> problemContext = invokeProblemContextPayload(controller, new AiChatRequest(
                "c-problem-hint",
                2058726164939169794L,
                "这题怎么入手？",
                "hint",
                new AiChatRequest.ProblemContext(
                        "2058726164939169794",
                        "星港建设",
                        "MEDIUM",
                        """
                                选择 m 个星港，使最小距离最大。
                                数据范围：2 <= m <= n <= 2e5，0 <= xi <= 1e9。
                                """,
                        "最大化最小值。",
                        List.of("sorting", "binary_search_on_answer", "greedy"),
                        List.of(new TestCaseDto("5 3\n1\n2\n8\n4\n9\n", "3\n", true)),
                        1000,
                        262144
                ),
                null,
                null
        ));
        Map<String, Object> hints = invokeDefaultRenderHints(controller, problemContext);

        assertThat(problemContext).containsEntry("title", "星港建设");
        assertThat(problemContext.get("tags")).asList().contains("sorting", "binary_search_on_answer", "greedy");
        assertThat(problemContext.get("constraints")).asList().anySatisfy(item -> assertThat(item.toString()).contains("n <= 2e5"));
        assertThat(hints).containsEntry("showProblemContext", "compact");
        assertThat(hints.get("problemRefs")).asList().contains("title", "constraints", "tags");
    }

    @SuppressWarnings("unchecked")
    private Map<String, Object> invokeProblemContextPayload(AiController controller, AiChatRequest request) throws Exception {
        Method method = AiController.class.getDeclaredMethod("problemContextPayload", AiChatRequest.class);
        method.setAccessible(true);
        return (Map<String, Object>) method.invoke(controller, request);
    }

    @SuppressWarnings("unchecked")
    private Map<String, Object> invokeDefaultRenderHints(AiController controller, Map<String, Object> problemContext) throws Exception {
        Method method = AiController.class.getDeclaredMethod("defaultRenderHints", Map.class);
        method.setAccessible(true);
        return (Map<String, Object>) method.invoke(controller, problemContext);
    }
}
