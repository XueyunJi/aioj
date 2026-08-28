package com.aioj.next.ai.agent.tool;

import com.aioj.next.ai.agent.context.DataClassification;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;

class ToolBrokerTest {

    private final ObjectMapper objectMapper = new ObjectMapper();
    private final ToolAuditService auditService = mock(ToolAuditService.class);

    @Test
    void successfulCallRunsFullPipelineAndReturnsSanitizedPayload() throws Exception {
        AgentTool tool = fakeTool("context.search_exact", Set.of("AI_CHAT"), schema(), (context, input) ->
                ToolResult.success(null, Map.of("hits", List.of()), List.of(),
                        DataClassification.USER_PRIVATE, null));
        ToolBroker broker = broker(tool);
        ToolResult<String> result = broker.execute(executionContext(Set.of("AI_CHAT")), 42L, 1,
                "call-1", "context.search_exact", "{\"query\":\"二分\"}");

        assertThat(result.ok()).isTrue();
        JsonNode payload = objectMapper.readTree(result.data());
        assertThat(payload.path("status").asText()).isEqualTo("SUCCESS");
        assertThat(payload.path("instructionAllowed").asBoolean()).isFalse();
        assertThat(payload.path("data").path("hits").isArray()).isTrue();
        verify(auditService).record(any(), anyLong(), anyInt(), any(), anyString(), anyString(),
                anyString(), any(), org.mockito.ArgumentMatchers.eq(ToolStatus.SUCCESS), any(), anyString(), anyLong(), isNull());
    }

    @Test
    void schemaViolationIsReturnedAsDataNotThrown() throws Exception {
        AgentTool tool = fakeTool("context.search_exact", Set.of("AI_CHAT"), schema(), (context, input) -> {
            throw new AssertionError("must not execute on schema violation");
        });
        ToolBroker broker = broker(tool);
        ToolResult<String> result = broker.execute(executionContext(Set.of("AI_CHAT")), 42L, 1,
                "call-2", "context.search_exact", "{\"unexpected\":1}");

        assertThat(result.ok()).isFalse();
        assertThat(result.status()).isEqualTo(ToolStatus.SCHEMA_ERROR);
        assertThat(objectMapper.readTree(result.data()).path("errorCode").asText())
                .isEqualTo("ARGUMENTS_SCHEMA_VIOLATION");
    }

    @Test
    void missingScopeIsDeniedBeforeExecution() throws Exception {
        AgentTool tool = fakeTool("context.search_exact", Set.of("AI_CHAT"), schema(), (context, input) -> {
            throw new AssertionError("must not execute when unauthorized");
        });
        ToolBroker broker = broker(tool);
        ToolResult<String> result = broker.execute(executionContext(Set.of()), 42L, 1,
                "call-3", "context.search_exact", "{\"query\":\"x\"}");

        assertThat(result.status()).isEqualTo(ToolStatus.POLICY_DENIED);
        assertThat(objectMapper.readTree(result.data()).path("errorCode").asText()).isEqualTo("MISSING_SCOPE");
    }

    @Test
    void unknownToolReturnsNotFound() throws Exception {
        ToolBroker broker = broker();
        ToolResult<String> result = broker.execute(executionContext(Set.of("AI_CHAT")), 42L, 1,
                "call-4", "context.missing", "{}");
        assertThat(result.status()).isEqualTo(ToolStatus.NOT_FOUND);
    }

    @Test
    void toolExceptionBecomesExecutionErrorPayload() throws Exception {
        AgentTool tool = fakeTool("context.search_exact", Set.of("AI_CHAT"), schema(), (context, input) -> {
            throw new IllegalStateException("boom");
        });
        ToolBroker broker = broker(tool);
        ToolResult<String> result = broker.execute(executionContext(Set.of("AI_CHAT")), 42L, 1,
                "call-5", "context.search_exact", "{\"query\":\"x\"}");
        assertThat(result.status()).isEqualTo(ToolStatus.EXECUTION_ERROR);
        JsonNode payload = objectMapper.readTree(result.data());
        assertThat(payload.path("errorCode").asText()).isEqualTo("TOOL_EXECUTION_ERROR");
    }

    private ToolBroker broker(AgentTool... tools) {
        return new ToolBroker(new ToolRegistry(List.of(tools)), new ToolAuthorizationService(),
                new ToolResultSanitizer(objectMapper), auditService, objectMapper);
    }

    private ToolExecutionContext executionContext(Set<String> scopes) {
        return new ToolExecutionContext(7L, "c1", "t1", 1L, "ps-1", scopes, Instant.now(), "trace-1");
    }

    private JsonNode schema() throws Exception {
        return objectMapper.readTree("""
                {"type":"object","additionalProperties":false,
                 "required":["query"],
                 "properties":{"query":{"type":"string","minLength":1}}}
                """);
    }

    private interface ToolBehavior {
        ToolResult<Object> run(ToolExecutionContext context, JsonNode input);
    }

    private AgentTool fakeTool(String name, Set<String> scopes, JsonNode schema, ToolBehavior behavior) {
        ToolDescriptor descriptor = new ToolDescriptor(
                name, "1.0.0", "fake tool", schema,
                ToolRiskLevel.LOW, true, true, scopes,
                Set.of(DataClassification.USER_PRIVATE), 1000, Duration.ofSeconds(2), ToolAuditLevel.FULL);
        return new AgentTool() {
            @Override
            public ToolDescriptor descriptor() {
                return descriptor;
            }

            @Override
            public ToolResult<Object> execute(ToolExecutionContext context, JsonNode input) {
                return behavior.run(context, input);
            }
        };
    }
}
