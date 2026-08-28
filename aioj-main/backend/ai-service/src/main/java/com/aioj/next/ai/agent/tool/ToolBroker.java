package com.aioj.next.ai.agent.tool;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.stereotype.Service;

import java.util.List;

/**
 * The single entry point for every model-initiated tool call (design doc §4.1):
 * schema validation → authorization → execution → sanitization → audit.
 * The model only ever receives the sanitized JSON payload.
 */
@Service
public class ToolBroker {

    private final ToolRegistry registry;
    private final ToolAuthorizationService authorizationService;
    private final ToolResultSanitizer sanitizer;
    private final ToolAuditService auditService;
    private final ObjectMapper objectMapper;

    public ToolBroker(ToolRegistry registry, ToolAuthorizationService authorizationService,
                      ToolResultSanitizer sanitizer, ToolAuditService auditService, ObjectMapper objectMapper) {
        this.registry = registry;
        this.authorizationService = authorizationService;
        this.sanitizer = sanitizer;
        this.auditService = auditService;
        this.objectMapper = objectMapper;
    }

    /**
     * @return ToolResult whose data is the model-facing JSON payload string
     *         (to be appended as the tool message content). Errors are data:
     *         the model sees them and may correct its call, bounded by the loop budget.
     */
    public ToolResult<String> execute(ToolExecutionContext context, long agentRunId, int callSeq,
                                      String callId, String toolName, String argumentsJson) {
        long started = System.nanoTime();
        AgentTool tool = registry.find(toolName);
        if (tool == null) {
            return finish(context, agentRunId, callSeq, null, toolName, callId, argumentsJson, null,
                    ToolResult.failure(callId, ToolStatus.NOT_FOUND, "TOOL_NOT_FOUND",
                            "Tool " + toolName + " is not available"), started);
        }
        ToolDescriptor descriptor = tool.descriptor();

        JsonNode arguments;
        try {
            arguments = argumentsJson == null || argumentsJson.isBlank()
                    ? objectMapper.createObjectNode()
                    : objectMapper.readTree(argumentsJson);
        } catch (Exception ex) {
            return finish(context, agentRunId, callSeq, descriptor, toolName, callId, argumentsJson, null,
                    ToolResult.failure(callId, ToolStatus.SCHEMA_ERROR, "ARGUMENTS_NOT_JSON",
                            "Tool arguments are not valid JSON"), started);
        }

        List<String> schemaErrors = ToolInputSchemaValidator.validate(descriptor.inputSchema(), arguments);
        if (!schemaErrors.isEmpty()) {
            return finish(context, agentRunId, callSeq, descriptor, toolName, callId, argumentsJson, null,
                    ToolResult.failure(callId, ToolStatus.SCHEMA_ERROR, "ARGUMENTS_SCHEMA_VIOLATION",
                            "Tool arguments failed schema validation: " + String.join("; ", schemaErrors)), started);
        }

        ToolAuthorizationService.AuthorizationDecision decision = authorizationService.authorize(descriptor, context);
        if (!decision.allowed()) {
            return finish(context, agentRunId, callSeq, descriptor, toolName, callId, argumentsJson, decision.decisionId(),
                    ToolResult.failure(callId, ToolStatus.POLICY_DENIED, decision.reasonCode(),
                            "Tool call was denied by server policy"), started);
        }

        ToolResult<Object> result;
        try {
            result = tool.execute(context, arguments);
        } catch (RuntimeException ex) {
            result = ToolResult.failure(callId, ToolStatus.EXECUTION_ERROR, "TOOL_EXECUTION_ERROR",
                    "Tool execution failed: " + (ex.getMessage() == null ? ex.getClass().getSimpleName() : ex.getMessage()));
        }
        result = result.withPolicyDecisionId(decision.decisionId());
        return finish(context, agentRunId, callSeq, descriptor, toolName, callId, argumentsJson, decision.decisionId(),
                result, started);
    }

    private ToolResult<String> finish(ToolExecutionContext context, long agentRunId, int callSeq,
                                      ToolDescriptor descriptor, String toolName, String callId,
                                      String argumentsJson, String policyDecisionId,
                                      ToolResult<Object> result, long startedNanos) {
        String payload = sanitizer.toModelPayload(result, descriptor);
        long latencyMs = (System.nanoTime() - startedNanos) / 1_000_000;
        auditService.record(context, agentRunId, callSeq, descriptor, toolName, callId, argumentsJson,
                policyDecisionId, result.status(), result.classification(), payload, latencyMs, result.errorCode());
        return new ToolResult<>(callId, result.status(), payload, List.of(), result.classification(), result.trustLevel(),
                policyDecisionId, result.truncated(), result.nextCursor(), result.resultHash(), result.warnings(),
                result.errorCode(), result.errorMessage());
    }
}
