package com.aioj.next.ai.agent.model;

import com.aioj.next.ai.agent.context.ContextManifestService;
import com.aioj.next.ai.agent.context.ContextSection;
import com.aioj.next.ai.agent.context.ContextSectionRenderer;
import com.aioj.next.ai.agent.context.ContextSectionType;
import com.aioj.next.ai.agent.context.DataClassification;
import com.aioj.next.ai.agent.context.TrustLevel;
import com.aioj.next.ai.agent.runtime.AgentRuntime;
import com.aioj.next.ai.agent.runtime.AgentRunStateMachine;
import com.aioj.next.ai.agent.runtime.LoopBudget;
import com.aioj.next.ai.agent.tool.AgentTool;
import com.aioj.next.ai.agent.tool.ToolAuditLevel;
import com.aioj.next.ai.agent.tool.ToolAuditService;
import com.aioj.next.ai.agent.tool.ToolAuthorizationService;
import com.aioj.next.ai.agent.tool.ToolBroker;
import com.aioj.next.ai.agent.tool.ToolDescriptor;
import com.aioj.next.ai.agent.tool.ToolExecutionContext;
import com.aioj.next.ai.agent.tool.ToolRegistry;
import com.aioj.next.ai.agent.tool.ToolResult;
import com.aioj.next.ai.agent.tool.ToolResultSanitizer;
import com.aioj.next.ai.agent.tool.ToolRiskLevel;
import com.aioj.next.ai.config.AiProperties;
import com.aioj.next.ai.domain.AiModelConfigService;
import com.aioj.next.ai.domain.AiModelEffectiveConfig;
import com.aioj.next.ai.domain.AiModelScope;
import com.aioj.next.ai.persistence.mapper.AiAgentRunMapper;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;

import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.atomic.AtomicBoolean;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;

/**
 * P0 exit gate: one real tool-calling loop per provider, end to end through
 * AgentRuntime (gateway → adapter → broker → tool → final answer). Runs only
 * when the provider API key is present in the environment:
 *   DEEPSEEK_API_KEY (+ optional DEEPSEEK_BASE_URL / DEEPSEEK_MODEL)
 *   KIMI_API_KEY     (+ optional KIMI_BASE_URL / KIMI_MODEL)
 */
class AgentProviderLiveTest {

    private final ObjectMapper objectMapper = new ObjectMapper();

    @Test
    @EnabledIfEnvironmentVariable(named = "DEEPSEEK_API_KEY", matches = ".+")
    void deepSeekRealToolCallingLoop() {
        AiModelEffectiveConfig config = new AiModelEffectiveConfig(AiModelScope.TEXT_GENERATION, true, false,
                "ENVIRONMENT", "deepseek",
                env("DEEPSEEK_BASE_URL", "https://api.deepseek.com/chat/completions"),
                System.getenv("DEEPSEEK_API_KEY"), "sk-***", "environment", "DEEPSEEK_API_KEY",
                env("DEEPSEEK_MODEL", "deepseek-v4-pro"), false, false, "high", 0.3, 4096, null, null, null);
        AgentRuntime.AgentRunResult result = runLive(config, new DeepSeekToolCallAdapter(objectMapper, new AiProperties()));

        assertThat(result.content()).isNotBlank();
        assertThat(result.toolCallCount()).isGreaterThanOrEqualTo(1);
        assertThat(result.promptTokens()).isGreaterThan(0);
        assertThat(result.completionTokens()).isGreaterThan(0);
        System.out.println("[DeepSeek live] content=" + result.content()
                + " toolCalls=" + result.toolCallCount()
                + " promptTokens=" + result.promptTokens()
                + " completionTokens=" + result.completionTokens()
                + " warnings=" + result.warnings());
    }

