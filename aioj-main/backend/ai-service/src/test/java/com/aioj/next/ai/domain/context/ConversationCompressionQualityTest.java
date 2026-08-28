package com.aioj.next.ai.domain.context;

import com.aioj.next.ai.persistence.entity.AiMessageEntity;
import com.aioj.next.contract.ai.AiChatRequest;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

class ConversationCompressionQualityTest {
    private static final TypeReference<Map<String, Object>> MAP_TYPE = new TypeReference<>() {
    };

    private final ObjectMapper objectMapper = new ObjectMapper();
    private final ConversationStateMerger merger = new ConversationStateMerger(objectMapper);
    private final ConversationCompressionService compression = new ConversationCompressionService(merger, objectMapper);

    @Test
    void compactSummaryHasNonEmptyStructureSalienceTokensAndKeptSegments() throws Exception {
        String state = merger.mergeBeforePrompt(null, new AiChatRequest(
                "c-quality",
                null,
                "星港建设：选择 m 个星港让最小距离最大，2 <= m <= n <= 2e5，xi <= 1e9。",
                "hint",
                null,
                null,
                null
        )).stateJson();
        state = merger.mergeBeforePrompt(state, new AiChatRequest(
                "c-quality",
                null,
                "已补充：是先用二分找距离，再检查距离是否合理吗？",
                "hint",
                null,
                null,
                new AiChatRequest.ClarificationAnswer("clarify_check", "如何检查 d？", "是先用二分找距离，再检查距离是否合理吗？", List.of(), null)
        )).stateJson();

        ConversationCompressionService.CompressionResult result = compression.compact(state, List.of(
                message(1L, "user", "题面包含 n <= 2e5，xi <= 1e9。"),
                message(2L, "assistant", "check(d) 从左到右扫描，选择最靠左可行位置。"),
                message(3L, "user", "已补充：是先用二分找距离，再检查距离是否合理吗？")
        ));
        Map<String, Object> structured = objectMapper.readValue(result.structuredSummaryJson(), MAP_TYPE);

        assertThat(result.valid()).isTrue();
        assertThat(result.salienceScore()).isGreaterThan(0);
        assertThat(result.tokenEstimate()).isGreaterThan(0);
        assertThat(result.warnings()).isEmpty();
        assertThat(map(structured.get("problem")).get("statementSummary")).asString().contains("星港");
        assertThat(list(structured.get("keptSegments"))).isNotEmpty();
        assertThat(structured.get("salienceScore")).isInstanceOf(Number.class);
        assertThat(structured.get("tokenEstimate")).isInstanceOf(Number.class);
    }

    @Test
    void missingCriticalConstraintsMakesCompressionInvalidWithWarning() {
        String state = """
                {"problem":{"title":"缺约束题","statementSummary":"从一些点中选择若干点，让最小距离最大。","constraints":[]},"learningFlow":{"userKnownPoints":[],"userStuckPoints":[],"doNotRepeatQuestions":[]},"algorithmState":{"candidateApproach":"二分答案","decisions":[]},"codeState":{},"clarifications":{"answers":[]},"summary":{}}
                """;

        ConversationCompressionService.CompressionResult result = compression.compact(state, List.of(message(1L, "user", "题目描述：最小距离最大。")));

        assertThat(result.valid()).isFalse();
        assertThat(result.warnings()).anySatisfy(warning -> assertThat(warning.code()).isEqualTo("MISSING_CONSTRAINTS"));
    }

    @Test
    void emptyLowValueConversationDoesNotWriteShellSummary() {
        ConversationCompressionService.CompressionResult result = compression.compact("{}", List.of(
                message(1L, "assistant", "你好，请仔细阅读题目。")
        ));

        assertThat(result.valid()).isFalse();
        assertThat(result.warnings()).anySatisfy(warning -> assertThat(warning.code()).isEqualTo("EMPTY_KEPT_SEGMENTS"));
    }

    private AiMessageEntity message(Long id, String role, String content) {
        AiMessageEntity message = new AiMessageEntity();
        message.setId(id);
        message.setConversationId("c-quality");
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

    @SuppressWarnings("unchecked")
    private List<Object> list(Object value) {
        return (List<Object>) value;
    }
}
