package com.aioj.next.ai.agent.model;

import com.aioj.next.ai.agent.context.DataClassification;
import com.aioj.next.ai.agent.tool.ToolAuditLevel;
import com.aioj.next.ai.agent.tool.ToolDescriptor;
import com.aioj.next.ai.agent.tool.ToolRiskLevel;
import com.aioj.next.ai.domain.AiModelEffectiveConfig;
import com.aioj.next.ai.domain.AiModelScope;
import com.aioj.next.common.error.DomainException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;
import org.springframework.mock.http.client.MockClientHttpRequest;
import org.springframework.test.web.client.MockRestServiceServer;
import org.springframework.web.client.RestClient;

import java.time.Duration;
import java.util.List;
import java.util.Set;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withSuccess;

class KimiToolCallAdapterTest {

    private final ObjectMapper objectMapper = new ObjectMapper();
    private MockRestServiceServer server;
    private KimiToolCallAdapter adapter;
    private final AtomicReference<String> recordedBody = new AtomicReference<>();

    @BeforeEach
    void setUp() {
        RestClient.Builder builder = RestClient.builder();
        server = MockRestServiceServer.bindTo(builder).build();
        adapter = new KimiToolCallAdapter(objectMapper, builder.build());
        recordedBody.set(null);
    }

    @Test
    void functionPayloadCarriesExplicitStrictTrue() throws Exception {
        respondWithChat("{\"choices\":[{\"message\":{\"content\":\"ok\"},\"finish_reason\":\"stop\"}]}");
        adapter.execute(config("kimi-k3-0905"), new CallSettings(false, "high", 0.6, 4096),
                new GatewayRequest(List.of(GatewayMessage.user("hi")), tools(), ToolChoiceMode.AUTO, CallProfile.CHAT_STREAM));
        JsonNode body = objectMapper.readTree(recordedBody.get());
        assertThat(body.path("tools").get(0).path("function").path("strict").asBoolean()).isTrue();
        assertThat(body.path("tools").get(0).path("function").path("name").asText())
                .isEqualTo("context__search_exact");
    }

    @Test
    void k3SendsTopLevelReasoningEffortAndNativeRequired() throws Exception {
        respondWithChat("{\"choices\":[{\"message\":{\"content\":\"ok\"},\"finish_reason\":\"stop\"}]}");
        adapter.execute(config("kimi-k3-0905"), new CallSettings(false, "max", 0.6, 4096),
                new GatewayRequest(List.of(GatewayMessage.user("hi")), tools(), ToolChoiceMode.REQUIRED, CallProfile.CHAT_STREAM));
        JsonNode body = objectMapper.readTree(recordedBody.get());
        assertThat(body.path("reasoning_effort").asText()).isEqualTo("max");
        assertThat(body.path("tool_choice").asText()).isEqualTo("required");
        assertThat(body.has("thinking")).isFalse();
        // k3 is thinking-only: only temperature=1 is accepted.
        assertThat(body.path("temperature").asDouble()).isEqualTo(1.0);
    }

    @Test
    void k25ThinkingUsesThinkingFieldAndTemperatureOne() throws Exception {
        respondWithChat("{\"choices\":[{\"message\":{\"content\":\"ok\"},\"finish_reason\":\"stop\"}]}");
        adapter.execute(config("kimi-k2.5-0905"), new CallSettings(true, "high", 0.6, 4096),
                new GatewayRequest(List.of(GatewayMessage.user("hi")), List.of(), ToolChoiceMode.AUTO, CallProfile.CHAT_STREAM));
        JsonNode body = objectMapper.readTree(recordedBody.get());
        assertThat(body.path("thinking").path("type").asText()).isEqualTo("enabled");
        assertThat(body.path("temperature").asDouble()).isEqualTo(1.0);
        assertThat(body.has("reasoning_effort")).isFalse();
    }

    @Test
    void requiredOnNonK3ModelIsRejectedBeforeHttp() {
        assertThatThrownBy(() -> adapter.execute(config("kimi-k2.5-0905"),
                new CallSettings(false, "high", 0.6, 4096),
                new GatewayRequest(List.of(GatewayMessage.user("hi")), tools(), ToolChoiceMode.REQUIRED, CallProfile.CHAT_STREAM)))
                .isInstanceOf(DomainException.class)
                .hasMessageContaining("tool_choice=required");
        assertThat(recordedBody.get()).isNull();
    }

    private void respondWithChat(String json) {
        server.expect(request -> recordedBody.set(((MockClientHttpRequest) request).getBodyAsString()))
                .andRespond(withSuccess(json, MediaType.APPLICATION_JSON));
    }

    private List<ToolDescriptor> tools() throws Exception {
        JsonNode schema = objectMapper.readTree("""
                {"type":"object","additionalProperties":false,"required":["exactTerms"],
                 "properties":{"exactTerms":{"type":"array","items":{"type":"string","minLength":1}}}}
                """);
        return List.of(new ToolDescriptor("context.search_exact", "1.0.0", "search", schema,
                ToolRiskLevel.LOW, true, true, Set.of("AI_CHAT"), Set.of(DataClassification.USER_PRIVATE),
                2000, Duration.ofSeconds(5), ToolAuditLevel.FULL));
    }

    private AiModelEffectiveConfig config(String model) {
        return new AiModelEffectiveConfig(AiModelScope.TEXT_GENERATION, true, false, "DATABASE",
                "kimi", "https://api.moonshot.cn/v1/chat/completions", "sk-kimi", "sk-***", "environment",
                "KIMI_API_KEY", model, false, false, "high", 0.6, 4096, null, null, null);
    }
}