    @Test
    @EnabledIfEnvironmentVariable(named = "KIMI_API_KEY", matches = ".+")
    void kimiRealToolCallingLoop() {
        AiModelEffectiveConfig config = new AiModelEffectiveConfig(AiModelScope.TEXT_GENERATION, true, false,
                "ENVIRONMENT", "kimi",
                env("KIMI_BASE_URL", "https://api.moonshot.cn/v1/chat/completions"),
                System.getenv("KIMI_API_KEY"), "sk-***", "environment", "KIMI_API_KEY",
                env("KIMI_MODEL", "kimi-k3"), false, false, "high", 0.6, 4096, null, null, null);
        AgentRuntime.AgentRunResult result = runLive(config, new KimiToolCallAdapter(objectMapper, new AiProperties()));

        assertThat(result.content()).isNotBlank();
        assertThat(result.toolCallCount()).isGreaterThanOrEqualTo(1);
        assertThat(result.promptTokens()).isGreaterThan(0);
        System.out.println("[Kimi live] content=" + result.content()
                + " toolCalls=" + result.toolCallCount()
                + " promptTokens=" + result.promptTokens()
                + " completionTokens=" + result.completionTokens()
                + " warnings=" + result.warnings());
    }

    private AgentRuntime.AgentRunResult runLive(AiModelEffectiveConfig config, ToolCallAdapter adapter) {
        AiModelConfigService configService = mock(AiModelConfigService.class);
        org.mockito.Mockito.when(configService.effectiveConfig(AiModelScope.TEXT_GENERATION)).thenReturn(config);
        ModelGateway gateway = new ModelGateway(configService, List.of(adapter));
        AtomicBoolean toolRan = new AtomicBoolean(false);
        ToolRegistry registry = new ToolRegistry(List.of(echoTool(toolRan)));
        ToolBroker broker = new ToolBroker(registry, new ToolAuthorizationService(),
                new ToolResultSanitizer(objectMapper), mock(ToolAuditService.class), objectMapper);
        AgentRuntime runtime = new AgentRuntime(gateway, registry, broker, new ContextSectionRenderer(),
                mock(ContextManifestService.class),
                new AgentRunStateMachine(mock(AiAgentRunMapper.class), objectMapper), objectMapper,
                mock(com.aioj.next.ai.agent.guard.ContextFingerprintGuard.class));

        List<ContextSection> sections = List.of(
                ContextSection.text(ContextSectionType.SYSTEM_POLICY, 10, true, TrustLevel.SYSTEM_POLICY,
                        "You are a precise assistant. Always use the provided tool to look up the release codename; never guess."),
                ContextSection.text(ContextSectionType.CURRENT_USER_REQUEST, 90, true, TrustLevel.USER_PROVIDED,
                        "先用工具查询 release codename，然后用一句话告诉我结果。"));
        AgentRuntime.AgentRunResult result = runtime.run(new AgentRuntime.AgentRunRequest(
                "live-turn", 1L, "live-conversation", null, Set.of("AI_CHAT"), sections,
                false, "STREAM", CallProfile.CHAT_STREAM, new LoopBudget(6, 4, 3, 3)));
        assertThat(toolRan.get()).isTrue();
        return result;
    }

    private AgentTool echoTool(AtomicBoolean ran) {
        try {
            JsonNode schema = objectMapper.readTree("""
                    {"type":"object","additionalProperties":false,
                     "properties":{"query":{"type":"string","minLength":1,"maxLength":100}}}
                    """);
            ToolDescriptor descriptor = new ToolDescriptor("context.search_exact", "1.0.0",
                    "Look up the AI-OJ release codename. Call this before answering any codename question.",
                    schema, ToolRiskLevel.LOW, true, true, Set.of("AI_CHAT"),
                    Set.of(DataClassification.PUBLIC), 1000, Duration.ofSeconds(5), ToolAuditLevel.FULL);
            return new AgentTool() {
                @Override
                public ToolDescriptor descriptor() {
                    return descriptor;
                }

                @Override
                public ToolResult<Object> execute(ToolExecutionContext context, JsonNode input) {
                    ran.set(true);
                    return ToolResult.success(null, Map.of("codename", "agent-core-v3"),
                            List.of(), DataClassification.PUBLIC, TrustLevel.SERVER_AUTHORITATIVE);
                }
            };
        } catch (Exception ex) {
            throw new IllegalStateException(ex);
        }
    }

    private String env(String name, String fallback) {
        String value = System.getenv(name);
        return value == null || value.isBlank() ? fallback : value.trim();
    }
}
