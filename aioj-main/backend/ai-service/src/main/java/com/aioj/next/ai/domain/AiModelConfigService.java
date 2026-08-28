package com.aioj.next.ai.domain;

import com.aioj.next.ai.config.AiProperties;
import com.aioj.next.ai.persistence.entity.AiModelConfigEntity;
import com.aioj.next.ai.persistence.mapper.AiModelConfigMapper;
import com.aioj.next.common.error.DomainException;
import com.aioj.next.common.error.ErrorCode;
import com.aioj.next.contract.ai.AiModelConfigResponse;
import com.aioj.next.contract.ai.AiModelConfigTestRequest;
import com.aioj.next.contract.ai.AiModelConfigTestResponse;
import com.aioj.next.contract.ai.AiModelConfigUpdateRequest;
import com.aioj.next.contract.ai.AiModelListResponse;
import com.baomidou.mybatisplus.core.conditions.query.QueryWrapper;
import com.baomidou.mybatisplus.core.toolkit.IdWorker;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.util.Arrays;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.Function;

@Service
public class AiModelConfigService implements AiModelConfigResolver {
    private static final Logger log = LoggerFactory.getLogger(AiModelConfigService.class);
    private static final long CACHE_TTL_MILLIS = 30_000;
    private static final ZoneId ZONE = ZoneId.systemDefault();

    private final AiModelConfigMapper mapper;
    private final AiModelCompletionClient completionClient;
    private final AiModelCatalogClient modelCatalogClient;
    private final AiQuotaService aiQuotaService;
    private final EnvironmentAiModelConfigResolver environmentResolver;
    private final Map<AiModelScope, CacheEntry> cache = new ConcurrentHashMap<>();
    /** Agent Core V3 rule: env takeover of the chat model is WARN-logged once per JVM, never silent. */
    private final AtomicBoolean envFallbackWarned = new AtomicBoolean(false);

    @Autowired
    public AiModelConfigService(
            AiProperties properties,
            AiModelConfigMapper mapper,
            AiModelCompletionClient completionClient,
            AiModelCatalogClient modelCatalogClient,
            AiQuotaService aiQuotaService
    ) {
        this(properties, mapper, completionClient, modelCatalogClient, aiQuotaService, System::getenv);
    }

    AiModelConfigService(
            AiProperties properties,
            AiModelConfigMapper mapper,
            AiModelCompletionClient completionClient,
            AiModelCatalogClient modelCatalogClient,
            AiQuotaService aiQuotaService,
            Function<String, String> envLookup
    ) {
        this.mapper = mapper;
        this.completionClient = completionClient;
        this.modelCatalogClient = modelCatalogClient;
        this.aiQuotaService = aiQuotaService;
        this.environmentResolver = new EnvironmentAiModelConfigResolver(properties, envLookup);
    }

    @Override
    public AiModelEffectiveConfig effectiveConfig(AiModelScope scope) {
        AiModelScope normalized = scope == null ? AiModelScope.TEXT_GENERATION : scope;
        long now = System.currentTimeMillis();
        CacheEntry cached = cache.get(normalized);
        if (cached != null && cached.expiresAtMillis() > now) {
            return cached.config();
        }
        AiModelEffectiveConfig config = loadEffectiveConfig(normalized);
        if (normalized == AiModelScope.TEXT_GENERATION && "ENVIRONMENT".equals(config.source())
                && envFallbackWarned.compareAndSet(false, true)) {
            log.warn("AI chat model config is resolved from environment (no enabled DATABASE row): provider={} model={} baseUrl={}. "
                    + "Agent Core V3 expects the DB-backed config service as the source of truth; configure it via admin model config.",
                    config.provider(), config.model(), config.baseUrl());
        }
        cache.put(normalized, new CacheEntry(config, now + CACHE_TTL_MILLIS));
        return config;
    }

    public List<AiModelConfigResponse> listConfigs() {
        return Arrays.stream(AiModelScope.values())
                .map(scope -> toResponse(loadDisplayConfig(scope)))
                .toList();
    }

