package com.aioj.next.ai.agent.memory.tool;

import com.aioj.next.ai.agent.tool.ToolExecutionContext;
import com.aioj.next.ai.agent.tool.ToolRegistry;
import com.aioj.next.ai.agent.tool.ToolResult;
import com.aioj.next.ai.agent.tool.ToolStatus;
import com.aioj.next.ai.persistence.entity.AiMemoryClaimEntity;
import com.aioj.next.ai.persistence.entity.AiMemoryEvidenceEntity;
import com.aioj.next.ai.persistence.mapper.AiMemoryClaimMapper;
import com.aioj.next.ai.persistence.mapper.AiMemoryEvidenceMapper;
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

class MemoryFetchEvidenceToolTest {

    private final AiMemoryClaimMapper claimMapper = mock(AiMemoryClaimMapper.class);
    private final AiMemoryEvidenceMapper evidenceMapper = mock(AiMemoryEvidenceMapper.class);
    private final ObjectMapper objectMapper = new ObjectMapper();
    private final MemoryFetchEvidenceTool tool =
            new MemoryFetchEvidenceTool(claimMapper, evidenceMapper, objectMapper);

    @Test
    void descriptorPassesRegistryRules() {
        // Name, schema subset, no identity fields — construction must satisfy the registry contract.
        new ToolRegistry(List.of(tool));
        assertThat(tool.descriptor().name()).isEqualTo("memory.fetch_evidence");
        assertThat(tool.descriptor().requiredScopes()).containsExactly("AI_CHAT");
        assertThat(tool.descriptor().readOnly()).isTrue();
    }

    @Test
    void claimOfAnotherUserIsNotFound() throws Exception {
        // Mapper returns null because the query is scoped by id AND caller user_id.
        when(claimMapper.selectOne(any(QueryWrapper.class))).thenReturn(null);

        ToolResult<Object> result = tool.execute(context(), objectMapper.readTree("{\"claimId\":99}"));

        assertThat(result.status()).isEqualTo(ToolStatus.NOT_FOUND);
        assertThat(result.errorCode()).isEqualTo("CLAIM_NOT_FOUND");
        @SuppressWarnings("unchecked")
        ArgumentCaptor<QueryWrapper<AiMemoryClaimEntity>> captor =
                ArgumentCaptor.forClass(QueryWrapper.class);
        verify(claimMapper).selectOne(captor.capture());
        assertThat(captor.getValue().getSqlSegment()).contains("id =").contains("user_id =");
        assertThat(captor.getValue().getParamNameValuePairs().values()).contains(99L, 7L);
    }

    @Test
    void returnsEvidenceTruncatedAt500Chars() throws Exception {
        when(claimMapper.selectOne(any(QueryWrapper.class))).thenReturn(claim(5L, "ACTIVE"));
        String longText = "a".repeat(600);
        when(evidenceMapper.selectList(any(QueryWrapper.class))).thenReturn(List.of(
                evidence(21L, longText), evidence(22L, "short quote")));

        ToolResult<Object> result = tool.execute(context(), objectMapper.readTree(
                "{\"claimId\":5,\"maxEvidence\":2}"));

        assertThat(result.status()).isEqualTo(ToolStatus.SUCCESS);
        @SuppressWarnings("unchecked")
        ArgumentCaptor<QueryWrapper<AiMemoryEvidenceEntity>> captor =
                ArgumentCaptor.forClass(QueryWrapper.class);
        verify(evidenceMapper).selectList(captor.capture());
        // Evidence query is double-scoped by claim_id and caller user_id.
        assertThat(captor.getValue().getSqlSegment()).contains("claim_id =").contains("user_id =");
        assertThat(captor.getValue().getParamNameValuePairs().values()).contains(5L, 7L);

        @SuppressWarnings("unchecked")
        Map<String, Object> data = (Map<String, Object>) result.data();
        @SuppressWarnings("unchecked")
        List<Map<String, Object>> evidence = (List<Map<String, Object>>) data.get("evidence");
        assertThat(data.get("evidenceCount")).isEqualTo(2);
        assertThat(evidence.get(0).get("evidenceText").toString()).hasSize(501); // 500 + ellipsis
        assertThat(evidence.get(1).get("evidenceText")).isEqualTo("short quote");
        @SuppressWarnings("unchecked")
        Map<String, Object> claimSummary = (Map<String, Object>) data.get("claim");
        assertThat(claimSummary.get("claimId")).isEqualTo("5");
        assertThat(claimSummary.get("status")).isEqualTo("ACTIVE");
        assertThat(data).doesNotContainKey("warning");
        assertThat(result.sources()).hasSize(2);
        assertThat(result.sources().get(0).type()).isEqualTo("MEMORY_EVIDENCE");
        assertThat(result.trustLevel().name()).isEqualTo("USER_PROVIDED");
        assertThat(result.classification().name()).isEqualTo("USER_PRIVATE");
    }

