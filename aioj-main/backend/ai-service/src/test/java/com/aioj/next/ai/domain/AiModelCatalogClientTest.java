package com.aioj.next.ai.domain;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class AiModelCatalogClientTest {
    private final ObjectMapper objectMapper = new ObjectMapper();
    private final AiModelCatalogClient client = new AiModelCatalogClient(objectMapper);

    @Test
    void derivesProviderModelUrls() {
        assertThat(client.modelsUrl("deepseek", "https://api.deepseek.com/chat/completions"))
                .isEqualTo("https://api.deepseek.com/models");
        assertThat(client.modelsUrl("moonshot", "https://api.moonshot.cn/v1/chat/completions"))
                .isEqualTo("https://api.moonshot.cn/v1/models");
        assertThat(client.modelsUrl("custom", "https://example.test/v1/chat/completions"))
                .isEqualTo("https://example.test/v1/models");
        assertThat(client.modelsUrl("dashscope", "https://dashscope.aliyuncs.com/compatible-mode/v1/embeddings"))
                .isNull();
    }

    @Test
    void parsesDeepSeekModelsWithJsonAndThinkingCapabilities() throws Exception {
        var models = client.parseModels("deepseek", objectMapper.readTree("""
                {
                  "object": "list",
                  "data": [
                    {"id": "deepseek-v4-flash", "object": "model", "owned_by": "deepseek"},
                    {"id": "deepseek-chat", "object": "model", "owned_by": "deepseek"}
                  ]
                }
                """));

        assertThat(models).hasSize(2);
        assertThat(models.get(0).supportsJsonOutput()).isTrue();
        assertThat(models.get(0).supportsThinking()).isTrue();
        assertThat(models.get(0).thinkingEffortModes()).containsExactly("high", "max");
        assertThat(models.get(1).deprecated()).isTrue();
    }

    @Test
    void parsesKimiReasoningCapabilityWithoutEffortModes() throws Exception {
        var models = client.parseModels("moonshot", objectMapper.readTree("""
                {
                  "object": "list",
                  "data": [
                    {
                      "id": "kimi-k2.6",
                      "object": "model",
                      "owned_by": "moonshot",
                      "context_length": 262144,
                      "supports_reasoning": true
                    },
                    {
                      "id": "moonshot-v1-32k",
                      "object": "model",
                      "owned_by": "moonshot",
                      "context_length": 32768,
                      "supports_reasoning": false
                    }
                  ]
                }
                """));

        assertThat(models).hasSize(2);
        assertThat(models.get(0).supportsJsonOutput()).isTrue();
        assertThat(models.get(0).supportsThinking()).isTrue();
        assertThat(models.get(0).thinkingEffortModes()).isEmpty();
        assertThat(models.get(0).recommendedTemperature()).isEqualTo(0.6);
        assertThat(models.get(0).contextLength()).isEqualTo(262144);
        assertThat(models.get(1).supportsThinking()).isFalse();
    }
}
