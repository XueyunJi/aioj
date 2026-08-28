package com.aioj.next.ai.agent.model;

import com.aioj.next.ai.config.AiProperties;
import com.aioj.next.ai.domain.AiModelEffectiveConfig;
import com.aioj.next.common.error.DomainException;
import com.aioj.next.common.error.ErrorCode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;

import java.util.Locale;
import java.util.Map;

/**
 * Kimi (Moonshot) tool-call adapter, per official docs (design doc §3.1):
 * OpenAI-compatible tools/tool_calls, no legacy `functions` param, function
 * names matching {@code ^[a-zA-Z_][a-zA-Z0-9-_]$} (handled by ToolNameCodec),
 * JSON-Schema subset with per-function strict (default true, sent explicitly),
 * K3 native tool_choice="required" and top-level reasoning_effort.
 */
@Component
public class KimiToolCallAdapter extends AbstractOpenAiToolCallAdapter {

    @Autowired
    public KimiToolCallAdapter(ObjectMapper objectMapper, AiProperties properties) {
        super(objectMapper, properties);
    }

    KimiToolCallAdapter(ObjectMapper objectMapper, RestClient restClient) {
        super(objectMapper, restClient);
    }

    @Override
    public String provider() {
        return "kimi";
    }

    @Override
    public ProviderCapabilities capabilities() {
        return ProviderCapabilities.kimi();
    }

    @Override
    protected void customizeFunction(Map<String, Object> function) {
        // Kimi defaults strict to true; send it explicitly so the contract is visible.
        function.put("strict", true);
    }

    @Override
    protected void customizeThinking(Map<String, Object> body, AiModelEffectiveConfig config, CallSettings settings) {
        String model = config.model() == null ? "" : config.model().toLowerCase(Locale.ROOT);
        boolean k25OrK26 = model.startsWith("kimi-k2.5") || model.startsWith("kimi-k2.6");
        boolean k3 = model.startsWith("kimi-k3");
        if (k25OrK26) {
            body.put("thinking", Map.of("type", settings.thinkingEnabled() ? "enabled" : "disabled"));
        }
        if (k3) {
            body.put("reasoning_effort", "max".equalsIgnoreCase(settings.reasoningEffort()) ? "max" : "high");
        }
        // Thinking-mode models (k2.5/k2.6 with thinking on; k3 is thinking-only) accept temperature=1 only.
        boolean thinkingMode = k3 || (k25OrK26 && settings.thinkingEnabled());
        body.put("temperature", thinkingMode ? 1.0 : 0.6);
    }

    @Override
    protected void customizeToolChoice(Map<String, Object> body, AiModelEffectiveConfig config, ToolChoiceMode mode) {
        if (mode == ToolChoiceMode.REQUIRED) {
            String model = config.model() == null ? "" : config.model().toLowerCase(Locale.ROOT);
            if (!model.startsWith("kimi-k3")) {
                // Native required is documented for K3; other Kimi models fall back to runtime simulation.
                throw new DomainException(ErrorCode.BAD_REQUEST,
                        "Kimi model " + config.model() + " does not support tool_choice=required; Agent Runtime simulates REQUIRED");
            }
            body.put("tool_choice", "required");
            return;
        }
        body.put("tool_choice", "auto");
    }
}