    public AiModelListResponse listModels(String scopeName, String provider, String baseUrl) {
        AiModelScope scope = parseScope(scopeName);
        AiModelEffectiveConfig current = loadDisplayConfig(scope);
        String effectiveProvider = providerForScope(scope, textOr(provider, current.provider()));
        String effectiveBaseUrl = normalizeTextBaseUrl(scope, effectiveProvider, textOr(baseUrl, current.baseUrl()));
        EnvironmentAiModelConfigResolver.ApiKeyResolution key = environmentResolver.apiKeyFor(scope, effectiveProvider);
        return modelCatalogClient.listModels(scope, effectiveProvider, effectiveBaseUrl, key);
    }

    @Transactional
    public AiModelConfigResponse updateConfig(String scopeName, AiModelConfigUpdateRequest request, Long updatedBy) {
        AiModelScope scope = parseScope(scopeName);
        AiModelConfigEntity existing = select(scope);
        AiModelEffectiveConfig base = existing != null && Boolean.TRUE.equals(existing.getEnabled())
                ? fromDatabase(scope, existing, false)
                : fallbackFor(scope, existing != null);
        AiModelConfigEntity entity = existing == null ? new AiModelConfigEntity() : existing;
        LocalDateTime now = LocalDateTime.now();
        if (entity.getId() == null) {
            entity.setId(IdWorker.getId());
            entity.setScope(scope.name());
            entity.setCreatedAt(now);
        }
        boolean enabled = scope == AiModelScope.TEXT_GENERATION || request == null || request.enabled() == null
                ? true
                : request.enabled();
        entity.setEnabled(enabled);
        String provider = providerForScope(scope, textOr(request == null ? null : request.provider(), base.provider()));
        String baseUrl = required("baseUrl", normalizeTextBaseUrl(scope, provider, textOr(request == null ? null : request.baseUrl(), base.baseUrl())));
        String model = required("model", textOr(request == null ? null : request.model(), base.model()));
        validateTextModel(scope, provider, model, baseUrl);
        entity.setProvider(provider);
        entity.setBaseUrl(baseUrl);
        entity.setModel(model);
        entity.setJsonOutputEnabled(request == null || request.jsonOutputEnabled() == null ? base.jsonOutputEnabled() : request.jsonOutputEnabled());
        entity.setThinkingEnabled(request == null || request.thinkingEnabled() == null ? base.thinkingEnabled() : request.thinkingEnabled());
        entity.setReasoningEffort(reasoningEffort(request == null ? null : request.reasoningEffort(), base.reasoningEffort()));
        entity.setTemperature(temperature(request == null ? null : request.temperature(), base.temperature()));
        entity.setMaxTokens(maxTokens(request == null ? null : request.maxTokens(), base.maxTokens()));
        entity.setEmbeddingDimension(embeddingDimension(request == null ? null : request.embeddingDimension(), base.embeddingDimension()));
        applyKeyAction(entity, request);
        entity.setUpdatedAt(now);
        entity.setUpdatedBy(updatedBy);
        if (existing == null) {
            mapper.insert(entity);
        } else {
            mapper.updateById(entity);
        }
        cache.clear();
        return toResponse(loadDisplayConfig(scope));
    }

