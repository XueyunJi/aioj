package com.aioj.next.ai.agent.retrieval.builtin;

import com.aioj.next.ai.agent.context.DataClassification;
import com.aioj.next.ai.agent.context.TrustLevel;
import com.aioj.next.ai.agent.digest.TurnDigestEmbedHandler;
import com.aioj.next.ai.agent.tool.AgentTool;
import com.aioj.next.ai.agent.tool.SourceRef;
import com.aioj.next.ai.agent.tool.ToolAuditLevel;
import com.aioj.next.ai.agent.tool.ToolDescriptor;
import com.aioj.next.ai.agent.tool.ToolExecutionContext;
import com.aioj.next.ai.agent.tool.ToolResult;
import com.aioj.next.ai.agent.tool.ToolRiskLevel;
import com.aioj.next.ai.agent.tool.ToolStatus;
import com.aioj.next.ai.domain.OpenAiCompatibleProvider;
import com.aioj.next.ai.persistence.entity.AiRetrievalChunkEntity;
import com.aioj.next.ai.persistence.entity.AiTurnDigestEntity;
import com.aioj.next.ai.persistence.mapper.AiRetrievalChunkMapper;
import com.aioj.next.ai.persistence.mapper.AiTurnDigestMapper;
import com.baomidou.mybatisplus.core.conditions.query.QueryWrapper;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.springframework.stereotype.Component;

import java.time.Duration;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

/**
 * Built-in retrieval tool {@code context.search_digests} (design doc §4.2/§6.4 第二层):
 * locates relevant turns by searching the per-turn digest plane — never returns raw
 * message text ({@code requiresFetch=true}); callers must follow up with
 * {@code context.fetch_sources} for evidence (定位与取证分离). KEYWORD matching always
 * works; SEMANTIC ranks by digest-embedding cosine, HYBRID fuses both lanes with
 * weighted RRF. When the embedding lane is unavailable the semantic modes degrade to
 * KEYWORD with an explicit warning.
 */
@Component
public class ContextSearchDigestsTool implements AgentTool {

    public static final String HIT_ID_PREFIX = "dg-";

    private static final int DEFAULT_TOP_K = 8;
    private static final int MAX_TOP_K = 10;
    private static final int SCAN_LIMIT = 200;
    /** Candidate cap for ALL_MY_CONVERSATIONS scope (design doc §10 P4-1). */
    private static final int USER_SCAN_LIMIT = 400;
    private static final int MAX_QUERY_CHARS = 200;
    /** Minimum cosine similarity for a semantic-lane hit; below this is noise. */
    private static final double MIN_SEMANTIC_SIMILARITY = 0.2;
    /** Reciprocal-rank-fusion constant for HYBRID. */
    private static final int RRF_K = 60;

    private final AiTurnDigestMapper digestMapper;
    private final AiRetrievalChunkMapper chunkMapper;
    private final OpenAiCompatibleProvider provider;
    private final ObjectMapper objectMapper;
    private final ToolDescriptor descriptor;

    public ContextSearchDigestsTool(AiTurnDigestMapper digestMapper, AiRetrievalChunkMapper chunkMapper,
                                    OpenAiCompatibleProvider provider, ObjectMapper objectMapper) {
        this.digestMapper = digestMapper;
        this.chunkMapper = chunkMapper;
        this.provider = provider;
        this.objectMapper = objectMapper;
        this.descriptor = new ToolDescriptor(
                "context.search_digests",
                "1.0.0",
                "Search per-turn digests (summaries/keywords/entities) to LOCATE relevant earlier turns — "
                        + "including ones far back in history. Use when the user refers to something discussed "
                        + "before (\"之前/上次/最开始/那道…题/那个错误\"). Default scope is the current conversation; "
                        + "when the user refers to another/earlier conversation (\"另一个会话/上次聊天/我们之前聊过\"), "
                        + "retry with scope=ALL_MY_CONVERSATIONS (your own conversations only). Returns digest hits "
                        + "only, never raw message text; call context.fetch_sources with the hitIds to read the "
                        + "actual messages. Do NOT answer from summaries alone.",
                buildSchema(),
                ToolRiskLevel.LOW,
                true,
                true,
                Set.of("AI_CHAT"),
                Set.of(DataClassification.USER_PRIVATE),
                3000,
                Duration.ofSeconds(8),
                ToolAuditLevel.FULL
        );
    }

