package com.aioj.next.ai.domain;

import com.aioj.next.common.error.DomainException;
import com.aioj.next.common.error.ErrorCode;
import com.aioj.next.ai.config.AiProperties;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientException;
import org.springframework.web.client.RestClientResponseException;

import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

@Component
public class AiModelCompletionClient {
    private final ObjectMapper objectMapper;
    private final RestClient restClient;

    @Autowired
    public AiModelCompletionClient(ObjectMapper objectMapper, AiProperties properties) {
        this(objectMapper, AiHttpClients.create(properties));
    }

    AiModelCompletionClient(ObjectMapper objectMapper, RestClient restClient) {
        this.objectMapper = objectMapper;
        this.restClient = restClient;
    }

    public AiModelCompletionClient(ObjectMapper objectMapper) {
        this(objectMapper, AiHttpClients.create(new AiProperties()));
    }

    public CompletionResult complete(AiModelEffectiveConfig config, List<Map<String, String>> messages,
                                     double temperature, int maxTokens, boolean jsonOutput) {
        if (config == null || !config.enabled()) {
            throw new DomainException(ErrorCode.BAD_REQUEST, "AI model configuration is disabled");
        }
        if (!config.hasApiKey()) {
            throw new DomainException(ErrorCode.BAD_REQUEST, "AI API key is not configured");
        }
        return complete(config, messages, temperature, maxTokens, jsonOutput, true);
    }

    public CompletionResult completeWithJsonSchema(AiModelEffectiveConfig config, List<Map<String, String>> messages,
                                                   double temperature, int maxTokens, JsonNode schema) {
        if (config == null || !config.enabled()) {
            throw new DomainException(ErrorCode.BAD_REQUEST, "AI model configuration is disabled");
        }
        if (!config.hasApiKey()) {
            throw new DomainException(ErrorCode.BAD_REQUEST, "AI API key is not configured");
        }
        return complete(config, messages, temperature, maxTokens, true, true, schema);
    }

    private CompletionResult complete(AiModelEffectiveConfig config, List<Map<String, String>> messages,
                                      double temperature, int maxTokens, boolean jsonOutput,
                                      boolean allowThinkingRetry) {
        return complete(config, messages, temperature, maxTokens, jsonOutput, allowThinkingRetry, null);
    }

    private CompletionResult complete(AiModelEffectiveConfig config, List<Map<String, String>> messages,
                                      double temperature, int maxTokens, boolean jsonOutput,
                                      boolean allowThinkingRetry, JsonNode jsonSchema) {
        Map<String, Object> body = completionBody(messages, temperature, maxTokens, config, jsonOutput, jsonSchema);
        try {
            String response = restClient.post()
                    .uri(config.baseUrl())
                    .header(HttpHeaders.AUTHORIZATION, "Bearer " + config.apiKey())
                    .contentType(MediaType.APPLICATION_JSON)
                    .accept(MediaType.APPLICATION_JSON)
                    .body(body)
                    .retrieve()
                    .body(String.class);
            JsonNode root = objectMapper.readTree(response);
            String content = visibleAssistantContent(root);
            if (content == null || content.isBlank()) {
                if (allowThinkingRetry && shouldRetryWithoutThinking(config)) {
                    CompletionResult retry = complete(
                            config.withOverrides(null, null, null, null, null, null, null,
                                    null, null, false, null, null, null, null),
                            messages,
                            temperature,
                            maxTokens,
                            jsonOutput,
                            false
                    );
                    long emptyPromptTokens = root.path("usage").path("prompt_tokens").asLong(0);
                    long emptyCompletionTokens = root.path("usage").path("completion_tokens").asLong(0);
                    return new CompletionResult(
                            retry.content(),
                            retry.provider(),
                            retry.model(),
                            emptyPromptTokens + retry.promptTokens(),
                            emptyCompletionTokens + retry.completionTokens()
                    );
                }
                throw new DomainException(ErrorCode.INTERNAL_ERROR, "AI provider returned empty content");
            }
            long promptTokens = root.path("usage").path("prompt_tokens").asLong(estimateTokens(messages.toString()));
            long completionTokens = root.path("usage").path("completion_tokens").asLong(estimateTokens(content));
            return new CompletionResult(content, config.provider(), config.model(), promptTokens, completionTokens);
        } catch (RestClientResponseException ex) {
            throw new DomainException(ErrorCode.INTERNAL_ERROR,
                    "AI provider HTTP " + ex.getStatusCode().value() + ": " + summarize(ex.getResponseBodyAsString(), config.apiKey()));
        } catch (RestClientException ex) {
            throw new DomainException(ErrorCode.INTERNAL_ERROR, "AI provider request failed: " + summarize(ex.getMessage(), config.apiKey()));
        } catch (DomainException ex) {
            throw ex;
        } catch (Exception ex) {
            throw new DomainException(ErrorCode.INTERNAL_ERROR, "AI provider response could not be parsed");
        }
    }

