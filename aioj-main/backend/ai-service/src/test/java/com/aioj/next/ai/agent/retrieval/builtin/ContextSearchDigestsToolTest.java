package com.aioj.next.ai.agent.retrieval.builtin;

import com.aioj.next.ai.agent.tool.ToolExecutionContext;
import com.aioj.next.ai.agent.tool.ToolResult;
import com.aioj.next.ai.agent.tool.ToolStatus;
import com.aioj.next.ai.persistence.entity.AiTurnDigestEntity;
import com.aioj.next.ai.persistence.mapper.AiTurnDigestMapper;
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

class ContextSearchDigestsToolTest {

    private final AiTurnDigestMapper digestMapper = mock(AiTurnDigestMapper.class);
    private final com.aioj.next.ai.persistence.mapper.AiRetrievalChunkMapper chunkMapper =
            mock(com.aioj.next.ai.persistence.mapper.AiRetrievalChunkMapper.class);
    private final com.aioj.next.ai.domain.OpenAiCompatibleProvider provider =
            mock(com.aioj.next.ai.domain.OpenAiCompatibleProvider.class);
    private final ContextSearchDigestsTool tool = new ContextSearchDigestsTool(
            digestMapper, chunkMapper, provider, new ObjectMapper());

    @Test
    void keywordSearchRanksAndReturnsDigestHitsWithoutRawContent() {
        when(digestMapper.selectLatestForConversation(7L, "c-1", 200)).thenReturn(List.of(
                digest(1L, "t-1", "用户粘贴了一道区间异或题", "区间查询 贪心", 10),
                digest(2L, "t-2", "用户问了滑动窗口复杂度", "滑动窗口 复杂度", 9),
                digest(3L, "t-3", "用户继续问异或题的前缀和做法", "异或 前缀和", 8)
        ));

        ToolResult<Object> result = tool.execute(context(), readTree(
                "{\"query\":\"之前那道异或题的前缀和\",\"searchMode\":\"KEYWORD\",\"topK\":5}"));

        assertThat(result.status()).isEqualTo(ToolStatus.SUCCESS);
        Map<String, Object> data = data(result);
        List<Map<String, Object>> hits = hits(data);
        assertThat(hits).hasSize(2);
        // t-3 matches 异或+前缀和 (2 terms) ranks above t-1 partial matches ordered by tie-break
        assertThat(hits.get(0).get("hitId")).isEqualTo("dg-3");
        for (Map<String, Object> hit : hits) {
            assertThat(hit.get("requiresFetch")).isEqualTo(true);
            assertThat(hit.containsKey("content")).isFalse();
            assertThat(hit.get("summary")).isNotNull();
        }
        assertThat(result.sources()).extracting("type").containsOnly("TURN_DIGEST");
    }

    @Test
    void semanticModeDegradesWithWarning() {
        when(digestMapper.selectLatestForConversation(7L, "c-1", 200))
                .thenReturn(List.of(digest(1L, "t-1", "异或题", "异或", 10)));

        ToolResult<Object> result = tool.execute(context(), readTree(
                "{\"query\":\"异或\",\"searchMode\":\"HYBRID\"}"));

        Map<String, Object> data = data(result);
        assertThat((List<String>) data.get("warnings")).anyMatch(w -> w.contains("SEMANTIC_UNAVAILABLE"));
        assertThat(hits(data)).hasSize(1);
    }

    @Test
    void earliestTimeHintOrdersOldestFirst() {
        // Input arrives newest-first (DB order); EARLIEST must re-sort to oldest first.
        when(digestMapper.selectLatestForConversation(7L, "c-1", 200)).thenReturn(List.of(
                digest(2L, "t-2", "异或 前缀和", "异或 前缀和", 8),
                digest(1L, "t-1", "异或 前缀和", "异或 前缀和", 10)
        ));

        ToolResult<Object> result = tool.execute(context(), readTree(
                "{\"query\":\"异或 前缀和\",\"timeHint\":\"EARLIEST\"}"));

        List<Map<String, Object>> hits = hits(data(result));
        assertThat(hits.get(0).get("turnId")).isEqualTo("t-1");
    }

    @Test
    void entityTypeFilterDropsNonMatchingHits() {
        when(digestMapper.selectLatestForConversation(7L, "c-1", 200)).thenReturn(List.of(
                digestWithEntities(1L, "t-1", "异或 前缀和", "[{\"type\":\"PROBLEM\",\"canonicalName\":\"区间异或\"}]"),
                digestWithEntities(2L, "t-2", "异或 前缀和", "[{\"type\":\"ALGORITHM_TOPIC\",\"canonicalName\":\"xor\"}]")
        ));

        ToolResult<Object> result = tool.execute(context(), readTree(
                "{\"query\":\"异或\",\"entityTypes\":[\"PROBLEM\"]}"));

        List<Map<String, Object>> hits = hits(data(result));
        assertThat(hits).hasSize(1);
        assertThat(hits.get(0).get("turnId")).isEqualTo("t-1");
    }