    public AiModelConfigTestResponse testConfig(String scopeName, AiModelConfigTestRequest request, Long userId) {
        AiModelScope scope = parseScope(scopeName);
        AiModelEffectiveConfig config = temporaryConfig(scope, request);
        long start = System.nanoTime();
        try {
            if (!config.enabled()) {
                aiQuotaService.record(userId, config.provider(), config.model(), 0, 0, false);
                return failure(config, elapsedMillis(start), 0, 0, "AI model configuration is disabled");
            }
            if (!config.hasApiKey()) {
                aiQuotaService.record(userId, config.provider(), config.model(), 0, 0, false);
                return failure(config, elapsedMillis(start), 0, 0, "AI API key is not configured");
            }
            boolean strictJsonTest = scope != AiModelScope.EMBEDDING && config.jsonOutputEnabled();
            String prompt = request == null || request.prompt() == null || request.prompt().isBlank()
                    ? defaultTestPrompt(strictJsonTest)
                    : request.prompt().trim();
            if (scope == AiModelScope.EMBEDDING) {
                // Embeddings endpoints reject chat completion payloads; probe with a real
                // embeddings request instead.
                AiModelCompletionClient.EmbeddingProbeResult probe = completionClient.embedProbe(config, prompt);
                long elapsed = elapsedMillis(start);
                aiQuotaService.record(userId, config.provider(), config.model(), probe.promptTokens(), 0, true);
                return new AiModelConfigTestResponse(true, config.provider(), config.model(), elapsed,
                        probe.promptTokens(), 0, "Embedding OK, vector dimension " + probe.dimension(), null);
            }
            AiModelCompletionClient.CompletionResult result = completionClient.complete(
                    config,
                    List.of(
                            message("system", strictJsonTest ? "Return a strict JSON object only." : "Reply briefly for an AI model connectivity test."),
                            message("user", prompt)
                    ),
                    config.temperatureOr(strictJsonTest ? 0.1 : 0.2),
                    config.maxTokensOr(512),
                    strictJsonTest
            );
            long elapsed = elapsedMillis(start);
            aiQuotaService.record(userId, result.provider(), result.model(), result.promptTokens(), result.completionTokens(), true);
            return new AiModelConfigTestResponse(true, result.provider(), result.model(), elapsed,
                    result.promptTokens(), result.completionTokens(), preview(result.content(), 500), null);
        } catch (RuntimeException ex) {
            long elapsed = elapsedMillis(start);
            aiQuotaService.record(userId, config.provider(), config.model(), 0, 0, false);
            return failure(config, elapsed, 0, 0, sanitizeError(ex.getMessage(), config.apiKey()));
        }
    }

    private AiModelEffectiveConfig temporaryConfig(AiModelScope scope, AiModelConfigTestRequest request) {
        AiModelEffectiveConfig current = loadDisplayConfig(scope);
        if (request == null) {
            return current;
        }
        if (scope != AiModelScope.TEXT_GENERATION && scope != AiModelScope.EMBEDDING && Boolean.FALSE.equals(request.enabled())) {
            return effectiveConfig(AiModelScope.TEXT_GENERATION).withScope(scope, true, "TEXT_GENERATION");
        }
        String action = keyAction(request.apiKeyAction());
        if ("REPLACE".equals(action)) {
            throw new DomainException(ErrorCode.BAD_REQUEST, "API Key must be configured through server environment variables");
        }
        String provider = providerForScope(scope, textOr(request.provider(), current.provider()));
        String baseUrl = normalizeTextBaseUrl(scope, provider, textOr(request.baseUrl(), current.baseUrl()));
        String model = textOr(request.model(), current.model());
        validateTextModel(scope, provider, model, baseUrl);
        EnvironmentAiModelConfigResolver.ApiKeyResolution key = environmentResolver.apiKeyFor(scope, provider);
        if ("CLEAR".equals(action)) {
            key = environmentResolver.apiKeyFor(scope, provider);
        }
        return current.withOverrides(
                request.enabled(),
                provider,
                baseUrl,
                key.apiKey(),
                key.apiKeyPreview(),
                key.apiKeySource(),
                key.apiKeyEnvName(),
                model,
                request.jsonOutputEnabled(),
                request.thinkingEnabled(),
                reasoningEffort(request.reasoningEffort(), current.reasoningEffort()),
                temperature(request.temperature(), current.temperature()),
                maxTokens(request.maxTokens(), current.maxTokens()),
                embeddingDimension(request.embeddingDimension(), current.embeddingDimension())
        );
    }