    @Override
    public ToolDescriptor descriptor() {
        return descriptor;
    }

    @Override
    public ToolResult<Object> execute(ToolExecutionContext context, JsonNode input) {
        String query = input.path("query").asText("").trim();
        if (query.isEmpty()) {
            return ToolResult.failure(null, ToolStatus.SCHEMA_ERROR, "EMPTY_QUERY",
                    "query must be a non-blank string");
        }
        if (query.length() > MAX_QUERY_CHARS) {
            query = query.substring(0, MAX_QUERY_CHARS);
        }
        int topK = input.path("topK").isIntegralNumber()
                ? Math.min(MAX_TOP_K, Math.max(1, input.path("topK").asInt()))
                : DEFAULT_TOP_K;
        String timeHint = input.path("timeHint").asText("ANY");
        String searchMode = input.path("searchMode").asText("KEYWORD");
        boolean allConversations = "ALL_MY_CONVERSATIONS".equalsIgnoreCase(input.path("scope").asText(""));
        List<String> warnings = new ArrayList<>();
        boolean semanticRequested = "SEMANTIC".equalsIgnoreCase(searchMode) || "HYBRID".equalsIgnoreCase(searchMode);
        if (!allConversations && (context.conversationId() == null || context.conversationId().isBlank())) {
            return success(List.of(), topK, warnings, "CURRENT_CONVERSATION");
        }
        List<AiTurnDigestEntity> candidates;
        try {
            candidates = allConversations
                    ? digestMapper.selectLatestForUser(context.userId(), USER_SCAN_LIMIT)
                    : digestMapper.selectLatestForConversation(context.userId(), context.conversationId(), SCAN_LIMIT);
        } catch (RuntimeException ex) {
            return ToolResult.failure(null, ToolStatus.EXECUTION_ERROR, "DIGEST_SEARCH_FAILED",
                    "digest search failed");
        }
        List<String> terms = queryTerms(query);
        Set<String> entityTypeFilter = new LinkedHashSet<>();
        for (JsonNode type : input.path("entityTypes")) {
            if (type.isTextual() && !type.asText().isBlank()) {
                entityTypeFilter.add(type.asText());
            }
        }
        Map<Long, Double> semanticScores = Map.of();
        if (semanticRequested) {
            Optional<List<Double>> queryVector = provider.embed(query);
            if (queryVector.isPresent()) {
                semanticScores = loadSemanticScores(context.userId(), candidates, queryVector.get());
                if (semanticScores.isEmpty()) {
                    warnings.add("NO_DIGEST_EMBEDDINGS: no digest embeddings for this scope yet; "
                            + "degraded to KEYWORD matching");
                }
            } else {
                warnings.add("SEMANTIC_UNAVAILABLE: embedding lane not active; degraded to KEYWORD matching");
            }
        }
        List<ScoredHit> scored = new ArrayList<>();
        for (AiTurnDigestEntity digest : candidates) {
            String haystack = ((digest.getSummary() == null ? "" : digest.getSummary()) + " "
                    + (digest.getSearchText() == null ? "" : digest.getSearchText()))
                    .toLowerCase(Locale.ROOT);
            int matched = 0;
            for (String term : terms) {
                if (haystack.contains(term.toLowerCase(Locale.ROOT))) {
                    matched++;
                }
            }
            if (!entityTypeFilter.isEmpty() && !matchesEntityType(digest.getStructuredDigest(), entityTypeFilter)) {
                continue;
            }
            Double similarity = semanticScores.get(digest.getId());
            scored.add(new ScoredHit(digest, matched, similarity == null ? 0.0 : similarity, similarity != null));
        }
        List<ScoredHit> ranked = rank(scored, searchMode, !semanticScores.isEmpty(), timeHint);
        int maxMatched = scored.stream().mapToInt(hit -> hit.matchedTerms).max().orElse(1);
        List<Map<String, Object>> hits = new ArrayList<>();
        for (ScoredHit hit : ranked) {
            if (hits.size() >= topK) {
                break;
            }
            hits.add(toHit(hit, maxMatched));
        }
        return success(hits, topK, warnings, allConversations ? "ALL_MY_CONVERSATIONS" : "CURRENT_CONVERSATION");
    }