    private boolean shouldRetryWithoutThinking(AiModelEffectiveConfig config) {
        return config.thinkingEnabled() && (isDeepSeekRequest(config) || isKimiThinkingModel(config));
    }

    public record EmbeddingProbeResult(int dimension, long promptTokens) {
    }

    /**
     * Sends an OpenAI-compatible embeddings request for admin configuration tests.
     * Chat completions cannot be used to probe an embeddings endpoint.
     */
    public EmbeddingProbeResult embedProbe(AiModelEffectiveConfig config, String input) {
        if (config == null || !config.enabled()) {
            throw new DomainException(ErrorCode.BAD_REQUEST, "AI model configuration is disabled");
        }
        Map<String, Object> body = new HashMap<>();
        body.put("model", config.model());
        body.put("input", input == null || input.isBlank() ? "ai-model-config-embedding-test" : input.trim());
        body.put("encoding_format", "float");
        if (config.embeddingDimension() != null && config.embeddingDimension() > 0) {
            body.put("dimensions", config.embeddingDimension());
        }
        try {
            String response = restClient.post()
                    .uri(normalizeEmbeddingUrl(config.baseUrl()))
                    .header(HttpHeaders.AUTHORIZATION, "Bearer " + config.apiKey())
                    .contentType(MediaType.APPLICATION_JSON)
                    .accept(MediaType.APPLICATION_JSON)
                    .body(body)
                    .retrieve()
                    .body(String.class);
            JsonNode root = objectMapper.readTree(response);
            JsonNode vector = root.path("data").path(0).path("embedding");
            if (!vector.isArray() || vector.isEmpty()) {
                throw new DomainException(ErrorCode.INTERNAL_ERROR, "AI provider returned no embedding vector");
            }
            long promptTokens = root.path("usage").path("prompt_tokens").asLong(
                    root.path("usage").path("total_tokens").asLong(0));
            return new EmbeddingProbeResult(vector.size(), promptTokens);
        } catch (RestClientResponseException ex) {
            throw new DomainException(ErrorCode.INTERNAL_ERROR,
                    "AI provider HTTP " + ex.getStatusCode().value() + ": " + summarize(ex.getResponseBodyAsString(), config.apiKey()));
        } catch (RestClientException ex) {
            throw new DomainException(ErrorCode.INTERNAL_ERROR, "AI provider request failed: " + summarize(ex.getMessage(), config.apiKey()));
        } catch (DomainException ex) {
            throw ex;
        } catch (Exception ex) {
            throw new DomainException(ErrorCode.INTERNAL_ERROR, "AI provider response could not be parsed");
        }
    }

    /** Normalizes an embeddings base URL; accepts the compatible-mode root or a /v1 suffix. */
    String normalizeEmbeddingUrl(String baseUrl) {
        String url = baseUrl == null ? "" : baseUrl.trim();
        while (url.endsWith("/")) {
            url = url.substring(0, url.length() - 1);
        }
        String lower = url.toLowerCase(Locale.ROOT);
        if (lower.endsWith("/embeddings")) {
            return url;
        }
        if (lower.endsWith("/chat/completions")) {
            return url.substring(0, url.length() - "/chat/completions".length()) + "/embeddings";
        }
        if (lower.endsWith("/compatible-mode")) {
            return url + "/v1/embeddings";
        }
        if (lower.endsWith("/v1")) {
            return url + "/embeddings";
        }
        return url + "/embeddings";
    }

    Map<String, Object> completionBody(List<Map<String, String>> messages, double temperature, int maxTokens,
                                       AiModelEffectiveConfig config, boolean jsonOutput) {
        return completionBody(messages, temperature, maxTokens, config, jsonOutput, null);
    }

