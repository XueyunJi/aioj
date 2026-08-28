package com.aioj.next.ai.agent.context;

import com.aioj.next.ai.persistence.entity.AiContextManifestEntity;
import com.aioj.next.ai.persistence.mapper.AiContextManifestMapper;
import com.baomidou.mybatisplus.core.toolkit.IdWorker;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.LocalDateTime;
import java.util.HexFormat;
import java.util.List;

/**
 * Persists an ai_context_manifests row for every model call (design doc §6.7):
 * which sections, prompt version, policy snapshot, tool definitions hash, and
 * context hash went into the call. Persistence failure never breaks a turn.
 */
@Service
public class ContextManifestService {

    private static final Logger log = LoggerFactory.getLogger(ContextManifestService.class);

    private final AiContextManifestMapper mapper;
    private final ObjectMapper objectMapper;

    public ContextManifestService(AiContextManifestMapper mapper, ObjectMapper objectMapper) {
        this.mapper = mapper;
        this.objectMapper = objectMapper;
    }

    public void record(String turnId, Long agentRunId, int callSeq, String model, String promptVersion,
                       String policySnapshotId, List<ContextSection> sections, String toolDefinitionsHash,
                       String contextHash, Integer inputTokens, Integer cacheHitTokens, List<String> warnings) {
        try {
            AiContextManifestEntity entity = new AiContextManifestEntity();
            entity.setId(IdWorker.getId());
            entity.setTurnId(turnId);
            entity.setAgentRunId(agentRunId);
            entity.setCallSeq(callSeq);
            entity.setModel(model);
            entity.setPromptVersion(promptVersion);
            entity.setPolicySnapshotId(policySnapshotId);
            entity.setSectionsJson(sectionsJson(sections));
            entity.setToolDefinitionsHash(toolDefinitionsHash);
            entity.setContextHash(contextHash);
            entity.setInputTokens(inputTokens);
            entity.setCacheHitTokens(cacheHitTokens);
            entity.setWarningsJson(warnings == null || warnings.isEmpty()
                    ? "[]"
                    : objectMapper.writeValueAsString(warnings));
            entity.setCreatedAt(LocalDateTime.now());
            mapper.insert(entity);
        } catch (Exception ex) {
            log.warn("AI context manifest persist failed turn={} callSeq={} error={}", turnId, callSeq, ex.toString());
        }
    }

    private String sectionsJson(List<ContextSection> sections) throws Exception {
        ArrayNode array = objectMapper.createArrayNode();
        for (ContextSection section : sections) {
            ObjectNode node = array.addObject();
            node.put("type", section.type().name());
            node.put("tokenEstimate", section.tokenEstimate());
            node.put("trust", section.trustLevel().name());
            node.put("atomic", section.atomic());
            if (!section.messages().isEmpty()) {
                node.put("messageCount", section.messages().size());
            }
        }
        return objectMapper.writeValueAsString(array);
    }

    public static String sha256(String text) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            return HexFormat.of().formatHex(digest.digest((text == null ? "" : text).getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException ex) {
            throw new IllegalStateException("SHA-256 unavailable", ex);
        }
    }
}
