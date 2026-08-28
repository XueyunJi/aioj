package com.aioj.next.ai.agent.model;

import com.aioj.next.ai.domain.AiModelConfigService;
import com.aioj.next.ai.domain.AiModelEffectiveConfig;
import com.aioj.next.ai.domain.AiModelScope;
import com.aioj.next.common.error.DomainException;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class ModelGatewayTest {

    private final AiModelConfigService configService = mock(AiModelConfigService.class);

    @Test
    void requiredToolChoiceWithoutNativeSupportIsExplicitError() {
        when(configService.effectiveConfig(AiModelScope.TEXT_GENERATION)).thenReturn(config("deepseek", "deepseek-v4-pro"));
        ModelGateway gateway = new ModelGateway(configService, List.of(new RecordingAdapter("deepseek", ProviderCapabilities.deepSeek())));
        GatewayRequest request = new GatewayRequest(List.of(GatewayMessage.user("hi")), List.of(),
                ToolChoiceMode.REQUIRED, CallProfile.CHAT_STREAM);
        assertThatThrownBy(() -> gateway.call(gateway.chatConfig(), request))
                .isInstanceOf(DomainException.class)
                .hasMessageContaining("no native tool_choice=required");
    }

    @Test
    void kimiConfigResolvesToKimiAdapter() {
        RecordingAdapter kimi = new RecordingAdapter("kimi", ProviderCapabilities.kimi());
        when(configService.effectiveConfig(AiModelScope.TEXT_GENERATION)).thenReturn(config("moonshot", "kimi-k3"));
        ModelGateway gateway = new ModelGateway(configService, List.of(kimi));
        GatewayResponse response = gateway.call(gateway.chatConfig(),
                new GatewayRequest(List.of(GatewayMessage.user("hi")), List.of(), ToolChoiceMode.AUTO, CallProfile.CHAT_STREAM));
        assertThat(response.content()).isEqualTo("ok");
        assertThat(kimi.lastSettings).isNotNull();
    }

    @Test
    void thinkingProfileRaisesMaxTokensToFloor() {
        RecordingAdapter deepseek = new RecordingAdapter("deepseek", ProviderCapabilities.deepSeek());
        AiModelEffectiveConfig config = config("deepseek", "deepseek-v4-pro");
        when(configService.effectiveConfig(AiModelScope.TEXT_GENERATION)).thenReturn(config);
        ModelGateway gateway = new ModelGateway(configService, List.of(deepseek));
        // Config maxTokens=500 with thinking enabled: floor of 2000 must apply.
        AiModelEffectiveConfig smallBudget = new AiModelEffectiveConfig(AiModelScope.TEXT_GENERATION, true, false,
                "DATABASE", "deepseek", "https://api.deepseek.com/chat/completions", "sk", "sk-*", "env", "K",
                "deepseek-v4-pro", false, true, "high", 0.3, 500, null, null, null);
        gateway.call(smallBudget, new GatewayRequest(List.of(GatewayMessage.user("hi")), List.of(),
                ToolChoiceMode.AUTO, CallProfile.CHAT_STREAM));
        assertThat(deepseek.lastSettings.maxTokens()).isEqualTo(CallProfile.MIN_MAX_TOKENS_WHEN_THINKING);
        assertThat(deepseek.lastSettings.thinkingEnabled()).isTrue();
    }

    @Test
    void structuredSmallProfileForcesThinkingOff() {
        RecordingAdapter deepseek = new RecordingAdapter("deepseek", ProviderCapabilities.deepSeek());
        when(configService.effectiveConfig(AiModelScope.TEXT_GENERATION)).thenReturn(config("deepseek", "deepseek-v4-pro"));
        ModelGateway gateway = new ModelGateway(configService, List.of(deepseek));
        gateway.call(gateway.chatConfig(), new GatewayRequest(List.of(GatewayMessage.user("hi")), List.of(),
                ToolChoiceMode.AUTO, CallProfile.STRUCTURED_SMALL));
        assertThat(deepseek.lastSettings.thinkingEnabled()).isFalse();
    }

    @Test
    void unknownProviderIsExplicitError() {
        when(configService.effectiveConfig(AiModelScope.TEXT_GENERATION)).thenReturn(config("openai", "gpt-5"));
        ModelGateway gateway = new ModelGateway(configService, List.of(new RecordingAdapter("deepseek", ProviderCapabilities.deepSeek())));
        assertThatThrownBy(() -> gateway.call(gateway.chatConfig(),
                new GatewayRequest(List.of(GatewayMessage.user("hi")), List.of(), ToolChoiceMode.AUTO, CallProfile.CHAT_STREAM)))
                .isInstanceOf(DomainException.class)
                .hasMessageContaining("No agent tool-call adapter");
    }

    private AiModelEffectiveConfig config(String provider, String model) {
        return new AiModelEffectiveConfig(AiModelScope.TEXT_GENERATION, true, false, "DATABASE",
                provider, "https://example.test/v1/chat/completions", "sk", "sk-*", "env", "K",
                model, false, true, "high", 0.3, 4096, null, null, null);
    }

    private static final class RecordingAdapter implements ToolCallAdapter {
        private final String provider;
        private final ProviderCapabilities capabilities;
        private CallSettings lastSettings;

        private RecordingAdapter(String provider, ProviderCapabilities capabilities) {
            this.provider = provider;
            this.capabilities = capabilities;
        }

        @Override
        public String provider() {
            return provider;
        }

        @Override
        public ProviderCapabilities capabilities() {
            return capabilities;
        }

        @Override
        public GatewayResponse execute(AiModelEffectiveConfig config, CallSettings settings, GatewayRequest request) {
            this.lastSettings = settings;
            return new GatewayResponse("ok", List.of(), "stop", 1, 1, 0, provider, config.model());
        }
    }
}