    Map<String, Object> completionBody(List<Map<String, String>> messages, double temperature, int maxTokens,
                                       AiModelEffectiveConfig config, boolean jsonOutput, JsonNode jsonSchema) {
        Map<String, Object> body = new HashMap<>();
        body.put("model", config.model());
        body.put("messages", messages);
        body.put("stream", false);
        Double effectiveTemperature = effectiveTemperature(config, temperature);
        if (effectiveTemperature != null) {
            body.put("temperature", effectiveTemperature);
        }
        if (maxTokens > 0) {
            body.put("max_tokens", maxTokens);
        }
        if (jsonOutput && config.jsonOutputEnabled() && jsonSchema != null && supportsJsonSchema(config)) {
            body.put("response_format", Map.of(
                    "type", "json_schema",
                    "json_schema", Map.of(
                            "name", "problem_draft",
                            "strict", true,
                            "schema", jsonSchema
                    )
            ));
        } else if (jsonOutput && config.jsonOutputEnabled() && supportsJsonOutput(config)) {
            body.put("response_format", Map.of("type", "json_object"));
        }
        applyThinkingOptions(body, config);
        return body;
    }

    String visibleAssistantContent(JsonNode root) {
        JsonNode message = root.path("choices").path(0).path("message");
        JsonNode content = message.path("content");
        if (content.isTextual()) {
            return content.asText();
        }
        if (content.isArray()) {
            StringBuilder text = new StringBuilder();
            for (JsonNode item : content) {
                JsonNode part = item.path("text");
                if (part.isTextual()) {
                    text.append(part.asText());
                }
            }
            return text.toString();
        }
        return null;
    }

    private void applyThinkingOptions(Map<String, Object> body, AiModelEffectiveConfig config) {
        if (isDeepSeekRequest(config)) {
            body.put("thinking", Map.of("type", config.thinkingEnabled() ? "enabled" : "disabled"));
            if (config.thinkingEnabled()) {
                body.put("reasoning_effort", "max".equalsIgnoreCase(config.reasoningEffort()) ? "max" : "high");
            }
            return;
        }
        if (isKimiThinkingModel(config)) {
            body.put("thinking", Map.of("type", config.thinkingEnabled() ? "enabled" : "disabled"));
        }
    }

    private Double effectiveTemperature(AiModelEffectiveConfig config, double requestedTemperature) {
        if (isDeepSeekRequest(config) && config.thinkingEnabled()) {
            return null;
        }
        if (isKimiThinkingModel(config)) {
            return config.thinkingEnabled() ? 1.0 : 0.6;
        }
        return requestedTemperature;
    }

    private boolean supportsJsonOutput(AiModelEffectiveConfig config) {
        return isDeepSeekRequest(config) || isMoonshotOrKimiRequest(config);
    }

    private boolean supportsJsonSchema(AiModelEffectiveConfig config) {
        return containsIgnoreCase(config.provider(), "openai")
                || containsIgnoreCase(config.baseUrl(), "api.openai.com");
    }

    private boolean isDeepSeekRequest(AiModelEffectiveConfig config) {
        return containsIgnoreCase(config.provider(), "deepseek")
                || containsIgnoreCase(config.model(), "deepseek")
                || containsIgnoreCase(config.baseUrl(), "api.deepseek.com");
    }

    private boolean isKimiThinkingModel(AiModelEffectiveConfig config) {
        if (!isMoonshotOrKimiRequest(config)) {
            return false;
        }
        String model = defaultString(config.model()).toLowerCase(Locale.ROOT);
        return model.startsWith("kimi-k2.5") || model.startsWith("kimi-k2.6");
    }

    private boolean isMoonshotOrKimiRequest(AiModelEffectiveConfig config) {
        return containsIgnoreCase(config.provider(), "moonshot")
                || containsIgnoreCase(config.provider(), "kimi")
                || containsIgnoreCase(config.baseUrl(), "api.moonshot.");
    }

    private boolean containsIgnoreCase(String value, String needle) {
        return value != null && needle != null && value.toLowerCase().contains(needle.toLowerCase());
    }

    private String defaultString(String value) {
        return value == null ? "" : value;
    }

    private long estimateTokens(String value) {
        if (value == null || value.isBlank()) {
            return 0;
        }
        return Math.max(1, (value.length() + 3L) / 4L);
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
        sanitized = sanitized.replaceAll("(?i)(your\\s+api\\s+key\\s*:\\s*)\\*+[^\\s\\\"',;，。}]+", "$1***");
        sanitized = sanitized.replaceAll("(?i)(api\\s+key\\s*:\\s*)\\*+[^\\s\\\"',;，。}]+", "$1***");
        return sanitized.length() <= 500 ? sanitized : sanitized.substring(0, 500);
    }

    public record CompletionResult(String content, String provider, String model, long promptTokens, long completionTokens) {
    }
}
