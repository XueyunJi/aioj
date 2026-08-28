package com.aioj.next.ai.agent.retrieval.builtin;

import com.aioj.next.ai.agent.tool.ToolExecutionContext;
import com.aioj.next.ai.agent.tool.ToolResult;
import com.aioj.next.ai.agent.tool.ToolStatus;
import com.aioj.next.ai.persistence.entity.AiMessageEntity;
import com.aioj.next.ai.persistence.mapper.AiMessageMapper;
import com.baomidou.mybatisplus.core.conditions.query.QueryWrapper;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class ContextSearchExactToolTest {

    private final AiMessageMapper messageMapper = mock(AiMessageMapper.class);
    private final ObjectMapper objectMapper = new ObjectMapper();
    private final ContextSearchExactTool tool = new ContextSearchExactTool(messageMapper, objectMapper);

    @Test
    void descriptorPassesRegistryRules() {
        // Name, schema subset, no identity fields — construction must satisfy the registry contract.
        new com.aioj.next.ai.agent.tool.ToolRegistry(List.of(tool));
        assertThat(tool.descriptor().name()).isEqualTo("context.search_exact");
        assertThat(tool.descriptor().requiredScopes()).containsExactly("AI_CHAT");
    }

    @Test
    void ranksByMatchedTermCountAndBuildsExcerpt() throws Exception {
        AiMessageEntity weak = message(1L, "user", "二分查找的边界问题", LocalDateTime.now().minusMinutes(2));
        AiMessageEntity strong = message(2L, "assistant", "二分答案 + 二分图都要二分", LocalDateTime.now().minusMinutes(1));
        when(messageMapper.selectList(any(QueryWrapper.class))).thenReturn(List.of(strong, weak));

        ToolResult<Object> result = tool.execute(context(), objectMapper.readTree(
                "{\"exactTerms\":[\"二分\",\"答案\"],\"topK\":2}"));

        assertThat(result.status()).isEqualTo(ToolStatus.SUCCESS);
        @SuppressWarnings("unchecked")
        Map<String, Object> data = (Map<String, Object>) result.data();
        @SuppressWarnings("unchecked")
        List<Map<String, Object>> hits = (List<Map<String, Object>>) data.get("hits");
        assertThat(hits).hasSize(2);
        assertThat(hits.get(0).get("messageId")).isEqualTo("2");
        assertThat((List<?>) hits.get(0).get("matchedTerms")).hasSize(2);
        assertThat(hits.get(0).get("excerpt").toString()).contains("二分");
        assertThat(hits.get(0).get("trustLevel")).isEqualTo("MODEL_INFERRED");
        assertThat(hits.get(1).get("trustLevel")).isEqualTo("USER_PROVIDED");
        assertThat(result.sources()).hasSize(2);
        assertThat(result.classification().name()).isEqualTo("USER_PRIVATE");
    }

    @Test
    void emptyConversationReturnsEmptyHits() throws Exception {
        ToolResult<Object> result = tool.execute(
                new ToolExecutionContext(7L, "", "t1", 1L, "ps-1", Set.of("AI_CHAT"), Instant.now(), "tr"),
                objectMapper.readTree("{\"exactTerms\":[\"二分\"]}"));
        assertThat(result.status()).isEqualTo(ToolStatus.SUCCESS);
        @SuppressWarnings("unchecked")
        Map<String, Object> data = (Map<String, Object>) result.data();
        assertThat((List<?>) data.get("hits")).isEmpty();
    }

    @Test
    void blankTermsAreSchemaError() throws Exception {
        ToolResult<Object> result = tool.execute(context(), objectMapper.readTree("{\"exactTerms\":[]}"));
        assertThat(result.status()).isEqualTo(ToolStatus.SCHEMA_ERROR);
    }

    private ToolExecutionContext context() {
        return new ToolExecutionContext(7L, "c1", "t1", 1L, "ps-1", Set.of("AI_CHAT"), Instant.now(), "tr");
    }

    private AiMessageEntity message(Long id, String role, String content, LocalDateTime createdAt) {
        AiMessageEntity entity = new AiMessageEntity();
        entity.setId(id);
        entity.setConversationId("c1");
        entity.setRole(role);
        entity.setContent(content);
        entity.setCreatedAt(createdAt);
        return entity;
    }
}
