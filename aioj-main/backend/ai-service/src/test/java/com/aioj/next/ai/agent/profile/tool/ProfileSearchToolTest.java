package com.aioj.next.ai.agent.profile.tool;

import com.aioj.next.ai.agent.tool.ToolExecutionContext;
import com.aioj.next.ai.agent.tool.ToolRegistry;
import com.aioj.next.ai.agent.tool.ToolResult;
import com.aioj.next.ai.agent.tool.ToolStatus;
import com.aioj.next.ai.persistence.entity.AiLearningProfileEntity;
import com.aioj.next.ai.persistence.entity.AiProfileSignalEntity;
import com.aioj.next.ai.persistence.mapper.AiLearningProfileMapper;
import com.aioj.next.ai.persistence.mapper.AiProfileSignalMapper;
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
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

class ProfileSearchToolTest {

    private final AiLearningProfileMapper profileMapper = mock(AiLearningProfileMapper.class);
    private final AiProfileSignalMapper signalMapper = mock(AiProfileSignalMapper.class);
    private final ObjectMapper objectMapper = new ObjectMapper();
    private final ProfileSearchTool tool = new ProfileSearchTool(profileMapper, signalMapper, objectMapper);

    @Test
    void descriptorPassesRegistryRules() {
        // Name, schema subset, no identity fields — construction must satisfy the registry contract.
        new ToolRegistry(List.of(tool));
        assertThat(tool.descriptor().name()).isEqualTo("profile.search");
        assertThat(tool.descriptor().requiredScopes()).containsExactly("AI_CHAT");
        assertThat(tool.descriptor().readOnly()).isTrue();
        assertThat(tool.descriptor().idempotent()).isTrue();
        assertThat(tool.descriptor().riskLevel().name()).isEqualTo("LOW");
        assertThat(tool.descriptor().auditLevel().name()).isEqualTo("FULL");
    }

    @Test
    void searchFiltersActiveProfilesAndCallerUserId() throws Exception {
        when(profileMapper.selectList(any(QueryWrapper.class))).thenReturn(List.of(
                profile(11L, "weakness", "binary-search", "二分查找边界处理薄弱"),
                profile(12L, "mastery", "dp", "动态规划掌握良好")));
        when(signalMapper.selectList(any(QueryWrapper.class))).thenReturn(List.of());

        ToolResult<Object> result = tool.execute(context(), objectMapper.readTree(
                "{\"query\":\"二分\",\"category\":\"weakness\",\"topK\":5,\"includeSignals\":true}"));

        assertThat(result.status()).isEqualTo(ToolStatus.SUCCESS);
        @SuppressWarnings("unchecked")
        ArgumentCaptor<QueryWrapper<AiLearningProfileEntity>> captor =
                ArgumentCaptor.forClass(QueryWrapper.class);
        verify(profileMapper).selectList(captor.capture());
        QueryWrapper<AiLearningProfileEntity> wrapper = captor.getValue();
        String sql = wrapper.getSqlSegment();
        assertThat(sql).contains("user_id =").contains("state =").contains("category =")
                .contains("label LIKE").contains("deleted_at IS NULL").contains("disabled_at IS NULL");
        // Q4 frozen decision: only ACTIVE profiles are ever searched.
        assertThat(wrapper.getParamNameValuePairs().values()).contains(7L, "ACTIVE", "weakness");

        @SuppressWarnings("unchecked")
        Map<String, Object> data = (Map<String, Object>) result.data();
        @SuppressWarnings("unchecked")
        List<Map<String, Object>> profiles = (List<Map<String, Object>>) data.get("profiles");
        assertThat(data.get("profileCount")).isEqualTo(2);
        assertThat(data.get("query")).isEqualTo("二分");
        assertThat(data.get("category")).isEqualTo("weakness");
        assertThat(profiles.get(0).get("profileId")).isEqualTo("11");
        assertThat(profiles.get(0).get("label")).isEqualTo("二分查找边界处理薄弱");
        assertThat((List<?>) profiles.get(0).get("signals")).isEmpty();
        assertThat(result.sources()).hasSize(2);
        assertThat(result.sources().get(0).type()).isEqualTo("LEARNING_PROFILE");
        assertThat(result.trustLevel().name()).isEqualTo("DERIVED_SUMMARY");
        assertThat(result.classification().name()).isEqualTo("USER_PRIVATE");
    }

