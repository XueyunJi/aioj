package com.aioj.next.ai.domain;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;
import org.springframework.test.web.client.MockRestServiceServer;
import org.springframework.web.client.RestClient;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.springframework.test.web.client.ExpectedCount.once;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.content;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.header;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.jsonPath;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.requestTo;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withSuccess;

class AiModelCompletionClientTest {
    private final ObjectMapper objectMapper = new ObjectMapper();

    @Test
    void markdownRetriesWithoutThinkingWhenProviderReturnsEmptyContent() {
        RestClient.Builder builder = RestClient.builder();
        MockRestServiceServer server = MockRestServiceServer.bindTo(builder).build();
        AiModelCompletionClient client = new AiModelCompletionClient(objectMapper, builder.build());
        AiModelEffectiveConfig config = new AiModelEffectiveConfig(
                AiModelScope.REPORT_ANALYSIS,
                true,
                false,
                "test",
                "deepseek",
                "https://api.deepseek.com/chat/completions",
                "test-key",
                null,
                "environment",
                "DEEPSEEK_API_KEY",
                "deepseek-v4-flash",
                true,
                true,
                "high",
                null,
                null,
                null,
                null,
                null
        );

        server.expect(once(), requestTo("https://api.deepseek.com/chat/completions"))
                .andExpect(jsonPath("$.response_format").doesNotExist())
                .andExpect(jsonPath("$.thinking.type").value("enabled"))
                .andRespond(withSuccess("""
                        {
                          "choices": [{"message": {"content": "", "reasoning_content": "analysis only"}}],
                          "usage": {"prompt_tokens": 13, "completion_tokens": 17}
                        }
                        """, MediaType.APPLICATION_JSON));
        server.expect(once(), requestTo("https://api.deepseek.com/chat/completions"))
                .andExpect(jsonPath("$.response_format").doesNotExist())
                .andExpect(jsonPath("$.thinking.type").value("disabled"))
                .andExpect(jsonPath("$.reasoning_effort").doesNotExist())
                .andRespond(withSuccess("""
                        {
                          "choices": [{"message": {"content": "# Contest report"}}],
                          "usage": {"prompt_tokens": 7, "completion_tokens": 5}
                        }
                        """, MediaType.APPLICATION_JSON));

        AiModelCompletionClient.CompletionResult result = client.complete(
                config,
                List.of(Map.of("role", "user", "content", "write markdown")),
                0.3,
                0,
                false
        );

        assertThat(result.content()).isEqualTo("# Contest report");
        assertThat(result.promptTokens()).isEqualTo(20);
        assertThat(result.completionTokens()).isEqualTo(22);
        server.verify();
    }

    @Test
    void markdownStopsAfterOneEmptyRetry() {
        RestClient.Builder builder = RestClient.builder();
        MockRestServiceServer server = MockRestServiceServer.bindTo(builder).build();
        AiModelCompletionClient client = new AiModelCompletionClient(objectMapper, builder.build());
        AiModelEffectiveConfig config = new AiModelEffectiveConfig(
                AiModelScope.REPORT_ANALYSIS, true, false, "test", "deepseek",
                "https://api.deepseek.com/chat/completions", "test-key", null,
                "environment", "DEEPSEEK_API_KEY", "deepseek-v4-flash",
                true, true, "high", null, null, null, null, null
        );

        server.expect(once(), requestTo(config.baseUrl()))
                .andExpect(jsonPath("$.thinking.type").value("enabled"))
                .andRespond(withSuccess("{\"choices\":[{\"message\":{\"content\":\"\"}}]}",
                        MediaType.APPLICATION_JSON));
        server.expect(once(), requestTo(config.baseUrl()))
                .andExpect(jsonPath("$.thinking.type").value("disabled"))
                .andRespond(withSuccess("{\"choices\":[{\"message\":{\"content\":\"\"}}]}",
                        MediaType.APPLICATION_JSON));

        assertThatThrownBy(() -> client.complete(
                config, List.of(Map.of("role", "user", "content", "write markdown")), 0.3, 0, false))
                .hasMessage("AI provider returned empty content");
        server.verify();
    }

