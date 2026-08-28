package com.aioj.next.ai.agent.tool;

import com.aioj.next.ai.agent.context.DataClassification;
import com.aioj.next.ai.persistence.entity.AiToolCallEntity;
import com.aioj.next.ai.persistence.mapper.AiToolCallMapper;
import com.baomidou.mybatisplus.core.toolkit.IdWorker;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.LocalDateTime;
import java.util.Iterator;
import java.util.Map;

/**
 * Writes the ai_tool_calls audit row for every brokered call (design doc §4.1).
 * Audit failure must not break a chat turn, so it is logged and swallowed.
 */
@Service
public class ToolAuditService {

    private static final Logger log = LoggerFactory.getLogger(ToolAuditService.class);
    private static final int MAX_AUDIT_ARGUMENTS_CHARS = 2_000;

    private final AiToolCallMapper mapper;
    private final ObjectMapper objectMapper;

    public ToolAuditService(AiToolCallMapper mapper, ObjectMapper objectMapper) {
        this.mapper = mapper;
        this.objectMapper = objectMapper;
    }

    public void record(ToolExecutionContext context, long agentRunId, int callSeq,
                       ToolDescriptor descriptor, String toolName, String callId,
                       String argumentsJson, String policyDecisionId, ToolStatus status,
                       DataClassification resultClassification, String resultPayload,
                       long latencyMs, String errorCode) {
        try {
            AiToolCallEntity entity = new AiToolCallEntity();
            entity.setId(IdWorker.getId());
            entity.setAgentRunId(agentRunId);
            entity.setTurnId(context.turnId());
            entity.setUserId(context.userId());
            entity.setCallId(callId == null ? "" : callId);
            entity.setCallSeq(callSeq);
            entity.setToolName(toolName);
            entity.setToolVersion(descriptor == null ? "-" : descriptor.version());
            boolean auditArguments = descriptor == null || descriptor.auditLevel() != ToolAuditLevel.NO_ARGUMENTS;
            entity.setArgumentsRedacted(auditArguments ? redactArguments(argumentsJson) : null);
            entity.setPolicyDecisionId(policyDecisionId);
            entity.setStatus(status.name());
            entity.setResultClassification(resultClassification == null ? null : resultClassification.name());
            entity.setResultHash(sha256(resultPayload));
            entity.setResultTokens(estimateTokens(resultPayload));
            entity.setLatencyMs((int) Math.max(0, latencyMs));
            entity.setErrorCode(errorCode);
            entity.setCreatedAt(LocalDateTime.now());
            mapper.insert(entity);
        } catch (RuntimeException ex) {
            log.warn("AI tool call audit failed turn={} tool={} error={}", context.turnId(), toolName, ex.toString());
        }
    }

    private String redactArguments(String argumentsJson) {
        if (argumentsJson == null || argumentsJson.isBlank()) {
            return null;
        }
        try {
            JsonNode tree = objectMapper.readTree(argumentsJson);
            JsonNode redacted = redact(tree);
            String serialized = redacted.toString();
            return serialized.length() <= MAX_AUDIT_ARGUMENTS_CHARS
                    ? serialized
                    : serialized.substring(0, MAX_AUDIT_ARGUMENTS_CHARS);
        } catch (Exception ex) {
            return "[unparseable arguments]";
        }
    }

    private JsonNode redact(JsonNode node) {
        if (node == null || !node.isObject()) {
            return node;
        }
        ObjectNode copy = ((ObjectNode) node).deepCopy();
        Iterator<Map.Entry<String, JsonNode>> fields = copy.fields();
        while (fields.hasNext()) {
            Map.Entry<String, JsonNode> entry = fields.next();
            String key = entry.getKey().toLowerCase(java.util.Locale.ROOT);
            if (key.contains("key") || key.contains("token") || key.contains("secret") || key.contains("password")) {
                copy.put(entry.getKey(), "***");
            } else if (entry.getValue().isObject()) {
                copy.set(entry.getKey(), redact(entry.getValue()));
            }
        }
        return copy;
    }

    private String sha256(String value) {
        if (value == null) {
            return null;
        }
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            byte[] hash = digest.digest(value.getBytes(StandardCharsets.UTF_8));
            StringBuilder hex = new StringBuilder(hash.length * 2);
            for (byte b : hash) {
                hex.append(Character.forDigit((b >> 4) & 0xF, 16)).append(Character.forDigit(b & 0xF, 16));
            }
            return hex.toString();
        } catch (Exception ex) {
            return null;
        }
    }

    private int estimateTokens(String value) {
        if (value == null || value.isBlank()) {
            return 0;
        }
        return (int) Math.max(1, (value.length() + 3L) / 4L);
    }
}
