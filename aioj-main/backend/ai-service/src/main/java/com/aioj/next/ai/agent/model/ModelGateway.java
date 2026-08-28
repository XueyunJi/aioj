package com.aioj.next.ai.agent.model;

import com.aioj.next.ai.domain.AiModelConfigService;
import com.aioj.next.ai.domain.AiModelEffectiveConfig;
import com.aioj.next.ai.domain.AiModelScope;
import com.aioj.next.common.error.DomainException;
import com.aioj.next.common.error.ErrorCode;
import org.springframework.stereotype.Service;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * Single entry point for every model call in the agent pipeline (design doc §3).
 * Applies CallProfile safety defaults, enforces the provider capability matrix
 * (illegal combinations are explicit errors, never silent downgrades), and
 * resolves the effective config from the DB-backed config service only.
 */
@Service
public class ModelGateway {

    private final AiModelConfigService configService;
    private final Map<String, ToolCallAdapter> adapters;

    public ModelGateway(AiModelConfigService configService, List<ToolCallAdapter> adapterList) {
        this.configService = configService;
        Map<String, ToolCallAdapter> byProvider = new LinkedHashMap<>();
        for (ToolCallAdapter adapter : adapterList) {
            byProvider.put(adapter.provider(), adapter);
        }
        this.adapters = Map.copyOf(byProvider);
    }

    /** Effective chat model config (DB first; env fallback is WARN-logged by the config service). */
    public AiModelEffectiveConfig chatConfig() {
        return configService.effectiveConfig(AiModelScope.TEXT_GENERATION);
    }

    /** Effective config for any scope (e.g. AGENT_CURATOR); DB-backed, with env fallback WARN. */
    public AiModelEffectiveConfig configFor(AiModelScope scope) {
        return configService.effectiveConfig(scope);
    }

    public ProviderCapabilities capabilities(AiModelEffectiveConfig config) {
        return adapterFor(config).capabilities();
    }

    public GatewayResponse call(AiModelEffectiveConfig config, GatewayRequest request) {
        ToolCallAdapter adapter = adapterFor(config);
        if (request.toolChoice() == ToolChoiceMode.REQUIRED && !adapter.capabilities().toolChoiceRequiredNative()) {
            throw new DomainException(ErrorCode.BAD_REQUEST,
                    "Provider " + adapter.provider() + " has no native tool_choice=required; Agent Runtime must simulate it");
        }
        return adapter.execute(config, resolveSettings(config, request.profile()), request);
    }

    private CallSettings resolveSettings(AiModelEffectiveConfig config, CallProfile profile) {
        boolean thinking = config.thinkingEnabled() && !profile.forceThinkingDisabled();
        int maxTokens = config.maxTokensOr(profile.defaultMaxTokens());
        if (thinking) {
            maxTokens = Math.max(maxTokens, CallProfile.MIN_MAX_TOKENS_WHEN_THINKING);
        }
        double temperature = config.temperatureOr(profile.defaultTemperature());
        return new CallSettings(thinking, config.reasoningEffort(), temperature, maxTokens);
    }

    private ToolCallAdapter adapterFor(AiModelEffectiveConfig config) {
        String hint = ((config.provider() == null ? "" : config.provider()) + " "
                + (config.model() == null ? "" : config.model()) + " "
                + (config.baseUrl() == null ? "" : config.baseUrl())).toLowerCase(Locale.ROOT);
        ToolCallAdapter adapter = null;
        if (hint.contains("deepseek")) {
            adapter = adapters.get("deepseek");
        } else if (hint.contains("kimi") || hint.contains("moonshot")) {
            adapter = adapters.get("kimi");
        }
        if (adapter == null) {
            throw new DomainException(ErrorCode.BAD_REQUEST,
                    "No agent tool-call adapter for provider of model " + config.model());
        }
        return adapter;
    }
}