    @Test
    void emptyQueryIsSchemaError() {
        ToolResult<Object> result = tool.execute(context(), readTree("{\"query\":\"  \"}"));
        assertThat(result.status()).isEqualTo(ToolStatus.SCHEMA_ERROR);
    }

    @Test
    void allConversationsScopeLoadsUserWideCandidates() {
        // P4-1: scope=ALL_MY_CONVERSATIONS searches across the user's conversations.
        AiTurnDigestEntity otherConv = digest(9L, "t-9", "另一个会话里的异或题", "异或 前缀和", 3);
        otherConv.setConversationId("c-other");
        when(digestMapper.selectLatestForUser(7L, 400)).thenReturn(List.of(
                otherConv,
                digest(1L, "t-1", "当前会话的异或讨论", "异或", 10)
        ));

        ToolResult<Object> result = tool.execute(context(), readTree(
                "{\"query\":\"异或\",\"scope\":\"ALL_MY_CONVERSATIONS\",\"topK\":5}"));

        Map<String, Object> data = data(result);
        assertThat(data.get("scope")).isEqualTo("ALL_MY_CONVERSATIONS");
        List<Map<String, Object>> hits = hits(data);
        assertThat(hits).hasSize(2);
        // Hits carry the origin conversation so the model can cite where it came from.
        assertThat(hits).extracting(hit -> hit.get("conversationId"))
                .containsExactlyInAnyOrder("c-other", "c-1");
        // Other conversation's hit ranks first (matches 异或+前缀和, 2 terms vs 1).
        assertThat(hits.get(0).get("conversationId")).isEqualTo("c-other");
    }

    @Test
    void defaultScopeStaysCurrentConversation() {
        when(digestMapper.selectLatestForConversation(7L, "c-1", 200)).thenReturn(List.of(
                digest(1L, "t-1", "异或题", "异或", 10)
        ));

        ToolResult<Object> result = tool.execute(context(), readTree("{\"query\":\"异或\"}"));

        Map<String, Object> data = data(result);
        assertThat(data.get("scope")).isEqualTo("CURRENT_CONVERSATION");
        assertThat(hits(data)).hasSize(1);
        org.mockito.Mockito.verify(digestMapper, org.mockito.Mockito.never())
                .selectLatestForUser(any(), org.mockito.ArgumentMatchers.anyInt());
    }

    @Test
    void cjkQueriesMatchViaBigramDecomposition() {
        List<String> terms = ContextSearchDigestsTool.queryTerms("第二道异或题");
        assertThat(terms).anyMatch(term -> term.contains("异或"));
        assertThat(terms.size()).isGreaterThan(2);
    }

    @Test
    void semanticModeRanksByCosineAndSurfacesLexicalMisses() {
        // Digest 1: no lexical match for "异或" but a near-parallel embedding; digest 2:
        // lexical hit with an almost orthogonal embedding (below the similarity floor).
        when(digestMapper.selectLatestForConversation(7L, "c-1", 200)).thenReturn(List.of(
                digest(1L, "t-1", "位运算技巧讨论", "位运算", 10),
                digest(2L, "t-2", "异或 前缀和", "异或 前缀和", 9)
        ));
        when(provider.embed("异或")).thenReturn(java.util.Optional.of(List.of(1.0, 0.0)));
        when(chunkMapper.selectList(any(com.baomidou.mybatisplus.core.conditions.query.QueryWrapper.class)))
                .thenReturn(List.of(
                        chunk("1", "[0.9,0.1]"),
                        chunk("2", "[0.1,0.9]")));

        ToolResult<Object> result = tool.execute(context(), readTree(
                "{\"query\":\"异或\",\"searchMode\":\"SEMANTIC\",\"topK\":5}"));

        Map<String, Object> data = data(result);
        assertThat(data.containsKey("warnings")).isFalse();
        List<Map<String, Object>> hits = hits(data);
        assertThat(hits).hasSize(1);
        assertThat(hits.get(0).get("turnId")).isEqualTo("t-1");
        Map<String, Object> breakdown = (Map<String, Object>) hits.get(0).get("scoreBreakdown");
        assertThat((Double) breakdown.get("semantic")).isGreaterThan(0.9);
        assertThat((Double) breakdown.get("lexical")).isEqualTo(0.0);
    }

