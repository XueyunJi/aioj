package com.aioj.next.ai.domain;

import com.aioj.next.ai.config.AiProperties;
import com.aioj.next.contract.ai.AiModelListResponse;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientException;
import org.springframework.web.client.RestClientResponseException;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

@Component
public class AiModelCatalogClient {
    private final ObjectMapper objectMapper;
    private final RestClient restClient;

    @Autowired
    public AiModelCatalogClient(ObjectMapper objectMapper, AiProperties properties) {
        this.objectMapper = objectMapper;
        this.restClient = AiHttpClients.create(properties);
    }

    public AiModelCatalogClient(ObjectMapper objectMapper) {
        this(objectMapper, new AiProperties());
    }

    public AiModelListResponse listModels(AiModelScope scope, String provider, String baseUrl,
                                           EnvironmentAiModelConfigResolver.ApiKeyResolution key) {
        String normalizedProvider = normalizeProvider(provider);
        String modelsUrl = modelsUrl(normalizedProvider, baseUrl);
        if (modelsUrl == null) {
            return response(scope, normalizedProvider, baseUrl, key, "UNSUPPORTED",
                    "This provider does not expose a compatible model list API.", List.of());
        }
        if (key == null || !key.hasApiKey()) {
            return response(scope, normalizedProvider, baseUrl, key, "MISSING_KEY",
                    "Server environment API key is not configured for this provider.", List.of());
        }
        try {
            String raw = restClient.get()
                    .uri(modelsUrl)
                    .header(HttpHeaders.AUTHORIZATION, "Bearer " + key.apiKey())
                    .accept(MediaType.APPLICATION_JSON)
                    .retrieve()
                    .body(String.class);
            return response(scope, normalizedProvider, baseUrl, key, "SUCCESS", null,
                    parseModels(normalizedProvider, objectMapper.readTree(raw)));
        } catch (RestClientResponseException ex) {
            return response(scope, normalizedProvider, baseUrl, key, "FAILED",
                    "Provider returned HTTP " + ex.getStatusCode().value() + ": " + summarize(ex.getResponseBodyAsString(), key.apiKey()), List.of());
        } catch (RestClientException ex) {
            return response(scope, normalizedProvider, baseUrl, key, "FAILED",
                    "Provider model list request failed: " + summarize(ex.getMessage(), key.apiKey()), List.of());
        } catch (Exception ex) {
            return response(scope, normalizedProvider, baseUrl, key, "FAILED",
                    "Provider model list response could not be parsed.", List.of());
        }
    }

    String modelsUrl(String provider, String baseUrl) {
        String normalizedProvider = normalizeProvider(provider);
        if ("deepseek".equals(normalizedProvider)) {
            return "https://api.deepseek.com/models";
        }
        if ("moonshot".equals(normalizedProvider) || "kimi".equals(normalizedProvider)) {
            return containsIgnoreCase(baseUrl, "moonshot.cn")
                    ? "https://api.moonshot.cn/v1/models"
                    : "https://api.moonshot.ai/v1/models";
        }
        if ("dashscope".equals(normalizedProvider) || "qwen".equals(normalizedProvider) || "alibaba".equals(normalizedProvider)) {
            return null;
        }
        return deriveOpenAiModelsUrl(baseUrl);
    }

    List<AiModelListResponse.ModelOption> parseModels(String provider, JsonNode root) {
        JsonNode data = root == null ? null : root.path("data");
        if (data == null || !data.isArray()) {
            return List.of();
        }
        List<AiModelListResponse.ModelOption> models = new ArrayList<>();
        for (JsonNode item : data) {
            String id = text(item.path("id"));
            if (id.isBlank()) {
                continue;
            }
            String ownedBy = text(item.path("owned_by"));
            Integer contextLength = item.path("context_length").canConvertToInt() ? item.path("context_length").asInt() : null;
            boolean supportsReasoning = item.path("supports_reasoning").asBoolean(false);
            models.add(modelOption(provider, id, ownedBy, contextLength, supportsReasoning));
        }
        return models;
    }

    private AiModelListResponse.ModelOption modelOption(String provider, String id, String ownedBy,
                                                        Integer contextLength, boolean supportsReasoning) {
        String normalizedProvider = normalizeProvider(provider);
        String model = id.toLowerCase(Locale.ROOT);
        if ("deepseek".equals(normalizedProvider) || model.startsWith("deepseek-")) {
            boolean deprecated = "deepseek-chat".equals(model) || "deepseek-reasoner".equals(model);
            boolean supportsThinking = model.startsWith("deepseek-v4-") || deprecated;
            return new AiModelListResponse.ModelOption(
                    id,
                    ownedBy,
                    true,
                    supportsThinking,
                    supportsThinking ? List.of("high", "max") : List.of(),
                    null,
                    null,
                    contextLength,
                    deprecated
            );
        }
        if ("moonshot".equals(normalizedProvider) || "kimi".equals(normalizedProvider) || ownedBy.toLowerCase(Locale.ROOT).contains("moonshot")) {
            boolean kimiThinking = supportsReasoning || model.startsWith("kimi-k2.5") || model.startsWith("kimi-k2.6");
            return new AiModelListResponse.ModelOption(
                    id,
                    ownedBy,
                    true,
                    kimiThinking,
                    List.of(),
                    null,
                    kimiThinking ? 0.6 : null,
                    contextLength,
                    false
            );
        }
        return new AiModelListResponse.ModelOption(
                id,
                ownedBy,
                false,
                false,
                List.of(),
                null,
                null,
                contextLength,
                false
        );
    }

    private AiModelListResponse response(AiModelScope scope, String provider, String baseUrl,
                                         EnvironmentAiModelConfigResolver.ApiKeyResolution key,
                                         String status, String error, List<AiModelListResponse.ModelOption> models) {
        return new AiModelListResponse(
                scope.name(),
                provider,
                baseUrl == null ? "" : baseUrl.trim(),
                key != null && key.hasApiKey(),
                key == null || key.apiKeyEnvName() == null ? "" : key.apiKeyEnvName(),
                true,
                status,
                error,
                models
        );
    }

    private String deriveOpenAiModelsUrl(String baseUrl) {
        if (baseUrl == null || baseUrl.isBlank()) {
            return null;
        }
        String url = baseUrl.trim();
        while (url.endsWith("/")) {
            url = url.substring(0, url.length() - 1);
        }
        String lower = url.toLowerCase(Locale.ROOT);
        if (lower.endsWith("/chat/completions")) {
            return url.substring(0, url.length() - "/chat/completions".length()) + "/models";
        }
        if (lower.endsWith("/embeddings")) {
            return url.substring(0, url.length() - "/embeddings".length()) + "/models";
        }
        if (lower.endsWith("/v1")) {
            return url + "/models";
        }
        return url + "/models";
    }

    private String normalizeProvider(String provider) {
        return provider == null || provider.isBlank() ? "custom" : provider.trim().toLowerCase(Locale.ROOT);
    }

    private String text(JsonNode node) {
        return node != null && node.isTextual() ? node.asText().trim() : "";
    }

    private boolean containsIgnoreCase(String value, String needle) {
        return value != null && needle != null && value.toLowerCase(Locale.ROOT).contains(needle.toLowerCase(Locale.ROOT));
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
        return sanitized.length() <= 300 ? sanitized : sanitized.substring(0, 300);
    }
}
