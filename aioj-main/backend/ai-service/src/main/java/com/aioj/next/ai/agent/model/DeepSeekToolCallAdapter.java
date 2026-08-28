package com.aioj.next.ai.agent.model;

import com.aioj.next.ai.config.AiProperties;
import com.aioj.next.ai.domain.AiModelEffectiveConfig;
import com.aioj.next.common.error.DomainException;
import com.aioj.next.common.error.ErrorCode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;

import java.util.Map;

/**
 * DeepSeek tool-call adapter. Spike-verified facts (design doc §3.1):
 * thinking + tool_choice=required is rejected by DeepSeek (HTTP 400), so
 * REQUIRED is always simulated by the Agent Runtime for this provider.
 */
@Component
public class DeepSeekToolCallAdapter extends AbstractOpenAiToolCallAdapter {

    @Autowired
    public DeepSeekToolCallAdapter(ObjectMapper objectMapper, AiProperties properties) {
        super(objectMapper, properties);
    }

    DeepSeekToolCallAdapter(ObjectMapper objectMapper, RestClient restClient) {
        super(objectMapper, restClient);
    }

    @Override
    public String provider() {
        return "deepseek";
    }

    @Override
    public ProviderCapabilities capabilities() {
        return ProviderCapabilities.deepSeek();
    }

    @Override
    protected void customizeThinking(Map<String, Object> body, AiModelEffectiveConfig config, CallSettings settings) {
        body.put("thinking", Map.of("type", settings.thinkingEnabled() ? "enabled" : "disabled"));
        if (settings.thinkingEnabled()) {
            body.put("reasoning_effort", "max".equalsIgnoreCase(settings.reasoningEffort()) ? "max" : "high");
            // Thinking models do not accept a custom temperature (matches legacy client behavior).
            body.remove("temperature");
        }
    }

    @Override
    protected void customizeToolChoice(Map<String, Object> body, AiModelEffectiveConfig config, ToolChoiceMode mode) {
        if (mode == ToolChoiceMode.REQUIRED) {
            // DeepSeek returns HTTP 400 for thinking + tool_choice=required (spike); the
            // runtime simulates REQUIRED for this provider and must never send it.
            throw new DomainException(ErrorCode.BAD_REQUEST,
                    "DeepSeek does not support tool_choice=required here; Agent Runtime simulates REQUIRED");
        }
        body.put("tool_choice", "auto");
    }
}
