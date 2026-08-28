package com.aioj.next.ai.domain;

import com.aioj.next.ai.config.AiProperties;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.function.Function;

final class EnvironmentAiModelConfigResolver implements AiModelConfigResolver {
    private final AiProperties properties;
    private final Function<String, String> envLookup;

    EnvironmentAiModelConfigResolver(AiProperties properties) {
        this(properties, System::getenv);
    }

    EnvironmentAiModelConfigResolver(AiProperties properties, Function<String, String> envLookup) {
        this.properties = properties;
        this.envLookup = envLookup;
    }

    @Override
    public AiModelEffectiveConfig effectiveConfig(AiModelScope scope) {
        return switch (scope) {
            case TEXT_GENERATION -> textConfig();
            case MEMORY_EXTRACTION -> textConfig().withScope(AiModelScope.MEMORY_EXTRACTION, true, "TEXT_GENERATION");
            case REPORT_ANALYSIS -> textConfig().withScope(AiModelScope.REPORT_ANALYSIS, true, "TEXT_GENERATION");
            case PROBLEM_DRAFT -> textConfig().withScope(AiModelScope.PROBLEM_DRAFT, true, "TEXT_GENERATION");
            case ACCOUNT_IMPORT_PARSE -> textConfig().withScope(AiModelScope.ACCOUNT_IMPORT_PARSE, true, "TEXT_GENERATION");
            case AGENT_CURATOR -> textConfig().withScope(AiModelScope.AGENT_CURATOR, true, "TEXT_GENERATION");
            case INTENT -> intentConfig();
            case EMBEDDING -> embeddingConfig();
        };
    }

    ApiKeyResolution apiKeyFor(AiModelScope scope, String provider) {
        String normalizedProvider = normalizeProvider(provider);
        if (scope == AiModelScope.EMBEDDING) {
            List<EnvCandidate> candidates = new ArrayList<>();
            candidates.add(envCandidate("AI_EMBEDDING_API_KEY"));
            for (String prefix : providerPrefixes(normalizedProvider)) {
                candidates.add(envCandidate(prefix + "_EMBEDDING_API_KEY"));
            }
            candidates.add(envCandidate("DASHSCOPE_API_KEY"));
            AiProperties.Embedding embedding = properties.getEmbedding();
            if (embedding != null) {
                candidates.add(new EnvCandidate("AI_EMBEDDING_API_KEY", embedding.getApiKey()));
            }
            return resolution(candidates, "AI_EMBEDDING_API_KEY");
        }

        String textProvider = canonicalTextProvider(normalizedProvider);
        if ("deepseek".equals(textProvider)) {
            List<EnvCandidate> candidates = new ArrayList<>();
            candidates.add(envCandidate("DEEPSEEK_API_KEY"));
            if (sameProvider(normalizedProvider, properties.getProvider())) {
                candidates.add(new EnvCandidate("DEEPSEEK_API_KEY", properties.getApiKey()));
            }
            return resolution(candidates, "DEEPSEEK_API_KEY");
        }
        if ("kimi".equals(textProvider)) {
            List<EnvCandidate> candidates = new ArrayList<>();
            candidates.add(envCandidate("KIMI_API_KEY"));
            return resolution(candidates, "KIMI_API_KEY");
        }
        return new ApiKeyResolution("", "", "NONE", "");
    }

    private AiModelEffectiveConfig textConfig() {
        AiProperties.DeepSeek deepSeek = properties.getDeepseek();
        String provider = canonicalTextProvider(defaultString(properties.getProvider(), "deepseek"));
        ApiKeyResolution key = apiKeyFor(AiModelScope.TEXT_GENERATION, provider);
        return new AiModelEffectiveConfig(
                AiModelScope.TEXT_GENERATION,
                true,
                false,
                "ENVIRONMENT",
                provider,
                defaultString(properties.getBaseUrl(), "https://api.deepseek.com/chat/completions"),
                key.apiKey(),
                key.apiKeyPreview(),
                key.apiKeySource(),
                key.apiKeyEnvName(),
                defaultString(properties.getModel(), "deepseek-v4-pro"),
                true,
                deepSeek != null && deepSeek.isThinkingEnabled(),
                reasoningEffort(deepSeek == null ? null : deepSeek.getReasoningEffort()),
                null,
                null,
                null,
                null,
                null
        );
    }