    @Test
    void strictJsonRetriesWithoutThinkingWhenProviderReturnsEmptyContent() {
        RestClient.Builder builder = RestClient.builder();
        MockRestServiceServer server = MockRestServiceServer.bindTo(builder).build();
        AiModelCompletionClient client = new AiModelCompletionClient(objectMapper, builder.build());
        AiModelEffectiveConfig config = new AiModelEffectiveConfig(
                AiModelScope.PROBLEM_DRAFT,
                true,
                false,
                "test",
                "deepseek",
                "https://api.deepseek.com/chat/completions",
                "test-key",
                null,
                "environment",
                "DEEPSEEK_API_KEY",
                "deepseek-v4-pro",
                true,
                true,
                "max",
                null,
                null,
                null,
                null,
                null
        );

        server.expect(once(), requestTo("https://api.deepseek.com/chat/completions"))
                .andExpect(header("Authorization", "Bearer test-key"))
                .andExpect(content().contentType(MediaType.APPLICATION_JSON))
                .andExpect(jsonPath("$.response_format.type").value("json_object"))
                .andExpect(jsonPath("$.thinking.type").value("enabled"))
                .andExpect(jsonPath("$.reasoning_effort").value("max"))
                .andRespond(withSuccess("""
                        {
                          "choices": [{"message": {"content": ""}}],
                          "usage": {"prompt_tokens": 11, "completion_tokens": 3}
                        }
                        """, MediaType.APPLICATION_JSON));
        server.expect(once(), requestTo("https://api.deepseek.com/chat/completions"))
                .andExpect(header("Authorization", "Bearer test-key"))
                .andExpect(jsonPath("$.response_format.type").value("json_object"))
                .andExpect(jsonPath("$.thinking.type").value("disabled"))
                .andExpect(jsonPath("$.reasoning_effort").doesNotExist())
                .andRespond(withSuccess("""
                        {
                          "choices": [{"message": {"content": "{\\"ok\\":true}"}}],
                          "usage": {"prompt_tokens": 7, "completion_tokens": 5}
                        }
                        """, MediaType.APPLICATION_JSON));

        AiModelCompletionClient.CompletionResult result = client.complete(
                config,
                List.of(Map.of("role", "user", "content", "return json")),
                0.2,
                100,
                true
        );

        assertThat(result.content()).isEqualTo("{\"ok\":true}");
        assertThat(result.promptTokens()).isEqualTo(18);
        assertThat(result.completionTokens()).isEqualTo(8);
        server.verify();
    }

    @Test
    void completeWithJsonSchemaUsesStrictSchemaForOpenAiCompatibleProvider() throws Exception {
        RestClient.Builder builder = RestClient.builder();
        MockRestServiceServer server = MockRestServiceServer.bindTo(builder).build();
        AiModelCompletionClient client = new AiModelCompletionClient(objectMapper, builder.build());
        AiModelEffectiveConfig config = new AiModelEffectiveConfig(
                AiModelScope.PROBLEM_DRAFT,
                true,
                false,
                "test",
                "openai",
                "https://api.openai.com/v1/chat/completions",
                "test-key",
                null,
                "environment",
                "OPENAI_API_KEY",
                "gpt-5-mini",
                true,
                false,
                "high",
                null,
                null,
                null,
                null,
                null
        );

        server.expect(once(), requestTo("https://api.openai.com/v1/chat/completions"))
                .andExpect(jsonPath("$.response_format.type").value("json_schema"))
                .andExpect(jsonPath("$.response_format.json_schema.name").value("problem_draft"))
                .andExpect(jsonPath("$.response_format.json_schema.strict").value(true))
                .andExpect(jsonPath("$.response_format.json_schema.schema.type").value("object"))
                .andRespond(withSuccess("""
                        {
                          "choices": [{"message": {"content": "{\\"ok\\":true}"}}],
                          "usage": {"prompt_tokens": 2, "completion_tokens": 3}
                        }
                        """, MediaType.APPLICATION_JSON));

        AiModelCompletionClient.CompletionResult result = client.completeWithJsonSchema(
                config,
                List.of(Map.of("role", "user", "content", "return json")),
                0.2,
                100,
                objectMapper.readTree("{\"type\":\"object\"}")
        );

        assertThat(result.content()).isEqualTo("{\"ok\":true}");
        server.verify();
    }

    @Test
    void completeWithJsonSchemaFallsBackToJsonModeForUnsupportedProvider() throws Exception {
        RestClient.Builder builder = RestClient.builder();
        MockRestServiceServer server = MockRestServiceServer.bindTo(builder).build();
        AiModelCompletionClient client = new AiModelCompletionClient(objectMapper, builder.build());
        AiModelEffectiveConfig config = new AiModelEffectiveConfig(
                AiModelScope.PROBLEM_DRAFT,
                true,
                false,
                "test",
                "deepseek",
                "https://api.deepseek.com/chat/completions",
                "test-key",
                null,
                "environment",
                "DEEPSEEK_API_KEY",
                "deepseek-chat",
                true,
                false,
                "high",
                null,
                null,
                null,
                null,
                null
        );

        server.expect(once(), requestTo("https://api.deepseek.com/chat/completions"))
                .andExpect(jsonPath("$.response_format.type").value("json_object"))
                .andExpect(jsonPath("$.response_format.json_schema").doesNotExist())
                .andRespond(withSuccess("""
                        {
                          "choices": [{"message": {"content": "{\\"ok\\":true}"}}],
                          "usage": {"prompt_tokens": 2, "completion_tokens": 3}
                        }
                        """, MediaType.APPLICATION_JSON));

        AiModelCompletionClient.CompletionResult result = client.completeWithJsonSchema(
                config,
                List.of(Map.of("role", "user", "content", "return json")),
                0.2,
                100,
                objectMapper.readTree("{\"type\":\"object\"}")
        );

        assertThat(result.content()).isEqualTo("{\"ok\":true}");
        server.verify();
    }
}