    private AiModelEffectiveConfig loadEffectiveConfig(AiModelScope scope) {
        AiModelConfigEntity entity = select(scope);
        if (entity != null && Boolean.TRUE.equals(entity.getEnabled())) {
            return fromDatabase(scope, entity, true);
        }
        return fallbackFor(scope, entity != null);
    }

    private AiModelEffectiveConfig loadDisplayConfig(AiModelScope scope) {
        AiModelConfigEntity entity = select(scope);
        if (entity != null && Boolean.TRUE.equals(entity.getEnabled())) {
            return fromDatabase(scope, entity, false);
        }
        return fallbackFor(scope, entity != null);
    }

    private AiModelEffectiveConfig fallbackFor(AiModelScope scope, boolean adminConfigured) {
        return switch (scope) {
            case TEXT_GENERATION -> environmentResolver.effectiveConfig(AiModelScope.TEXT_GENERATION);
            case MEMORY_EXTRACTION, REPORT_ANALYSIS, PROBLEM_DRAFT, ACCOUNT_IMPORT_PARSE, AGENT_CURATOR ->
                    effectiveConfig(AiModelScope.TEXT_GENERATION).withScope(scope, true, "TEXT_GENERATION");
            case INTENT -> adminConfigured
                    ? effectiveConfig(AiModelScope.TEXT_GENERATION).withScope(scope, true, "TEXT_GENERATION")
                    : environmentResolver.effectiveConfig(AiModelScope.INTENT);
            case EMBEDDING -> environmentResolver.effectiveConfig(AiModelScope.EMBEDDING);
        };
    }

    private AiModelEffectiveConfig fromDatabase(AiModelScope scope, AiModelConfigEntity entity, boolean fallbackWhenMissingKey) {
        AiModelEffectiveConfig fallback = fallbackFor(scope, true);
        String provider = storedProviderForScope(scope, entity.getProvider());
        if (provider.isBlank()) {
            return fallbackFor(scope, true);
        }
        String baseUrl = normalizeTextBaseUrl(scope, provider, textOr(entity.getBaseUrl(), fallback.baseUrl()));
        String model = textOr(entity.getModel(), fallback.model());
        if (isTextScope(scope) && invalidTextModel(provider, model, baseUrl)) {
            return fallbackFor(scope, true);
        }
        EnvironmentAiModelConfigResolver.ApiKeyResolution key = environmentResolver.apiKeyFor(scope, provider);
        if (fallbackWhenMissingKey && !key.hasApiKey() && shouldFallbackToDefaultText(scope)) {
            return fallbackFor(scope, true);
        }
        return new AiModelEffectiveConfig(
                scope,
                true,
                false,
                "DATABASE",
                provider,
                baseUrl,
                key.apiKey(),
                key.apiKeyPreview(),
                key.apiKeySource(),
                key.apiKeyEnvName(),
                model,
                entity.getJsonOutputEnabled() == null ? fallback.jsonOutputEnabled() : entity.getJsonOutputEnabled(),
                entity.getThinkingEnabled() == null ? fallback.thinkingEnabled() : entity.getThinkingEnabled(),
                reasoningEffort(entity.getReasoningEffort(), fallback.reasoningEffort()),
                temperature(entity.getTemperature(), fallback.temperature()),
                maxTokens(entity.getMaxTokens(), fallback.maxTokens()),
                embeddingDimension(entity.getEmbeddingDimension(), fallback.embeddingDimension()),
                toInstant(entity.getUpdatedAt()),
                entity.getUpdatedBy()
        );
    }

    private boolean shouldFallbackToDefaultText(AiModelScope scope) {
        return scope == AiModelScope.MEMORY_EXTRACTION
                || scope == AiModelScope.REPORT_ANALYSIS
                || scope == AiModelScope.PROBLEM_DRAFT
                || scope == AiModelScope.ACCOUNT_IMPORT_PARSE
                || scope == AiModelScope.AGENT_CURATOR
                || scope == AiModelScope.INTENT;
    }

