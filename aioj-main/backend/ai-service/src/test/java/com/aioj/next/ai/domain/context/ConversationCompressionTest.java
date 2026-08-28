package com.aioj.next.ai.domain.context;

import com.aioj.next.ai.persistence.entity.AiMessageEntity;
import com.aioj.next.contract.ai.AiChatRequest;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class ConversationCompressionTest {
    private final ObjectMapper objectMapper = new ObjectMapper();
    private final ConversationStateMerger merger = new ConversationStateMerger(objectMapper);
    private final ConversationCompressionService compression = new ConversationCompressionService(merger, objectMapper);

    @Test
    void turnThresholdAndClarificationAnswerTriggerCompression() {
        List<AiMessageEntity> messages = new ArrayList<>();
        for (long i = 1; i <= 10; i++) {
            messages.add(message(i, "assistant", "请仔细阅读题目，理解输入输出，寻找规律。"));
        }
        assertThat(compression.shouldCompact(messages, "{}", false)).isTrue();
        assertThat(compression.shouldCompact(List.of(message(1L, "user", "已补充：二分距离再检查")), "{}", true)).isTrue();
    }

    @Test
    void highValueClarificationAndProblemFactsSurviveCompaction() {
        String state = merger.mergeBeforePrompt(null, new AiChatRequest(
                "c1",
                null,
                "星港建设题：选择 m 个星港让最小距离最大，n <= 2e5，xi <= 1e9。",
                "hint",
                null,
                null,
                null
        )).stateJson();
        state = merger.mergeBeforePrompt(state, new AiChatRequest(
                "c1",
                null,
                "已补充：是先用二分找距离，再检查距离是否合理吗？",
                "hint",
                null,
                null,
                new AiChatRequest.ClarificationAnswer("clarify_check", "如何检查 d？", "是先用二分找距离，再检查距离是否合理吗？", List.of(), null)
        )).stateJson();
        List<AiMessageEntity> messages = List.of(
                message(1L, "assistant", "首先仔细阅读题目，理解输入输出。"),
                message(2L, "user", "已补充：是先用二分找距离，再检查距离是否合理吗？"),
                message(3L, "assistant", "check(d) 应该从左到右扫描。"),
                message(4L, "user", "#include <bits/stdc++.h>\nint main(){return 0;}")
        );

        ConversationCompressionService.CompressionResult result = compression.compact(state, messages);

        assertThat(result.valid()).isTrue();
        assertThat(result.narrativeSummary()).contains("当前题目");
        assertThat(result.narrativeSummary()).contains("算法方向");
        assertThat(result.structuredSummaryJson()).contains("clarificationAnswers");
        assertThat(result.structuredSummaryJson()).contains("二分");
    }

    @Test
    void boilerplateScoresLowerThanClarificationAndAlgorithmDecision() {
        List<ConversationCompressionService.ScoredSegment> scored = compression.scoreSegments(List.of(
                message(1L, "assistant", "首先仔细阅读题目，理解输入输出，寻找规律。"),
                message(2L, "user", "已补充：是先用二分找距离，再检查距离是否合理吗？"),
                message(3L, "assistant", "贪心 check(d)：从左到右扫描，选择最靠左可行位置。")
        ));

        assertThat(scored.get(1).score()).isGreaterThan(scored.get(0).score());
        assertThat(scored.get(2).score()).isGreaterThan(scored.get(0).score());
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
}