    /**
     * Ranking per search mode. KEYWORD (and every degraded path) keeps the original
     * lexical order; SEMANTIC ranks by cosine; HYBRID fuses both rankings with
     * weighted RRF. Sets {@code displayScore} on every returned hit.
     */
    private List<ScoredHit> rank(List<ScoredHit> scored, String searchMode, boolean semanticAvailable, String timeHint) {
        if (semanticAvailable && "SEMANTIC".equalsIgnoreCase(searchMode)) {
            List<ScoredHit> semanticOnly = new ArrayList<>(scored.stream()
                    .filter(hit -> hit.hasSemantic && hit.semantic >= MIN_SEMANTIC_SIMILARITY)
                    .toList());
            semanticOnly.sort((left, right) -> {
                int bySimilarity = Double.compare(right.semantic, left.semantic);
                return bySimilarity != 0 ? bySimilarity : timeCompare(left, right, timeHint);
            });
            semanticOnly.forEach(hit -> hit.displayScore = hit.semantic);
            return semanticOnly;
        }
        if (semanticAvailable && "HYBRID".equalsIgnoreCase(searchMode)) {
            List<ScoredHit> lexicalRanked = new ArrayList<>(scored.stream()
                    .filter(hit -> hit.matchedTerms > 0)
                    .toList());
            lexicalRanked.sort((left, right) -> {
                int byScore = Integer.compare(right.matchedTerms, left.matchedTerms);
                return byScore != 0 ? byScore : timeCompare(left, right, timeHint);
            });
            List<ScoredHit> semanticRanked = new ArrayList<>(scored.stream()
                    .filter(hit -> hit.hasSemantic && hit.semantic >= MIN_SEMANTIC_SIMILARITY)
                    .toList());
            semanticRanked.sort((left, right) -> Double.compare(right.semantic, left.semantic));
            Map<Long, Double> fused = new HashMap<>();
            Map<Long, ScoredHit> byId = new HashMap<>();
            for (int index = 0; index < lexicalRanked.size(); index++) {
                ScoredHit hit = lexicalRanked.get(index);
                byId.put(hit.digest.getId(), hit);
                fused.merge(hit.digest.getId(), 1.0 / (RRF_K + index + 1), Double::sum);
            }
            for (int index = 0; index < semanticRanked.size(); index++) {
                ScoredHit hit = semanticRanked.get(index);
                byId.putIfAbsent(hit.digest.getId(), hit);
                fused.merge(hit.digest.getId(), 1.0 / (RRF_K + index + 1), Double::sum);
            }
            List<ScoredHit> union = new ArrayList<>(byId.values());
            double topFused = union.stream().mapToDouble(hit -> fused.get(hit.digest.getId())).max().orElse(1.0);
            union.sort((left, right) -> {
                int byFused = Double.compare(fused.get(right.digest.getId()), fused.get(left.digest.getId()));
                return byFused != 0 ? byFused : timeCompare(left, right, timeHint);
            });
            union.forEach(hit -> hit.displayScore = fused.get(hit.digest.getId()) / topFused);
            return union;
        }
        List<ScoredHit> lexical = new ArrayList<>(scored.stream()
                .filter(hit -> hit.matchedTerms > 0)
                .toList());
        lexical.sort((left, right) -> {
            int byScore = Integer.compare(right.matchedTerms, left.matchedTerms);
            return byScore != 0 ? byScore : timeCompare(left, right, timeHint);
        });
        int maxMatched = lexical.isEmpty() ? 1 : lexical.get(0).matchedTerms;
        lexical.forEach(hit -> hit.displayScore = (double) hit.matchedTerms / maxMatched);
        return lexical;
    }

