package com.aioj.next.ai.agent.tool;

import com.aioj.next.ai.agent.context.DataClassification;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.util.List;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class ToolRegistryTest {

    private final ObjectMapper objectMapper = new ObjectMapper();

    @Test
    void validToolRegistersAndScopeFilteringWorks() throws Exception {
        AgentTool chatTool = fakeTool("context.search_exact", schema("query"), Set.of("AI_CHAT"));
        AgentTool staffTool = fakeTool("staff.metrics", schema("query"), Set.of("AI_STAFF"));
        ToolRegistry registry = new ToolRegistry(List.of(chatTool, staffTool));

        assertThat(registry.find("context.search_exact")).isSameAs(chatTool);
        assertThat(registry.descriptorsForScopes(Set.of("AI_CHAT")))
                .extracting(ToolDescriptor::name)
                .containsExactly("context.search_exact");
        assertThat(registry.descriptorsForScopes(Set.of("AI_CHAT", "AI_STAFF")))
                .extracting(ToolDescriptor::name)
                .containsExactly("context.search_exact", "staff.metrics");
    }

    @Test
    void identityFieldsInInputSchemaAreRejected() throws Exception {
        String schema = """
                {"type":"object","additionalProperties":false,
                 "properties":{"userId":{"type":"string"},"q":{"type":"string"}}}
                """;
        AgentTool bad = fakeTool("context.bad_identity", objectMapper.readTree(schema), Set.of());
        assertThatThrownBy(() -> new ToolRegistry(List.of(bad)))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("identity/permission field");
    }

    @Test
    void doubleUnderscoreNamesAreRejected() throws Exception {
        AgentTool bad = fakeTool("context.bad__name", schema("q"), Set.of());
        assertThatThrownBy(() -> new ToolRegistry(List.of(bad)))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("consecutive underscores");
    }

    @Test
    void unsupportedSchemaKeywordsAreRejected() throws Exception {
        String schema = """
                {"type":"object","properties":{"q":{"type":"string",
                 "oneOf":[{"type":"string"}]}}}
                """;
        AgentTool bad = fakeTool("context.bad_schema", objectMapper.readTree(schema), Set.of());
        assertThatThrownBy(() -> new ToolRegistry(List.of(bad)))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("outside the supported");
    }

    @Test
    void duplicateNamesAreRejected() throws Exception {
        AgentTool first = fakeTool("context.dup", schema("q"), Set.of());
        AgentTool second = fakeTool("context.dup", schema("q"), Set.of());
        assertThatThrownBy(() -> new ToolRegistry(List.of(first, second)))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("duplicate tool name");
    }

    private JsonNode schema(String requiredField) throws Exception {
        return objectMapper.readTree("""
                {"type":"object","additionalProperties":false,
                 "properties":{"%s":{"type":"string"}}}
                """.formatted(requiredField));
    }

    private AgentTool fakeTool(String name, JsonNode schema, Set<String> scopes) {
        ToolDescriptor descriptor = new ToolDescriptor(
                name, "1.0.0", "fake tool for tests", schema,
                ToolRiskLevel.LOW, true, true, scopes,
                Set.of(DataClassification.USER_PRIVATE), 1000, Duration.ofSeconds(2), ToolAuditLevel.FULL);
        return new AgentTool() {
            @Override
            public ToolDescriptor descriptor() {
                return descriptor;
            }

            @Override
            public ToolResult<Object> execute(ToolExecutionContext context, JsonNode input) {
                return ToolResult.success(null, "ok", List.of(), DataClassification.USER_PRIVATE, null);
            }
        };
    }
}