    @Test
    void searchEscapesLikeWildcards() throws Exception {
        when(profileMapper.selectList(any(QueryWrapper.class))).thenReturn(List.of());

        ToolResult<Object> result = tool.execute(context(), objectMapper.readTree(
                "{\"query\":\"100%_ok\"}"));

        assertThat(result.status()).isEqualTo(ToolStatus.SUCCESS);
        @SuppressWarnings("unchecked")
        ArgumentCaptor<QueryWrapper<AiLearningProfileEntity>> captor =
                ArgumentCaptor.forClass(QueryWrapper.class);
        verify(profileMapper).selectList(captor.capture());
        // Force segment generation before reading bound params (MyBatis-Plus is lazy here).
        assertThat(captor.getValue().getSqlSegment()).contains("label LIKE");
        assertThat(captor.getValue().getParamNameValuePairs().values()).contains("%100\\%\\_ok%");
    }

    @Test
    void includeSignalsFalseSkipsSignalMapper() throws Exception {
        when(profileMapper.selectList(any(QueryWrapper.class))).thenReturn(List.of(
                profile(11L, "weakness", "binary-search", "二分查找边界处理薄弱")));

        ToolResult<Object> result = tool.execute(context(), objectMapper.readTree(
                "{\"includeSignals\":false}"));

        assertThat(result.status()).isEqualTo(ToolStatus.SUCCESS);
        verifyNoInteractions(signalMapper);
        @SuppressWarnings("unchecked")
        Map<String, Object> data = (Map<String, Object>) result.data();
        @SuppressWarnings("unchecked")
        List<Map<String, Object>> profiles = (List<Map<String, Object>>) data.get("profiles");
        assertThat((List<?>) profiles.get(0).get("signals")).isEmpty();
        assertThat(result.sources()).hasSize(1);
    }

    @Test
    void signalsQueryFiltersPendingRejectedAndMatchesProfileKey() throws Exception {
        when(profileMapper.selectList(any(QueryWrapper.class))).thenReturn(List.of(
                profile(11L, "weakness", "binary-search", "二分查找边界处理薄弱")));
        when(signalMapper.selectList(any(QueryWrapper.class))).thenReturn(List.of(
                signal(21L, "binary-search", "{\"signal\":\"反复在二分右边界出错\"}")));

        ToolResult<Object> result = tool.execute(context(), objectMapper.readTree(
                "{\"includeSignals\":true,\"maxSignalsPerProfile\":2}"));

        assertThat(result.status()).isEqualTo(ToolStatus.SUCCESS);
        @SuppressWarnings("unchecked")
        ArgumentCaptor<QueryWrapper<AiProfileSignalEntity>> captor =
                ArgumentCaptor.forClass(QueryWrapper.class);
        verify(signalMapper).selectList(captor.capture());
        QueryWrapper<AiProfileSignalEntity> wrapper = captor.getValue();
        String sql = wrapper.getSqlSegment();
        // Q4 frozen decision: PENDING signals never reach the model.
        assertThat(sql).contains("user_id =").contains("status NOT IN").contains("knowledge_node =");
        assertThat(wrapper.getParamNameValuePairs().values())
                .contains(7L, "PENDING", "REJECTED", "binary-search");

        @SuppressWarnings("unchecked")
        Map<String, Object> data = (Map<String, Object>) result.data();
        @SuppressWarnings("unchecked")
        List<Map<String, Object>> profiles = (List<Map<String, Object>>) data.get("profiles");
        @SuppressWarnings("unchecked")
        List<Map<String, Object>> signals = (List<Map<String, Object>>) profiles.get(0).get("signals");
        assertThat(signals).hasSize(1);
        assertThat(signals.get(0).get("signalId")).isEqualTo("21");
        assertThat(signals.get(0).get("snippet")).isEqualTo("反复在二分右边界出错");
        assertThat(result.sources()).hasSize(2);
        assertThat(result.sources().get(1).type()).isEqualTo("PROFILE_SIGNAL");
    }