    private int timeCompare(ScoredHit left, ScoredHit right, String timeHint) {
        LocalDateTime leftTime = left.digest.getCreatedAt();
        LocalDateTime rightTime = right.digest.getCreatedAt();
        if (leftTime == null || rightTime == null) {
            return 0;
        }
        return "EARLIEST".equalsIgnoreCase(timeHint)
                ? leftTime.compareTo(rightTime)
                : rightTime.compareTo(leftTime);
    }

    /** Cosine similarity per candidate digest from the TURN_DIGEST chunk plane; empty when none embedded. */
    private Map<Long, Double> loadSemanticScores(Long userId, List<AiTurnDigestEntity> candidates, List<Double> queryVector) {
        List<String> ownerIds = candidates.stream().map(digest -> String.valueOf(digest.getId())).toList();
        if (ownerIds.isEmpty()) {
            return Map.of();
        }
        List<AiRetrievalChunkEntity> chunks;
        try {
            chunks = chunkMapper.selectList(new QueryWrapper<AiRetrievalChunkEntity>()
                    .eq("user_id", userId)
                    .eq("owner_type", TurnDigestEmbedHandler.OWNER_TYPE)
                    .in("owner_id", ownerIds)
                    .isNotNull("embedding_json"));
        } catch (RuntimeException ex) {
            return Map.of();
        }
        Map<Long, Double> scores = new HashMap<>();
        for (AiRetrievalChunkEntity chunk : chunks) {
            try {
                JsonNode vector = objectMapper.readTree(chunk.getEmbeddingJson());
                if (!vector.isArray()) {
                    continue;
                }
                List<Double> values = new ArrayList<>(vector.size());
                vector.forEach(item -> values.add(item.asDouble()));
                double similarity = cosine(queryVector, values);
                if (!Double.isNaN(similarity)) {
                    scores.put(Long.valueOf(chunk.getOwnerId()), similarity);
                }
            } catch (Exception ignored) {
                // malformed chunk: skip it, other chunks still score
            }
        }
        return scores;
    }

    static double cosine(List<Double> left, List<Double> right) {
        if (left == null || right == null || left.isEmpty() || left.size() != right.size()) {
            return Double.NaN;
        }
        double dot = 0.0;
        double leftNorm = 0.0;
        double rightNorm = 0.0;
        for (int index = 0; index < left.size(); index++) {
            dot += left.get(index) * right.get(index);
            leftNorm += left.get(index) * left.get(index);
            rightNorm += right.get(index) * right.get(index);
        }
        if (leftNorm == 0.0 || rightNorm == 0.0) {
            return Double.NaN;
        }
        return dot / (Math.sqrt(leftNorm) * Math.sqrt(rightNorm));
    }

    private Map<String, Object> toHit(ScoredHit scored, int maxMatched) {
        AiTurnDigestEntity digest = scored.digest;
        Map<String, Object> hit = new LinkedHashMap<>();
        hit.put("hitId", HIT_ID_PREFIX + digest.getId());
        hit.put("digestId", String.valueOf(digest.getId()));
        hit.put("turnId", digest.getTurnId());
        hit.put("conversationId", digest.getConversationId());
        hit.put("summary", digest.getSummary());
        hit.put("status", digest.getStatus());
        hit.put("createdAt", digest.getCreatedAt() == null ? null : digest.getCreatedAt().toString());
        hit.put("keywords", extractTextArray(digest.getStructuredDigest(), "searchKeywords", 10));
        hit.put("entities", extractEntityNames(digest.getStructuredDigest(), 6));
        double lexical = maxMatched == 0 ? 0.0 : (double) scored.matchedTerms / maxMatched;
        hit.put("score", Math.round(scored.displayScore * 100.0) / 100.0);
        Map<String, Object> breakdown = new LinkedHashMap<>();
        breakdown.put("lexical", Math.round(lexical * 100.0) / 100.0);
        breakdown.put("semantic", Math.round((scored.hasSemantic ? scored.semantic : 0.0) * 100.0) / 100.0);
        hit.put("scoreBreakdown", breakdown);
        hit.put("requiresFetch", true);
        return hit;
    }