    private String providerForScope(AiModelScope scope, String provider) {
        if (!isTextScope(scope)) {
            return required("provider", provider);
        }
        String canonical = environmentResolver.canonicalTextProvider(provider);
        if (canonical.isBlank()) {
            throw new DomainException(ErrorCode.BAD_REQUEST, "Text AI API must be DeepSeek or Kimi");
        }
        return canonical;
    }

    private String storedProviderForScope(AiModelScope scope, String provider) {
        if (!isTextScope(scope)) {
            return textOr(provider, "");
        }
        return environmentResolver.canonicalTextProvider(provider);
    }

    private boolean isTextScope(AiModelScope scope) {
        return scope != AiModelScope.EMBEDDING;
    }

    private void validateTextModel(AiModelScope scope, String provider, String model, String baseUrl) {
        if (!isTextScope(scope) || !invalidTextModel(provider, model, baseUrl)) {
            return;
        }
        throw new DomainException(ErrorCode.BAD_REQUEST, "Text AI API and model/base URL do not match");
    }

    private boolean invalidTextModel(String provider, String model, String baseUrl) {
        String normalizedProvider = provider == null ? "" : provider.trim().toLowerCase(Locale.ROOT);
        String normalizedModel = model == null ? "" : model.trim().toLowerCase(Locale.ROOT);
        String normalizedBaseUrl = baseUrl == null ? "" : baseUrl.trim().toLowerCase(Locale.ROOT);
        if ("deepseek".equals(normalizedProvider)) {
            return normalizedModel.startsWith("kimi-")
                    || normalizedModel.startsWith("moonshot-")
                    || normalizedBaseUrl.contains("moonshot")
                    || normalizedBaseUrl.contains("kimi");
        }
        if ("kimi".equals(normalizedProvider)) {
            return normalizedModel.startsWith("deepseek-")
                    || normalizedBaseUrl.contains("deepseek");
        }
        return true;
    }

    private String normalizeTextBaseUrl(AiModelScope scope, String provider, String baseUrl) {
        String current = textOr(baseUrl, "");
        if (isTextScope(scope) && "kimi".equals(provider)) {
            String normalized = current.toLowerCase(Locale.ROOT);
            if (normalized.isBlank() || normalized.contains("api.moonshot.ai")) {
                return "https://api.moonshot.cn/v1/chat/completions";
            }
        }
        return current;
    }

    private AiModelConfigEntity select(AiModelScope scope) {
        return mapper.selectOne(new QueryWrapper<AiModelConfigEntity>()
                .eq("scope", scope.name())
                .last("LIMIT 1"));
    }

    private void applyKeyAction(AiModelConfigEntity entity, AiModelConfigUpdateRequest request) {
        String action = keyAction(request == null ? null : request.apiKeyAction());
        if ("REPLACE".equals(action)) {
            throw new DomainException(ErrorCode.BAD_REQUEST, "API Key must be configured through server environment variables");
        }
        entity.setApiKeyEncrypted(null);
        entity.setApiKeyPreview(null);
    }

    private AiModelConfigResponse toResponse(AiModelEffectiveConfig config) {
        return new AiModelConfigResponse(
                config.scope().name(),
                config.enabled(),
                config.inherited(),
                config.source(),
                config.provider(),
                config.baseUrl(),
                config.model(),
                config.jsonOutputEnabled(),
                config.thinkingEnabled(),
                config.reasoningEffort(),
                config.temperature(),
                config.maxTokens(),
                config.embeddingDimension(),
                config.hasApiKey(),
                config.apiKeyPreview() == null ? "" : config.apiKeyPreview(),
                config.apiKeySource(),
                config.apiKeyEnvName(),
                config.updatedAt(),
                config.updatedBy()
        );
    }

    private AiModelScope parseScope(String scopeName) {
        try {
            return AiModelScope.from(scopeName);
        } catch (IllegalArgumentException ex) {
            throw new DomainException(ErrorCode.BAD_REQUEST, ex.getMessage());
        }
    }

