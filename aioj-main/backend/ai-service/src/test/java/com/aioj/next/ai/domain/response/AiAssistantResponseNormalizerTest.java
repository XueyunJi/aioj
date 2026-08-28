package com.aioj.next.ai.domain.response;

import com.aioj.next.ai.domain.AiCompletion;
import com.aioj.next.ai.domain.clarification.ClarificationSchemaRepairer;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class AiAssistantResponseNormalizerTest {
    private final AiAssistantResponseNormalizer normalizer = new AiAssistantResponseNormalizer(
            new ObjectMapper().findAndRegisterModules(),
            new ClarificationSchemaRepairer()
    );

    @Test
    void pureProtocolJsonReturnsOnlyVisibleMarkdownAndClarification() {
        AiAssistantResponseNormalizer.NormalizedResponse normalized = normalizer.normalize(new AiCompletion("""
                {
                  "teachingDecision": "SOCRATIC",
                  "stuckLayer": "OPTIMIZATION",
                  "studentLevel": "intermediate",
                  "content": "对，先二分候选距离 `d`，再检查可行性。",
                  "clarification": {
                    "id": "clarify_check_d",
                    "title": "确认 check",
                    "prompt": "你会怎样检查 d 是否可行？",
                    "input": {"kind": "free_text", "required": true, "allowCustom": true},
                    "options": []
                  },
                  "renderHints": {"showProblemContext": "compact"}
                }
                """, "mock", "mock-model", 1, 1));

        assertThat(normalized.completion().content()).isEqualTo("对，先二分候选距离 `d`，再检查可行性。");
        assertThat(normalized.completion().teachingDecision()).isEqualTo("SOCRATIC");
        assertThat(normalized.completion().stuckLayer()).isEqualTo("OPTIMIZATION");
        assertThat(normalized.completion().studentLevel()).isEqualTo("intermediate");
        assertThat(normalized.completion().clarification().id()).isEqualTo("clarify_check_d");
        assertThat(normalized.renderHints()).containsEntry("showProblemContext", "compact");
        assertThat(normalized.completion().content()).doesNotContain("teachingDecision", "clarification");
    }

    @Test
    void fencedProtocolJsonIsParsed() {
        AiAssistantResponseNormalizer.NormalizedResponse normalized = normalizer.normalize(new AiCompletion("""
                ```json
                {
                  "teachingDecision": "HINT",
                  "content": "先排序，再考虑答案是否具有单调性。",
                  "clarification": {"options": []}
                }
                ```
                """, "mock", "mock-model", 1, 1));

        assertThat(normalized.completion().content()).isEqualTo("先排序，再考虑答案是否具有单调性。");
        assertThat(normalized.parseWarnings()).isEmpty();
    }

    @Test
    void mixedTextProtocolJsonIsParsedWithoutLeakingMetadata() {
        AiAssistantResponseNormalizer.NormalizedResponse normalized = normalizer.normalize(new AiCompletion("""
                下面是结果：
                {
                  "teachingDecision": "EXPLAIN",
                  "stuckLayer": "UNKNOWN",
                  "studentLevel": "unknown",
                  "content": "从左到右扫描，选满足距离 d 的最靠左可行位置。",
                  "clarification": {"options": []}
                }
                """, "mock", "mock-model", 1, 1));

        assertThat(normalized.completion().content()).isEqualTo("从左到右扫描，选满足距离 d 的最靠左可行位置。");
        assertThat(normalized.parseWarnings()).contains(AiAssistantResponseNormalizer.WARNING_EXTRACTED_JSON_FROM_MIXED_TEXT);
        assertThat(normalized.completion().content()).doesNotContain("teachingDecision", "stuckLayer", "studentLevel");
    }

    @Test
    void markdownJsonExampleIsNotMisparsed() {
        String markdown = """
                可以用这个 JSON 理解输入：

                ```json
                {"n": 3, "values": [1, 2, 3]}
                ```

                这只是样例，不是内部协议。
                """;

        AiAssistantResponseNormalizer.NormalizedResponse normalized = normalizer.normalize(new AiCompletion(markdown, "mock", "mock-model", 1, 1));

        assertThat(normalized.completion().content()).isEqualTo(markdown);
        assertThat(normalized.parseWarnings()).isEmpty();
    }

    @Test
    void malformedInternalJsonFallsBackWithoutLeakingRawPayload() {
        AiAssistantResponseNormalizer.NormalizedResponse normalized = normalizer.normalize(new AiCompletion("""
                {
                  "teachingDecision": "HINT",
                  "content": "这里缺了结束引号,
                  "clarification": {"options": []}
                """, "mock", "mock-model", 1, 1));

        assertThat(normalized.completion().content()).isEqualTo("AI 回复解析失败，请重试或查看调试信息。");
        assertThat(normalized.completion().content()).doesNotContain("teachingDecision", "content", "clarification");
        assertThat(normalized.parseWarnings()).contains(AiAssistantResponseNormalizer.WARNING_MALFORMED_INTERNAL_JSON);
    }

    @Test
    void emptyContentWithClarificationShowsSafePrompt() {
        AiAssistantResponseNormalizer.NormalizedResponse normalized = normalizer.normalize(new AiCompletion("""
                {
                  "teachingDecision": "CLARIFY",
                  "content": "",
                  "clarification": {
                    "id": "clarify_code",
                    "title": "补充代码",
                    "prompt": "请粘贴当前代码。",
                    "input": {"kind": "code", "required": true},
                    "options": []
                  }
                }
                """, "mock", "mock-model", 1, 1));

        assertThat(normalized.completion().content()).isEqualTo("我需要先确认一个信息，请看下方补充框。");
        assertThat(normalized.completion().clarification().input().kind()).isEqualTo("code");
        assertThat(normalized.parseWarnings()).contains(AiAssistantResponseNormalizer.WARNING_EMPTY_CONTENT_WITH_CLARIFICATION);
    }

    @Test
    void assistantHistoryVisibleContentIsNormalized() {
        String visible = normalizer.normalizeVisibleContent("assistant", """
                {"teachingDecision":"HINT","content":"只显示这一句。","clarification":{"options":[]}}
                """);

        assertThat(visible).isEqualTo("只显示这一句。");
        assertThat(normalizer.normalizeVisibleContent("user", "{\"content\":\"用户原文\"}")).isEqualTo("{\"content\":\"用户原文\"}");
    }
}