    private ToolResult<Object> success(List<Map<String, Object>> hits, int topK, List<String> warnings, String scope) {
        Map<String, Object> data = new LinkedHashMap<>();
        data.put("hits", hits);
        data.put("hitCount", hits.size());
        data.put("topK", topK);
        data.put("scope", scope);
        if (!warnings.isEmpty()) {
            data.put("warnings", warnings);
        }
        List<SourceRef> sources = hits.stream()
                .map(hit -> new SourceRef("TURN_DIGEST", String.valueOf(hit.get("digestId"))))
                .toList();
        return ToolResult.success(null, data, sources, DataClassification.USER_PRIVATE, TrustLevel.DERIVED_SUMMARY);
    }

    /** Whitespace tokens plus overlapping CJK bigrams, so Chinese queries match without word segmentation. */
    static List<String> queryTerms(String query) {
        Set<String> terms = new LinkedHashSet<>();
        StringBuilder cjkRun = new StringBuilder();
        for (int index = 0; index < query.length(); index++) {
            char ch = query.charAt(index);
            if (Character.isWhitespace(ch) || "，。、？！；：（）【】\"'`".indexOf(ch) >= 0) {
                flushCjkRun(cjkRun, terms);
                continue;
            }
            if (isCjk(ch)) {
                cjkRun.append(ch);
            } else {
                flushCjkRun(cjkRun, terms);
                StringBuilder token = new StringBuilder();
                while (index < query.length()) {
                    char inner = query.charAt(index);
                    if (Character.isWhitespace(inner) || isCjk(inner) || "，。、？！；：（）【】\"'`".indexOf(inner) >= 0) {
                        index--;
                        break;
                    }
                    token.append(inner);
                    index++;
                }
                if (token.length() >= 2 && token.length() <= 40) {
                    terms.add(token.toString());
                }
            }
        }
        flushCjkRun(cjkRun, terms);
        return new ArrayList<>(terms);
    }

    private static void flushCjkRun(StringBuilder run, Set<String> terms) {
        if (run.length() == 0) {
            return;
        }
        String text = run.toString();
        run.setLength(0);
        if (text.length() <= 3) {
            terms.add(text);
            return;
        }
        for (int size = Math.min(4, text.length()); size >= 2; size--) {
            for (int start = 0; start + size <= text.length() && terms.size() < 40; start++) {
                terms.add(text.substring(start, start + size));
            }
        }
    }

    private static boolean isCjk(char ch) {
        Character.UnicodeBlock block = Character.UnicodeBlock.of(ch);
        return block == Character.UnicodeBlock.CJK_UNIFIED_IDEOGRAPHS
                || block == Character.UnicodeBlock.CJK_UNIFIED_IDEOGRAPHS_EXTENSION_A
                || block == Character.UnicodeBlock.CJK_COMPATIBILITY_IDEOGRAPHS;
    }

    private boolean matchesEntityType(String structuredDigest, Set<String> filter) {
        try {
            JsonNode entities = objectMapper.readTree(structuredDigest == null ? "{}" : structuredDigest).path("entities");
            if (entities.isArray()) {
                for (JsonNode entity : entities) {
                    if (filter.contains(entity.path("type").asText(""))) {
                        return true;
                    }
                }
            }
        } catch (Exception ignored) {
            // malformed digest JSON: treat as non-matching only when filter is applied
        }
        return false;
    }