    private String keyAction(String value) {
        if (value == null || value.isBlank()) {
            return "KEEP";
        }
        String normalized = value.trim().toUpperCase(Locale.ROOT);
        if (!List.of("KEEP", "REPLACE", "CLEAR").contains(normalized)) {
            throw new DomainException(ErrorCode.BAD_REQUEST, "Unsupported apiKeyAction");
        }
        return normalized;
    }

    private String required(String field, String value) {
        if (value == null || value.isBlank()) {
            throw new DomainException(ErrorCode.BAD_REQUEST, field + " is required");
        }
        return value.trim();
    }

    private String textOr(String value, String fallback) {
        return value == null || value.isBlank() ? fallback : value.trim();
    }

    private String reasoningEffort(String value, String fallback) {
        String normalized = textOr(value, fallback == null ? "high" : fallback).toLowerCase(Locale.ROOT);
        return "max".equals(normalized) ? "max" : "high";
    }

    private Double temperature(Double value, Double fallback) {
        Double next = value == null ? fallback : value;
        if (next != null && (next < 0 || next > 2)) {
            throw new DomainException(ErrorCode.BAD_REQUEST, "temperature must be between 0 and 2");
        }
        return next;
    }

    private Integer maxTokens(Integer value, Integer fallback) {
        Integer next = value == null ? fallback : value;
        if (next != null && next <= 0) {
            throw new DomainException(ErrorCode.BAD_REQUEST, "maxTokens must be positive");
        }
        return next;
    }

    private Integer embeddingDimension(Integer value, Integer fallback) {
        Integer next = value == null ? fallback : value;
        if (next != null && next <= 0) {
            throw new DomainException(ErrorCode.BAD_REQUEST, "embeddingDimension must be positive");
        }
        return next;
    }

    private Instant toInstant(LocalDateTime value) {
        return value == null ? null : value.atZone(ZONE).toInstant();
    }

    private Map<String, String> message(String role, String content) {
        return Map.of("role", role, "content", content);
    }

    private String defaultTestPrompt(boolean strictJsonTest) {
        return strictJsonTest ? "Return {\"ok\":true,\"source\":\"ai-model-config-test\"}." : "Reply with a short connectivity confirmation.";
    }

    private AiModelConfigTestResponse failure(AiModelEffectiveConfig config, long latencyMillis,
                                              long promptTokens, long completionTokens, String errorMessage) {
        return new AiModelConfigTestResponse(false, config.provider(), config.model(), latencyMillis,
                promptTokens, completionTokens, null, preview(errorMessage, 300));
    }

    private long elapsedMillis(long startNanos) {
        return Math.max(0, (System.nanoTime() - startNanos) / 1_000_000);
    }

    private String preview(String value, int maxLength) {
        if (value == null || value.isBlank()) {
            return "";
        }
        String normalized = value.trim();
        return normalized.length() <= maxLength ? normalized : normalized.substring(0, maxLength);
    }

    private String sanitizeError(String message, String apiKey) {
        String sanitized = message == null || message.isBlank() ? "AI provider test failed" : message;
        if (apiKey != null && !apiKey.isBlank()) {
            sanitized = sanitized.replace(apiKey, "***");
        }
        sanitized = sanitized.replaceAll("(?i)(api[_-]?key|token|secret|password)\\s*(?:=|:|是|为)\\s*[^\\s,;，。]+", "$1=***");
        sanitized = sanitized.replaceAll("(?i)(your\\s+api\\s+key\\s*:\\s*)\\*+[^\\s\\\"',;，。}]+", "$1***");
        sanitized = sanitized.replaceAll("(?i)(api\\s+key\\s*:\\s*)\\*+[^\\s\\\"',;，。}]+", "$1***");
        return preview(sanitized, 300);
    }

    private record CacheEntry(AiModelEffectiveConfig config, long expiresAtMillis) {
    }
}
