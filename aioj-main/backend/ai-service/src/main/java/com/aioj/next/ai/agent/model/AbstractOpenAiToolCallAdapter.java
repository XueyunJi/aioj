package com.aioj.next.ai.agent.model;

import com.aioj.next.ai.agent.tool.ToolDescriptor;
import com.aioj.next.ai.agent.tool.ToolNameCodec;
import com.aioj.next.ai.config.AiProperties;
import com.aioj.next.ai.domain.AiModelEffectiveConfig;
import com.aioj.next.common.error.DomainException;
import com.aioj.next.common.error.ErrorCode;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientException;
import org.springframework.web.client.RestClientResponseException;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Shared OpenAI-compatible tool-calling transport. Provider subclasses only
 * customize thinking controls, tool_choice handling, and strict-schema flags
 * (design doc §3.2/§3.3).
 */
abstract class AbstractOpenAiToolCallAdapter implements ToolCallAdapter {

    private final ObjectMapper objectMapper;
    private final RestClient restClient;

    AbstractOpenAiToolCallAdapter(ObjectMapper objectMapper, AiProperties properties) {
        this(objectMapper, AgentHttpClients.create(properties));
    }

    AbstractOpenAiToolCallAdapter(ObjectMapper objectMapper, RestClient restClient) {
        this.objectMapper = objectMapper;
        this.restClient = restClient;
    }

    @Override
    public GatewayResponse execute(AiModelEffectiveConfig config, CallSettings settings, GatewayRequest request) {
        if (config == null || !config.enabled()) {
            throw new DomainException(ErrorCode.BAD_REQUEST, "AI model configuration is disabled");
        }
        if (!config.hasApiKey()) {
            throw new DomainException(ErrorCode.BAD_REQUEST, "AI API key is not configured");
        }
        Map<String, Object> body = buildRequestBody(config, settings, request);
        try {
            String response = restClient.post()
                    .uri(config.baseUrl())
                    .header(HttpHeaders.AUTHORIZATION, "Bearer " + config.apiKey())
                    .contentType(MediaType.APPLICATION_JSON)
                    .accept(MediaType.APPLICATION_JSON)
                    .body(body)
                    .retrieve()
                    .body(String.class);
            return parseResponse(response, config);
        } catch (RestClientResponseException ex) {
            throw new DomainException(ErrorCode.INTERNAL_ERROR,
                    "AI provider HTTP " + ex.getStatusCode().value() + ": " + summarize(ex.getResponseBodyAsString(), config.apiKey()));
        } catch (RestClientException ex) {
            throw new DomainException(ErrorCode.INTERNAL_ERROR,
                    "AI provider request failed: " + summarize(ex.getMessage(), config.apiKey()));
        } catch (DomainException ex) {
            throw ex;
        } catch (Exception ex) {
            throw new DomainException(ErrorCode.INTERNAL_ERROR, "AI provider response could not be parsed");
        }
    }

    private Map<String, Object> buildRequestBody(AiModelEffectiveConfig config, CallSettings settings, GatewayRequest request) {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("model", config.model());
        body.put("messages", buildMessages(request));
        body.put("stream", false);
        body.put("max_tokens", settings.maxTokens());
        body.put("temperature", settings.temperature());
        if (!request.tools().isEmpty()) {
            body.put("tools", buildTools(request.tools()));
            customizeToolChoice(body, config, request.toolChoice());
        }
        if (request.profile().forceJsonOutput()) {
            body.put("response_format", Map.of("type", "json_object"));
        }
        customizeThinking(body, config, settings);
        return body;
    }