    private AiModelEffectiveConfig intentConfig() {
        AiModelEffectiveConfig text = textConfig();
        AiProperties.Intent intent = properties.getIntent();
        if (intent == null || !intent.isEnabled()) {
            return text.withScope(AiModelScope.INTENT, true, "TEXT_GENERATION")
                    .withOverrides(false, null, null, null, null, null, null, null, null, null, null, null, null, null);
        }
        String provider = text.provider();
        String baseUrl = firstNonBlank(intent.getBaseUrl(), text.baseUrl());
        String model = firstNonBlank(intent.getModel(), text.model());
        ApiKeyResolution key = apiKeyFor(AiModelScope.INTENT, provider);
        return new AiModelEffectiveConfig(
                AiModelScope.INTENT,
                true,
                false,
                "ENVIRONMENT",
                provider,
                baseUrl,
                key.apiKey(),
                key.apiKeyPreview(),
                key.apiKeySource(),
                key.apiKeyEnvName(),
                model,
                true,
                text.thinkingEnabled(),
                text.reasoningEffort(),
                null,
                null,
                null,
                null,
                null
        );
    }

    private AiModelEffectiveConfig embeddingConfig() {
        AiProperties.Embedding embedding = properties.getEmbedding();
        String provider = "dashscope";
        ApiKeyResolution key = apiKeyFor(AiModelScope.EMBEDDING, provider);
        String baseUrl = embedding == null ? "" : defaultString(embedding.getBaseUrl(), "");
        String model = embedding == null ? "" : defaultString(embedding.getModel(), "");
        return new AiModelEffectiveConfig(
                AiModelScope.EMBEDDING,
                embedding != null && embedding.isEnabled(),
                false,
                "ENVIRONMENT",
                provider,
                baseUrl,
                key.apiKey(),
                key.apiKeyPreview(),
                key.apiKeySource(),
                key.apiKeyEnvName(),
                model,
                false,
                false,
                "high",
                null,
                null,
                embedding == null ? null : embedding.getDimension(),
                null,
                null
        );
    }

    private ApiKeyResolution resolution(List<EnvCandidate> candidates, String fallbackEnvName) {
        for (EnvCandidate candidate : candidates) {
            if (hasText(candidate.apiKey())) {
                return new ApiKeyResolution(candidate.apiKey().trim(), "environment", "ENVIRONMENT", candidate.envName());
            }
        }
        return new ApiKeyResolution("", "", "NONE", defaultString(fallbackEnvName, ""));
    }

    private EnvCandidate envCandidate(String name) {
        String value = envLookup.apply(name);
        return new EnvCandidate(name, hasText(value) ? value.trim() : "");
    }

    private List<String> providerPrefixes(String provider) {
        String normalized = normalizeProvider(provider);
        if ("deepseek".equals(normalized)) {
            return List.of("DEEPSEEK");
        }
        if ("moonshot".equals(normalized) || "kimi".equals(normalized)) {
            return List.of("MOONSHOT", "KIMI");
        }
        if ("dashscope".equals(normalized) || "qwen".equals(normalized) || "alibaba".equals(normalized)) {
            return List.of("DASHSCOPE", "QWEN", "ALIBABA");
        }
        String custom = normalized.replaceAll("[^a-z0-9]+", "_").toUpperCase(Locale.ROOT);
        return hasText(custom) ? List.of(custom) : List.of();
    }

    private boolean sameProvider(String left, String right) {
        return canonicalTextProvider(left).equals(canonicalTextProvider(right));
    }

    String canonicalTextProvider(String provider) {
        String normalized = normalizeProvider(provider);
        if ("moonshot".equals(normalized) || "kimi".equals(normalized)) {
            return "kimi";
        }
        if ("deepseek".equals(normalized)) {
            return "deepseek";
        }
        return "";
    }

    private String normalizeProvider(String provider) {
        return defaultString(provider, "deepseek").toLowerCase(Locale.ROOT);
    }

    private String firstNonBlank(String first, String second) {
        return hasText(first) ? first.trim() : defaultString(second, "");
    }

    private String defaultString(String value, String fallback) {
        return value == null || value.isBlank() ? fallback : value.trim();
    }

    private boolean hasText(String value) {
        return value != null && !value.isBlank();
    }

    private String reasoningEffort(String value) {
        return "max".equalsIgnoreCase(value) ? "max" : "high";
    }

    record ApiKeyResolution(String apiKey, String apiKeyPreview, String apiKeySource, String apiKeyEnvName) {
        boolean hasApiKey() {
            return apiKey != null && !apiKey.isBlank();
        }
    }

    private record EnvCandidate(String envName, String apiKey) {
    }
}
