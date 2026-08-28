package com.aioj.next.ai.agent.tool;

import com.fasterxml.jackson.databind.JsonNode;

/**
 * Internal Agent tool SPI (design doc §4.1). Tools never see raw HTTP or the
 * model; they receive a server-generated {@link ToolExecutionContext} and a
 * schema-validated JSON input.
 */
public interface AgentTool {

    ToolDescriptor descriptor();

    ToolResult<Object> execute(ToolExecutionContext context, JsonNode input);
}