    private List<Map<String, Object>> buildMessages(GatewayRequest request) {
        List<Map<String, Object>> messages = new ArrayList<>();
        for (GatewayMessage message : request.messages()) {
            Map<String, Object> node = new LinkedHashMap<>();
            node.put("role", message.role());
            if (message.content() != null) {
                node.put("content", message.content());
            }
            if (message.toolCalls() != null && !message.toolCalls().isEmpty()) {
                List<Map<String, Object>> toolCalls = new ArrayList<>();
                for (GatewayToolCall call : message.toolCalls()) {
                    toolCalls.add(Map.of(
                            "id", call.callId(),
                            "type", "function",
                            "function", Map.of(
                                    "name", ToolNameCodec.toWire(call.name()),
                                    "arguments", call.argumentsJson() == null ? "{}" : call.argumentsJson()
                            )
                    ));
                }
                node.put("tool_calls", toolCalls);
            }
            if ("tool".equals(message.role())) {
                node.put("tool_call_id", message.toolCallId());
            }
            messages.add(node);
        }
        return messages;
    }

    private List<Map<String, Object>> buildTools(List<ToolDescriptor> tools) {
        List<Map<String, Object>> payload = new ArrayList<>();
        for (ToolDescriptor descriptor : tools) {
            Map<String, Object> function = new LinkedHashMap<>();
            function.put("name", ToolNameCodec.toWire(descriptor.name()));
            function.put("description", descriptor.description());
            function.put("parameters", descriptor.inputSchema());
            customizeFunction(function);
            payload.add(Map.of("type", "function", "function", function));
        }
        return payload;
    }

    private GatewayResponse parseResponse(String response, AiModelEffectiveConfig config) throws Exception {
        JsonNode root = objectMapper.readTree(response);
        JsonNode choice = root.path("choices").path(0);
        JsonNode message = choice.path("message");
        String content = textContent(message.path("content"));
        List<GatewayToolCall> toolCalls = new ArrayList<>();
        for (JsonNode call : message.path("tool_calls")) {
            JsonNode function = call.path("function");
            toolCalls.add(new GatewayToolCall(
                    call.path("id").asText(""),
                    ToolNameCodec.toInternal(function.path("name").asText("")),
                    function.path("arguments").isTextual() ? function.get("arguments").asText() : function.path("arguments").toString()
            ));
        }
        JsonNode usage = root.path("usage");
        long promptTokens = usage.path("prompt_tokens").asLong(0);
        long completionTokens = usage.path("completion_tokens").asLong(0);
        long cacheHitTokens = usage.path("prompt_cache_hit_tokens").asLong(0);
        return new GatewayResponse(
                content,
                toolCalls,
                choice.path("finish_reason").asText(""),
                promptTokens,
                completionTokens,
                cacheHitTokens,
                config.provider(),
                config.model()
        );
    }

    private String textContent(JsonNode content) {
        if (content == null || content.isMissingNode() || content.isNull()) {
            return "";
        }
        if (content.isTextual()) {
            return content.asText();
        }
        if (content.isArray()) {
            StringBuilder text = new StringBuilder();
            for (JsonNode part : content) {
                JsonNode value = part.path("text");
                if (value.isTextual()) {
                    text.append(value.asText());
                }
            }
            return text.toString();
        }
        return "";
    }

    /** Provider thinking/reasoning controls; may remove {@code temperature} when thinking is on. */
    protected abstract void customizeThinking(Map<String, Object> body, AiModelEffectiveConfig config, CallSettings settings);

    /** Provider tool_choice handling; must reject unsupported modes with an explicit error. */
    protected abstract void customizeToolChoice(Map<String, Object> body, AiModelEffectiveConfig config, ToolChoiceMode mode);

    /** Provider per-function schema flags (e.g. Kimi strict). */
    protected void customizeFunction(Map<String, Object> function) {
    }

    protected ObjectMapper objectMapper() {
        return objectMapper;
    }

    private String summarize(String value, String apiKey) {
        if (value == null || value.isBlank()) {
            return "empty response body";
        }
        String sanitized = value;
        if (apiKey != null && !apiKey.isBlank()) {
            sanitized = sanitized.replace(apiKey, "***");
        }
        sanitized = sanitized.replaceAll("(?i)(api[_-]?key|token|secret|password)\\s*(?:=|:|是|为)\\s*[^\\s,;，。]+", "$1=***");
        return sanitized.length() <= 500 ? sanitized : sanitized.substring(0, 500);
    }
}
