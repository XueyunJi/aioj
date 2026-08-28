package com.aioj.next.ai.agent.digest;

import com.aioj.next.ai.agent.asyncjob.AgentAsyncJobHandler;
import com.aioj.next.ai.agent.context.ContextManifestService;
import com.aioj.next.ai.domain.AiModelConfigService;
import com.aioj.next.ai.domain.AiModelEffectiveConfig;
import com.aioj.next.ai.domain.AiModelScope;
import com.aioj.next.ai.domain.OpenAiCompatibleProvider;
import com.aioj.next.ai.persistence.entity.AiAsyncJobEntity;
import com.aioj.next.ai.persistence.entity.AiRetrievalChunkEntity;
import com.aioj.next.ai.persistence.entity.AiTurnDigestEntity;
import com.aioj.next.ai.persistence.mapper.AiRetrievalChunkMapper;
import com.aioj.next.ai.persistence.mapper.AiTurnDigestMapper;
import com.baomidou.mybatisplus.core.conditions.query.QueryWrapper;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.time.LocalDateTime;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * Async digest embedding writer (design doc §6.4 第一层 dense lane). For every READY
 * digest, embeds its retrieval text and upserts one ai_retrieval_chunks row owned by
 * {@code TURN_DIGEST:<digestId>}. Idempotent via textHash; a missing vector from the
 * provider throws so the job retries with backoff, while a disabled embedding config
 * completes silently (the KEYWORD lane keeps serving searches).
 */
@Component
public class TurnDigestEmbedHandler implements AgentAsyncJobHandler {

    public static final String OWNER_TYPE = "TURN_DIGEST";

    private static final Logger log = LoggerFactory.getLogger(TurnDigestEmbedHandler.class);

    private static final int MAX_EMBED_TEXT_CHARS = 2000;

    private final AiTurnDigestMapper digestMapper;
    private final AiRetrievalChunkMapper chunkMapper;
    private final OpenAiCompatibleProvider provider;
    private final AiModelConfigService configService;
    private final ObjectMapper objectMapper;

    public TurnDigestEmbedHandler(
            AiTurnDigestMapper digestMapper,
            AiRetrievalChunkMapper chunkMapper,
            OpenAiCompatibleProvider provider,
            AiModelConfigService configService,
            ObjectMapper objectMapper
    ) {
        this.digestMapper = digestMapper;
        this.chunkMapper = chunkMapper;
        this.provider = provider;
        this.configService = configService;
        this.objectMapper = objectMapper;
    }

    @Override
    public String jobType() {
        return TurnDigestService.JOB_TYPE_EMBED_DIGEST;
    }

    @Override
    public void handle(AiAsyncJobEntity job) throws Exception {
        JsonNode payload = objectMapper.readTree(job.getPayloadJson());
        long digestId = payload.path("digestId").asLong(0);
        Long userId = payload.path("userId").isNumber() ? payload.path("userId").asLong() : null;
        if (digestId <= 0 || userId == null) {
            throw new IllegalStateException("embed job payload missing digestId/userId");
        }
        AiTurnDigestEntity digest = digestMapper.selectById(digestId);
        if (digest == null || !userId.equals(digest.getUserId())) {
            throw new IllegalStateException("embed job digest missing or ownership mismatch: " + digestId);
        }
        if (!StubDigestFactory.STATUS_READY.equals(digest.getStatus())) {
            return; // only curated (READY) digests enter the dense lane
        }

        AiModelEffectiveConfig embeddingConfig = configService.effectiveConfig(AiModelScope.EMBEDDING);
        if (!embeddingConfig.enabled() || !embeddingConfig.hasApiKey()) {
            log.debug("embedding config disabled; skipping digest embedding digest={}", digestId);
            return;
        }

        String embedText = embedText(digest);
        if (embedText.isBlank()) {
            return;
        }
        String textHash = ContextManifestService.sha256(embedText);
        AiRetrievalChunkEntity existing = chunkMapper.selectOne(new QueryWrapper<AiRetrievalChunkEntity>()
                .eq("owner_type", OWNER_TYPE)
                .eq("owner_id", String.valueOf(digestId))
                .last("LIMIT 1"));
        if (existing != null && textHash.equals(existing.getTextHash()) && existing.getEmbeddingJson() != null) {
            return; // identical text already embedded: idempotent no-op
        }

        Optional<List<Double>> embedding = provider.embed(embedText);
        if (embedding.isEmpty()) {
            // Transient provider failure: throw so the async-job worker retries with backoff.
            throw new IllegalStateException("embedding provider returned no vector for digest " + digestId);
        }
        List<Double> values = embedding.get();
        AiRetrievalChunkEntity chunk = existing == null ? new AiRetrievalChunkEntity() : existing;
        chunk.setUserId(userId);
        chunk.setOwnerType(OWNER_TYPE);
        chunk.setOwnerId(String.valueOf(digestId));
        chunk.setChunkText(embedText);
        chunk.setMetadataJson(metadataJson(digest, digestId));
        chunk.setEmbeddingModel(embeddingConfig.model());
        chunk.setEmbeddingDimension(values.size());
        chunk.setEmbeddingJson(objectMapper.writeValueAsString(values));
        chunk.setTextHash(textHash);
        LocalDateTime now = LocalDateTime.now();
        chunk.setUpdatedAt(now);
        if (existing == null) {
            chunk.setCreatedAt(now);
            chunkMapper.insert(chunk);
        } else {
            chunkMapper.updateById(chunk);
        }
    }

    private String embedText(AiTurnDigestEntity digest) {
        StringBuilder builder = new StringBuilder(MAX_EMBED_TEXT_CHARS);
        append(builder, digest.getSummary());
        append(builder, digest.getSearchText());
        String text = builder.toString().trim();
        return text.length() > MAX_EMBED_TEXT_CHARS ? text.substring(0, MAX_EMBED_TEXT_CHARS) : text;
    }

    private void append(StringBuilder builder, String value) {
        if (value == null || value.isBlank() || builder.length() >= MAX_EMBED_TEXT_CHARS) {
            return;
        }
        if (builder.length() > 0) {
            builder.append(' ');
        }
        builder.append(value.trim());
    }

    private String metadataJson(AiTurnDigestEntity digest, long digestId) {
        try {
            Map<String, Object> metadata = new LinkedHashMap<>();
            metadata.put("conversationId", digest.getConversationId());
            metadata.put("turnId", digest.getTurnId());
            metadata.put("digestId", String.valueOf(digestId));
            return objectMapper.writeValueAsString(metadata);
        } catch (Exception ex) {
            return "{}";
        }
    }
}