    @Test
    void hybridModeFusesLexicalAndSemanticLanes() {
        when(digestMapper.selectLatestForConversation(7L, "c-1", 200)).thenReturn(List.of(
                digest(1L, "t-1", "位运算技巧讨论", "位运算", 10),
                digest(2L, "t-2", "异或 前缀和", "异或 前缀和", 9)
        ));
        when(provider.embed("异或")).thenReturn(java.util.Optional.of(List.of(1.0, 0.0)));
        when(chunkMapper.selectList(any(com.baomidou.mybatisplus.core.conditions.query.QueryWrapper.class)))
                .thenReturn(List.of(
                        chunk("1", "[0.9,0.1]"),
                        chunk("2", "[0.1,0.9]")));

        ToolResult<Object> result = tool.execute(context(), readTree(
                "{\"query\":\"异或\",\"searchMode\":\"HYBRID\",\"topK\":5}"));

        List<Map<String, Object>> hits = hits(data(result));
        // Semantic-only hit (t-1) and lexical-only hit (t-2) both surface via RRF union.
        assertThat(hits).extracting(hit -> hit.get("turnId")).containsExactlyInAnyOrder("t-1", "t-2");
        Map<String, Map<String, Object>> byTurn = new java.util.HashMap<>();
        for (Map<String, Object> hit : hits) {
            byTurn.put((String) hit.get("turnId"), (Map<String, Object>) hit.get("scoreBreakdown"));
        }
        assertThat((Double) byTurn.get("t-1").get("semantic")).isGreaterThan(0.9);
        assertThat((Double) byTurn.get("t-2").get("lexical")).isGreaterThan(0.0);
    }

    @Test
    void semanticRequestedButNoEmbeddingsWarnsAndDegrades() {
        when(digestMapper.selectLatestForConversation(7L, "c-1", 200))
                .thenReturn(List.of(digest(1L, "t-1", "异或题", "异或", 10)));
        when(provider.embed("异或")).thenReturn(java.util.Optional.of(List.of(1.0, 0.0)));
        when(chunkMapper.selectList(any(com.baomidou.mybatisplus.core.conditions.query.QueryWrapper.class)))
                .thenReturn(List.of());

        ToolResult<Object> result = tool.execute(context(), readTree(
                "{\"query\":\"异或\",\"searchMode\":\"SEMANTIC\"}"));

        Map<String, Object> data = data(result);
        assertThat((List<String>) data.get("warnings")).anyMatch(w -> w.contains("NO_DIGEST_EMBEDDINGS"));
    }

    @Test
    void cosineRejectsDimensionMismatch() {
        assertThat(ContextSearchDigestsTool.cosine(List.of(1.0), List.of(1.0, 0.0))).isNaN();
        assertThat(ContextSearchDigestsTool.cosine(List.of(1.0, 0.0), List.of(1.0, 0.0))).isEqualTo(1.0);
    }

    private com.aioj.next.ai.persistence.entity.AiRetrievalChunkEntity chunk(String ownerId, String embeddingJson) {
        com.aioj.next.ai.persistence.entity.AiRetrievalChunkEntity chunk =
                new com.aioj.next.ai.persistence.entity.AiRetrievalChunkEntity();
        chunk.setUserId(7L);
        chunk.setOwnerType("TURN_DIGEST");
        chunk.setOwnerId(ownerId);
        chunk.setEmbeddingJson(embeddingJson);
        return chunk;
    }

    private ToolExecutionContext context() {
        return new ToolExecutionContext(7L, "c-1", "t-current", 5L, null, Set.of("AI_CHAT"), Instant.now(), "trace");
    }

    private com.fasterxml.jackson.databind.JsonNode readTree(String json) {
        try {
            return new ObjectMapper().readTree(json);
        } catch (Exception ex) {
            throw new IllegalStateException(ex);
        }
    }

    @SuppressWarnings("unchecked")
    private Map<String, Object> data(ToolResult<Object> result) {
        return (Map<String, Object>) result.data();
    }

    @SuppressWarnings("unchecked")
    private List<Map<String, Object>> hits(Map<String, Object> data) {
        return (List<Map<String, Object>>) data.get("hits");
    }

    private AiTurnDigestEntity digest(Long id, String turnId, String summary, String searchText, int hoursAgo) {
        AiTurnDigestEntity digest = new AiTurnDigestEntity();
        digest.setId(id);
        digest.setTurnId(turnId);
        digest.setConversationId("c-1");
        digest.setUserId(7L);
        digest.setSummary(summary);
        digest.setSearchText(searchText);
        digest.setStructuredDigest("{\"searchKeywords\":[\"异或\"],\"entities\":[{\"type\":\"PROBLEM\",\"canonicalName\":\"区间异或\"}]}");
        digest.setStatus("READY");
        digest.setCreatedAt(LocalDateTime.now().minusHours(hoursAgo));
        return digest;
    }

    private AiTurnDigestEntity digestWithEntities(Long id, String turnId, String searchText, String entitiesJson) {
        AiTurnDigestEntity digest = digest(id, turnId, "summary " + searchText, searchText, 5);
        digest.setStructuredDigest("{\"entities\":" + entitiesJson + "}");
        return digest;
    }
}
