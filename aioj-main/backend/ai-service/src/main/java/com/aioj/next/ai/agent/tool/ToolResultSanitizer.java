package com.aioj.next.ai.agent.tool;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.List;

/**
 * Renders the model-facing tool payload: structured JSON (never prose),
 * always flagged {@code instructionAllowed=false}, with token-budget
 * truncation that keeps the envelope itself valid JSON (design doc §4.1/§6.8).
 */
@Component
public class ToolResultSanitizer {

    private final ObjectMapper objectMapper;

    public ToolResultSanitizer(ObjectMapper objectMapper) {
        this.objectMapper = objectMapper;
    }

    public String toModelPayload(ToolResult<?> result, ToolDescriptor descriptor) {
        ObjectNode envelope = objectMapper.createObjectNode();
        envelope.put("status", result.status().name());
        int maxChars = descriptor == null ? 16_000 : descriptor.maxResultTokens() * 4;
        boolean truncated = result.truncated();
        List<String> warnings = new ArrayList<>(result.warnings());
        JsonNode dataNode = objectMapper.valueToTree(result.data());
        if (dataNode != null && !dataNode.isNull()) {
            String serialized = dataNode.toString();
            if (serialized.length() > maxChars) {
                truncated = true;
                warnings.add("Result was truncated to fit the token budget; narrow the query or fetch with pagination.");
                envelope.put("data", serialized.substring(0, maxChars) + "…[truncated]");
                envelope.put("dataTruncatedAsText", true);
            } else {
                envelope.set("data", dataNode);
            }
        }
        if (!result.sources().isEmpty()) {
            ArrayNode sources = envelope.putArray("sources");
            for (SourceRef source : result.sources()) {
                ObjectNode node = sources.addObject();
                node.put("type", source.type());
                node.put("id", source.id());
            }
        }
        if (result.classification() != null) {
            envelope.put("classification", result.classification().name());
        }
        if (result.trustLevel() != null) {
            envelope.put("trustLevel", result.trustLevel().name());
        }
        envelope.put("truncated", truncated);
        if (result.nextCursor() != null) {
            envelope.put("nextCursor", result.nextCursor());
        }
        if (result.errorCode() != null) {
            envelope.put("errorCode", result.errorCode());
        }
        if (result.errorMessage() != null) {
            envelope.put("errorMessage", sanitizeMessage(result.errorMessage()));
        }
        if (!warnings.isEmpty()) {
            ArrayNode warningNodes = envelope.putArray("warnings");
            for (String warning : warnings) {
                warningNodes.add(warning);
            }
        }
        envelope.put("instructionAllowed", false);
        return envelope.toString();
    }

    private String sanitizeMessage(String message) {
        String sanitized = message == null ? "" : message;
        sanitized = sanitized.replaceAll("(?i)(api[-_ ]?key|token|secret|password)\\s*[:=]\\s*\\S+", "$1=***");
        sanitized = sanitized.replaceAll("sk-[A-Za-z0-9_-]{8,}", "sk-***");
        return sanitized.length() <= 300 ? sanitized : sanitized.substring(0, 300);
    }
}
