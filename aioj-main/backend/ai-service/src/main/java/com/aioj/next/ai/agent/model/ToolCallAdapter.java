package com.aioj.next.ai.agent.model;

import com.aioj.next.ai.domain.AiModelEffectiveConfig;

/** Provider tool-call adapter SPI (design doc §3). One implementation per provider. */
public interface ToolCallAdapter {

    String provider();

    ProviderCapabilities capabilities();

    GatewayResponse execute(AiModelEffectiveConfig config, CallSettings settings, GatewayRequest request);
}