    @Test
    void disabledClaimReturnsWarningButStillReturnsData() throws Exception {
        when(claimMapper.selectOne(any(QueryWrapper.class))).thenReturn(claim(5L, "DISABLED"));
        when(evidenceMapper.selectList(any(QueryWrapper.class))).thenReturn(List.of(evidence(21L, "old quote")));

        ToolResult<Object> result = tool.execute(context(), objectMapper.readTree("{\"claimId\":5}"));

        assertThat(result.status()).isEqualTo(ToolStatus.SUCCESS);
        @SuppressWarnings("unchecked")
        Map<String, Object> data = (Map<String, Object>) result.data();
        assertThat(data.get("warning")).isEqualTo("claim_not_active");
        @SuppressWarnings("unchecked")
        Map<String, Object> claimSummary = (Map<String, Object>) data.get("claim");
        assertThat(claimSummary.get("status")).isEqualTo("DISABLED");
        assertThat(result.warnings()).containsExactly("claim_not_active");
        assertThat(result.truncated()).isFalse();
    }

    @Test
    void missingClaimIdIsSchemaError() throws Exception {
        ToolResult<Object> result = tool.execute(context(), objectMapper.readTree("{}"));
        assertThat(result.status()).isEqualTo(ToolStatus.SCHEMA_ERROR);
        assertThat(result.errorCode()).isEqualTo("MISSING_CLAIM_ID");
    }

    @Test
    void mapperExceptionBecomesExecutionError() throws Exception {
        when(claimMapper.selectOne(any(QueryWrapper.class))).thenThrow(new RuntimeException("db down"));

        ToolResult<Object> result = tool.execute(context(), objectMapper.readTree("{\"claimId\":5}"));

        assertThat(result.status()).isEqualTo(ToolStatus.EXECUTION_ERROR);
        assertThat(result.errorCode()).isEqualTo("EVIDENCE_QUERY_FAILED");
    }

    private ToolExecutionContext context() {
        return new ToolExecutionContext(7L, "c1", "t1", 1L, "ps-1", Set.of("AI_CHAT"), Instant.now(), "tr");
    }

    private AiMemoryClaimEntity claim(Long id, String status) {
        AiMemoryClaimEntity entity = new AiMemoryClaimEntity();
        entity.id = id;
        entity.userId = 7L;
        entity.category = "PREFERENCE";
        entity.memoryKey = "key-" + id;
        entity.canonicalText = "some memory";
        entity.status = status;
        return entity;
    }

    private AiMemoryEvidenceEntity evidence(Long id, String text) {
        AiMemoryEvidenceEntity entity = new AiMemoryEvidenceEntity();
        entity.id = id;
        entity.userId = 7L;
        entity.claimId = 5L;
        entity.conversationId = "c1";
        entity.messageId = 42L;
        entity.evidenceType = "USER_STATEMENT";
        entity.evidenceText = text;
        entity.confidence = new BigDecimal("0.90");
        entity.createdAt = LocalDateTime.now();
        return entity;
    }
}
