package com.aioj.next.ai.agent.memory.tool;

import com.aioj.next.ai.agent.tool.ToolExecutionContext;
import com.aioj.next.ai.agent.tool.ToolRegistry;
import com.aioj.next.ai.agent.tool.ToolResult;
import com.aioj.next.ai.agent.tool.ToolStatus;
import com.aioj.next.ai.persistence.entity.AiMemoryClaimEntity;
import com.aioj.next.ai.persistence.mapper.AiMemoryClaimMapper;
import com.baomidou.mybatisplus.core.conditions.query.QueryWrapper;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class MemorySearchClaimsToolTest {

    private final AiMemoryClaimMapper claimMapper = mock(AiMemoryClaimMapper.class);
    private final ObjectMapper objectMapper = new ObjectMapper();
    private final MemorySearchClaimsTool tool = new MemorySearchClaimsTool(claimMapper, objectMapper);

    @Test
    void descriptorPassesRegistryRules() {
        // Name, schema subset, no identity fields — construction must satisfy the registry contract.
        new ToolRegistry(List.of(tool));
        assertThat(tool.descriptor().name()).isEqualTo("memory.search_claims");
        assertThat(tool.descriptor().requiredScopes()).containsExactly("AI_CHAT");
        assertThat(tool.descriptor().readOnly()).isTrue();
        assertThat(tool.descriptor().idempotent()).isTrue();
    }

    @Test
    void searchFiltersActiveStatusAndCallerUserId() throws Exception {
        when(claimMapper.selectList(any(QueryWrapper.class))).thenReturn(List.of(
                claim(11L, "PREFERENCE", "likes terse hints"), claim(12L, "GOAL", "wants ICPC gold")));

        ToolResult<Object> result = tool.execute(context(), objectMapper.readTree(
                "{\"query\":\"二分\",\"category\":\"PREFERENCE\",\"topK\":5}"));

        assertThat(result.status()).isEqualTo(ToolStatus.SUCCESS);
        @SuppressWarnings("unchecked")
        ArgumentCaptor<QueryWrapper<AiMemoryClaimEntity>> captor =
                ArgumentCaptor.forClass(QueryWrapper.class);
        verify(claimMapper).selectList(captor.capture());
        QueryWrapper<AiMemoryClaimEntity> wrapper = captor.getValue();
        String sql = wrapper.getSqlSegment();
        assertThat(sql).contains("user_id =").contains("status =").contains("category =")
                .contains("canonical_text LIKE");
        // Q4 frozen decision: only ACTIVE claims are ever searched.
        assertThat(wrapper.getParamNameValuePairs().values()).contains(7L, "ACTIVE", "PREFERENCE");

        @SuppressWarnings("unchecked")
        Map<String, Object> data = (Map<String, Object>) result.data();
        @SuppressWarnings("unchecked")
        List<Map<String, Object>> claims = (List<Map<String, Object>>) data.get("claims");
        assertThat(data.get("claimCount")).isEqualTo(2);
        assertThat(data.get("query")).isEqualTo("二分");
        assertThat(data.get("category")).isEqualTo("PREFERENCE");
        assertThat(claims.get(0).get("claimId")).isEqualTo("11");
        assertThat(claims.get(0).get("canonicalText")).isEqualTo("likes terse hints");
        assertThat(result.sources()).hasSize(2);
        assertThat(result.sources().get(0).type()).isEqualTo("MEMORY_CLAIM");
        assertThat(result.trustLevel().name()).isEqualTo("DERIVED_SUMMARY");
        assertThat(result.classification().name()).isEqualTo("USER_PRIVATE");
    }

    @Test
    void searchEscapesLikeWildcardsAndTruncatesTopK() throws Exception {
        when(claimMapper.selectList(any(QueryWrapper.class))).thenReturn(List.of(
                claim(1L, "RULE", "a"), claim(2L, "RULE", "b"), claim(3L, "RULE", "c")));

        ToolResult<Object> result = tool.execute(context(), objectMapper.readTree(
                "{\"query\":\"100%_ok\",\"topK\":2}"));

        assertThat(result.status()).isEqualTo(ToolStatus.SUCCESS);
        @SuppressWarnings("unchecked")
        ArgumentCaptor<QueryWrapper<AiMemoryClaimEntity>> captor =
                ArgumentCaptor.forClass(QueryWrapper.class);
        verify(claimMapper).selectList(captor.capture());
        // Force segment generation before reading bound params (MyBatis-Plus is lazy here).
        assertThat(captor.getValue().getSqlSegment()).contains("canonical_text LIKE");
        assertThat(captor.getValue().getParamNameValuePairs().values()).contains("%100\\%\\_ok%");
        @SuppressWarnings("unchecked")
        Map<String, Object> data = (Map<String, Object>) result.data();
        assertThat(data.get("claimCount")).isEqualTo(2);
    }

    @Test
    void emptyResultIsSuccessWithZeroClaims() throws Exception {
        when(claimMapper.selectList(any(QueryWrapper.class))).thenReturn(List.of());

        ToolResult<Object> result = tool.execute(context(), objectMapper.readTree("{}"));

        assertThat(result.status()).isEqualTo(ToolStatus.SUCCESS);
        @SuppressWarnings("unchecked")
        Map<String, Object> data = (Map<String, Object>) result.data();
        assertThat(data.get("claimCount")).isEqualTo(0);
        assertThat((List<?>) data.get("claims")).isEmpty();
        assertThat(result.sources()).isEmpty();
    }

    @Test
    void invalidCategoryIsSchemaError() throws Exception {
        ToolResult<Object> result = tool.execute(context(), objectMapper.readTree(
                "{\"category\":\"SECRET\"}"));
        assertThat(result.status()).isEqualTo(ToolStatus.SCHEMA_ERROR);
        assertThat(result.errorCode()).isEqualTo("INVALID_CATEGORY");
    }

    @Test
    void mapperExceptionBecomesExecutionError() throws Exception {
        when(claimMapper.selectList(any(QueryWrapper.class)))
                .thenThrow(new RuntimeException("db down"));

        ToolResult<Object> result = tool.execute(context(), objectMapper.readTree("{}"));

        assertThat(result.status()).isEqualTo(ToolStatus.EXECUTION_ERROR);
        assertThat(result.errorCode()).isEqualTo("SEARCH_QUERY_FAILED");
    }

    private ToolExecutionContext context() {
        return new ToolExecutionContext(7L, "c1", "t1", 1L, "ps-1", Set.of("AI_CHAT"), Instant.now(), "tr");
    }

    private AiMemoryClaimEntity claim(Long id, String category, String canonicalText) {
        AiMemoryClaimEntity entity = new AiMemoryClaimEntity();
        entity.id = id;
        entity.userId = 7L;
        entity.category = category;
        entity.memoryKey = "key-" + id;
        entity.canonicalText = canonicalText;
        entity.confidence = new BigDecimal("0.80");
        entity.status = "ACTIVE";
        entity.supportCount = 2;
        entity.pinned = false;
        entity.lastConfirmedAt = LocalDateTime.now();
        return entity;
    }
}