    @Test
    void snippetExtractionTruncatesAndFallsBackOnMalformedJson() throws Exception {
        String longSignal = "s".repeat(400);
        String longPayload = "{\"preamble\":\"" + "x".repeat(400) + "\"}";
        when(profileMapper.selectList(any(QueryWrapper.class))).thenReturn(List.of(
                profile(11L, "weakness", "binary-search", "二分查找边界处理薄弱")));
        when(signalMapper.selectList(any(QueryWrapper.class))).thenReturn(List.of(
                signal(21L, "binary-search", "{\"signal\":\"" + longSignal + "\"}"),
                signal(22L, "binary-search", "{not json"),
                signal(23L, "binary-search", longPayload)));

        ToolResult<Object> result = tool.execute(context(), objectMapper.readTree(
                "{\"maxSignalsPerProfile\":5}"));

        assertThat(result.status()).isEqualTo(ToolStatus.SUCCESS);
        @SuppressWarnings("unchecked")
        Map<String, Object> data = (Map<String, Object>) result.data();
        @SuppressWarnings("unchecked")
        List<Map<String, Object>> profiles = (List<Map<String, Object>>) data.get("profiles");
        @SuppressWarnings("unchecked")
        List<Map<String, Object>> signals = (List<Map<String, Object>>) profiles.get(0).get("signals");
        // signal field extracted and truncated to 300 chars.
        assertThat((String) signals.get(0).get("snippet")).hasSize(300).startsWith("sss");
        // Malformed JSON degrades to the raw payload, truncated.
        assertThat((String) signals.get(1).get("snippet")).isEqualTo("{not json");
        // Well-formed JSON without a textual signal field degrades to the raw payload, truncated.
        assertThat((String) signals.get(2).get("snippet")).hasSize(300).startsWith("{\"preamble\"");
    }

    @Test
    void emptyResultIsSuccessWithZeroProfiles() throws Exception {
        when(profileMapper.selectList(any(QueryWrapper.class))).thenReturn(List.of());

        ToolResult<Object> result = tool.execute(context(), objectMapper.readTree("{}"));

        assertThat(result.status()).isEqualTo(ToolStatus.SUCCESS);
        verifyNoInteractions(signalMapper);
        @SuppressWarnings("unchecked")
        Map<String, Object> data = (Map<String, Object>) result.data();
        assertThat(data.get("profileCount")).isEqualTo(0);
        assertThat((List<?>) data.get("profiles")).isEmpty();
        assertThat(result.sources()).isEmpty();
    }

    @Test
    void mapperExceptionBecomesExecutionError() throws Exception {
        when(profileMapper.selectList(any(QueryWrapper.class)))
                .thenThrow(new RuntimeException("db down"));

        ToolResult<Object> result = tool.execute(context(), objectMapper.readTree("{}"));

        assertThat(result.status()).isEqualTo(ToolStatus.EXECUTION_ERROR);
        assertThat(result.errorCode()).isEqualTo("PROFILE_SEARCH_FAILED");
    }

    private ToolExecutionContext context() {
        return new ToolExecutionContext(7L, "c1", "t1", 1L, "ps-1", Set.of("AI_CHAT"), Instant.now(), "tr");
    }

    private AiLearningProfileEntity profile(Long id, String category, String profileKey, String label) {
        AiLearningProfileEntity entity = new AiLearningProfileEntity();
        entity.id = id;
        entity.userId = 7L;
        entity.category = category;
        entity.profileKey = profileKey;
        entity.label = label;
        entity.state = "ACTIVE";
        entity.confidence = new BigDecimal("0.80");
        entity.evidenceCount = 2;
        entity.lastEvidenceAt = LocalDateTime.now();
        return entity;
    }

    private AiProfileSignalEntity signal(Long id, String knowledgeNode, String payloadJson) {
        AiProfileSignalEntity entity = new AiProfileSignalEntity();
        entity.setId(id);
        entity.setUserId(7L);
        entity.setSignalType("ERROR_PATTERN");
        entity.setKnowledgeNode(knowledgeNode);
        entity.setPolarity("NEGATIVE");
        entity.setScore(new BigDecimal("0.70"));
        entity.setPayloadJson(payloadJson);
        entity.setStatus("CONFIRMED");
        entity.setCreatedAt(LocalDateTime.now());
        return entity;
    }
}