    private List<String> extractTextArray(String structuredDigest, String field, int cap) {        List<String> values = new ArrayList<>();
        try {
            JsonNode array = objectMapper.readTree(structuredDigest == null ? "{}" : structuredDigest).path(field);
            if (array.isArray()) {
                for (JsonNode item : array) {
                    if (values.size() >= cap) {
                        break;
                    }
                    if (item.isTextual() && !item.asText().isBlank()) {
                        values.add(item.asText());
                    }
                }
            }
        } catch (Exception ignored) {
            // malformed digest JSON: return what we have
        }
        return values;
    }

    private List<String> extractEntityNames(String structuredDigest, int cap) {
        List<String> values = new ArrayList<>();
        try {
            JsonNode entities = objectMapper.readTree(structuredDigest == null ? "{}" : structuredDigest).path("entities");
            if (entities.isArray()) {
                for (JsonNode entity : entities) {
                    if (values.size() >= cap) {
                        break;
                    }
                    String name = entity.path("canonicalName").asText("");
                    if (!name.isBlank()) {
                        values.add(name);
                    }
                }
            }
        } catch (Exception ignored) {
            // malformed digest JSON: return what we have
        }
        return values;
    }

    private JsonNode buildSchema() {
        ObjectNode schema = objectMapper.createObjectNode();
        schema.put("type", "object");
        schema.put("additionalProperties", false);
        schema.putArray("required").add("query");
        ObjectNode properties = schema.putObject("properties");
        ObjectNode query = properties.putObject("query");
        query.put("type", "string");
        query.put("description", "Natural-language description of what to locate, e.g. '之前讨论过的第二道异或题'.");
        query.put("minLength", 1);
        query.put("maxLength", MAX_QUERY_CHARS);
        ObjectNode scope = properties.putObject("scope");
        scope.put("type", "string");
        scope.put("description", "CURRENT_CONVERSATION (default) searches only this conversation; "
                + "ALL_MY_CONVERSATIONS searches digests across all your own conversations.");
        scope.putArray("enum").add("CURRENT_CONVERSATION").add("ALL_MY_CONVERSATIONS");
        ObjectNode searchMode = properties.putObject("searchMode");
        searchMode.put("type", "string");
        searchMode.put("description", "KEYWORD (default), SEMANTIC or HYBRID. Semantic lanes degrade to KEYWORD when embeddings are unavailable.");
        searchMode.putArray("enum").add("KEYWORD").add("SEMANTIC").add("HYBRID");
        ObjectNode timeHint = properties.putObject("timeHint");
        timeHint.put("type", "string");
        timeHint.put("description", "EARLIEST (最开始), LATEST (最近) or ANY (default).");
        timeHint.putArray("enum").add("EARLIEST").add("LATEST").add("ANY");
        ObjectNode entityTypes = properties.putObject("entityTypes");
        entityTypes.put("type", "array");
        entityTypes.put("description", "Optional entity type filter, e.g. [\"PROBLEM\"].");
        entityTypes.putObject("items").put("type", "string");
        ObjectNode topK = properties.putObject("topK");
        topK.put("type", "integer");
        topK.put("description", "Maximum hits (default 8, max 10).");
        topK.put("minimum", 1);
        topK.put("maximum", MAX_TOP_K);
        return schema;
    }

    private static final class ScoredHit {
        private final AiTurnDigestEntity digest;
        private final int matchedTerms;
        private final double semantic;
        private final boolean hasSemantic;
        /** Mode-specific score surfaced as {@code score} in the hit payload; set by rank(). */
        private double displayScore;

        private ScoredHit(AiTurnDigestEntity digest, int matchedTerms, double semantic, boolean hasSemantic) {
            this.digest = digest;
            this.matchedTerms = matchedTerms;
            this.semantic = semantic;
            this.hasSemantic = hasSemantic;
        }
    }
}
