package com.aioj.next.ai.agent.runtime;

import com.aioj.next.ai.agent.context.BootstrapContextBuilder;
import com.aioj.next.ai.agent.context.ContextManifestService;
import com.aioj.next.ai.agent.context.ContextSection;
import com.aioj.next.ai.agent.context.ContextSectionRenderer;
import com.aioj.next.ai.agent.guard.ContextFingerprintGuard;
import com.aioj.next.ai.agent.guard.GuardVerdict;
import com.aioj.next.ai.agent.model.CallProfile;
import com.aioj.next.ai.agent.model.GatewayMessage;
import com.aioj.next.ai.agent.model.GatewayRequest;
import com.aioj.next.ai.agent.model.GatewayResponse;
import com.aioj.next.ai.agent.model.GatewayToolCall;
import com.aioj.next.ai.agent.model.ModelGateway;
import com.aioj.next.ai.agent.model.ModelUsage;
import com.aioj.next.ai.agent.model.ToolChoiceMode;
import com.aioj.next.ai.agent.model.UsageMeter;
import com.aioj.next.ai.agent.policy.ContestPolicyView;
import com.aioj.next.ai.agent.tool.ToolBroker;
import com.aioj.next.ai.agent.tool.ToolDescriptor;
import com.aioj.next.ai.agent.tool.ToolExecutionContext;
import com.aioj.next.ai.agent.tool.ToolRegistry;
import com.aioj.next.ai.agent.tool.ToolResult;
import com.aioj.next.ai.domain.AiModelEffectiveConfig;
import com.aioj.next.ai.persistence.entity.AiAgentRunEntity;
import com.aioj.next.common.error.DomainException;
import com.aioj.next.common.error.ErrorCode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.function.Consumer;
import java.util.stream.Collectors;

/**
 * The agent loop (design doc §4.2): model call → tool execution → model call,
 * bounded by {@link LoopBudget}. Every model call is manifest-audited, every
 * tool call goes through the ToolBroker pipeline (validation → authorization →
 * execution → sanitization → audit).
 *
 * <p>tool_choice=REQUIRED is native only where the provider supports it (Kimi);
 * for other providers the runtime simulates it once (system nudge + one retry)
 * and reports {@code missed_required_tool} instead of silently pretending.</p>
 *
 * <p>Empty completions (typically thinking exhausting max_tokens, §3.4) get one
 * thinking-off recovery retry ({@link CallProfile#RECOVERY_ANSWER}); a second
 * empty response fails the run as retryable instead of storing an empty answer.</p>
 *
 * <p>For contest participants the L3 second layer (§5.3, P3-4) re-fingerprints the
 * assembled context before every model call and appends newly matched problems'
 * rules as a trailing system message that persists for the rest of the run.</p>
 */
@Service
public class AgentRuntime {

    private static final Logger log = LoggerFactory.getLogger(AgentRuntime.class);

    private final ModelGateway modelGateway;
    private final ToolRegistry toolRegistry;
    private final ToolBroker toolBroker;
    private final ContextSectionRenderer renderer;
    private final ContextManifestService manifestService;
    private final AgentRunStateMachine stateMachine;
    private final ObjectMapper objectMapper;
    private final ContextFingerprintGuard contextGuard;

    public AgentRuntime(ModelGateway modelGateway, ToolRegistry toolRegistry, ToolBroker toolBroker,
                        ContextSectionRenderer renderer, ContextManifestService manifestService,
                        AgentRunStateMachine stateMachine, ObjectMapper objectMapper,
                        ContextFingerprintGuard contextGuard) {
        this.modelGateway = modelGateway;
        this.toolRegistry = toolRegistry;
        this.toolBroker = toolBroker;
        this.renderer = renderer;
        this.manifestService = manifestService;
        this.stateMachine = stateMachine;
        this.objectMapper = objectMapper;
        this.contextGuard = contextGuard;
    }

    public record AgentRunRequest(
            String turnId,
            long userId,
            String conversationId,
            String policySnapshotId,
            Set<String> grantedScopes,
            List<ContextSection> sections,
            boolean requireToolCall,
            String outputMode,
            CallProfile profile,
            LoopBudget budget,
            ContestPolicyView contestPolicy,
            /** P3-4: the L3 message-layer verdict; its problems seed the context layer's constrained set. */
            GuardVerdict messageLayerVerdict,
            /** Optional observer for one actual provider invocation; it must never affect the loop. */
            Consumer<ModelUsage> usageObserver
    ) {
        /** Pre-P3-3 signature, kept so existing callers/tests compile unchanged. */
        public AgentRunRequest(String turnId, long userId, String conversationId, String policySnapshotId,
                               Set<String> grantedScopes, List<ContextSection> sections, boolean requireToolCall,
                               String outputMode, CallProfile profile, LoopBudget budget) {
            this(turnId, userId, conversationId, policySnapshotId, grantedScopes, sections, requireToolCall,
                    outputMode, profile, budget, null, null, null);
        }

        /** P3-3 signature (no message-layer verdict), kept for the same reason. */
        public AgentRunRequest(String turnId, long userId, String conversationId, String policySnapshotId,
                               Set<String> grantedScopes, List<ContextSection> sections, boolean requireToolCall,
                               String outputMode, CallProfile profile, LoopBudget budget,
                               ContestPolicyView contestPolicy) {
            this(turnId, userId, conversationId, policySnapshotId, grantedScopes, sections, requireToolCall,
                    outputMode, profile, budget, contestPolicy, null, null);
        }

        /** P3-4 signature before token-observer support. */
        public AgentRunRequest(String turnId, long userId, String conversationId, String policySnapshotId,
                               Set<String> grantedScopes, List<ContextSection> sections, boolean requireToolCall,
                               String outputMode, CallProfile profile, LoopBudget budget,
                               ContestPolicyView contestPolicy, GuardVerdict messageLayerVerdict) {
            this(turnId, userId, conversationId, policySnapshotId, grantedScopes, sections, requireToolCall,
                    outputMode, profile, budget, contestPolicy, messageLayerVerdict, null);
        }
    }

    public record AgentRunResult(
            long runId,
            String content,
            String provider,
            String model,
            long promptTokens,
            long completionTokens,
            long cacheHitTokens,
            int steps,
            int toolCallCount,
            boolean missedRequiredTool,
            List<String> warnings,
            /** P3-4: merged L3 message+context layer verdict, for the P3-5 L4 output guard. */
            GuardVerdict contestGuardVerdict
    ) {
        /** Pre-P3-4 signature, kept so existing callers/tests compile unchanged. */
        public AgentRunResult(long runId, String content, String provider, String model,
                              long promptTokens, long completionTokens, long cacheHitTokens,
                              int steps, int toolCallCount, boolean missedRequiredTool, List<String> warnings) {
            this(runId, content, provider, model, promptTokens, completionTokens, cacheHitTokens,
                    steps, toolCallCount, missedRequiredTool, warnings, null);
        }
    }

    public AgentRunResult run(AgentRunRequest spec) {
        AiModelEffectiveConfig config = modelGateway.chatConfig();
        List<ToolDescriptor> tools = toolRegistry.descriptorsForScopes(spec.grantedScopes());
        // C5 (frozen): problem.search only exists for contest participants — its search
        // space is the running-contest snapshot set. Non-participants never see the tool.
        ContestPolicyView contestPolicy = spec.contestPolicy();
        if (contestPolicy == null || !contestPolicy.isParticipant()) {
            tools = tools.stream()
                    .filter(descriptor -> !"problem.search".equals(descriptor.name()))
                    .toList();
        }
        LoopBudget budget = spec.budget();
        AiAgentRunEntity run = stateMachine.start(spec.turnId(), spec.conversationId(), spec.userId(),
                config.provider(), config.model(), spec.policySnapshotId(), spec.outputMode(), budget);
        UsageMeter usage = new UsageMeter();
        List<String> warnings = new ArrayList<>();
        int toolCallCount = 0;
        int searchCalls = 0;
        int fetchCalls = 0;
        int manifestSeq = 0;
        int toolSeq = 0;
        int steps = 0;
        boolean anyToolCallExecuted = false;
        boolean simulatedRetryUsed = false;
        boolean emptyRecoveryUsed = false;
        boolean toolBudgetExhausted = false;
        String finalContent = null;
        String provider = config.provider();
        String model = config.model();

        List<GatewayMessage> messages = new ArrayList<>(renderer.render(spec.sections()));
        int bootstrapMessageCount = messages.size();
        // L3 second layer state (§5.3, P3-4): problems already constrained by the
        // message layer (or an earlier step of this layer) are not re-injected.
        Set<Long> constrainedProblemIds = new LinkedHashSet<>();
        GuardVerdict contestGuardVerdict = spec.messageLayerVerdict();
        if (contestGuardVerdict != null && contestGuardVerdict.hasMatches()) {
            contestGuardVerdict.matchedProblems().forEach(ref -> {
                if (ref.problemId() != null) {
                    constrainedProblemIds.add(ref.problemId());
                }
            });
        }
        String toolDefinitionsHash = ContextManifestService.sha256(
                tools.stream().map(ToolDescriptor::name).collect(Collectors.joining(",")));
        boolean nativeRequired = modelGateway.capabilities(config).toolChoiceRequiredNative();
        ToolExecutionContext toolContext = new ToolExecutionContext(spec.userId(), spec.conversationId(),
                spec.turnId(), 0L, spec.policySnapshotId(), spec.grantedScopes(), Instant.now(), spec.turnId(),
                contestPolicy);
        stateMachine.advance(run.getId(), AgentRunStateMachine.STATUS_CONTEXT_PLANNED);

        try {
            for (int step = 1; step <= budget.maxAgentSteps(); step++) {
                steps = step;
                stateMachine.advance(run.getId(), AgentRunStateMachine.STATUS_GENERATING);
                ToolChoiceMode choice = spec.requireToolCall() && !anyToolCallExecuted && nativeRequired
                        ? ToolChoiceMode.REQUIRED
                        : ToolChoiceMode.AUTO;
                contestGuardVerdict = applyContextLayerGuard(spec, contestPolicy, constrainedProblemIds,
                        contestGuardVerdict, messages, bootstrapMessageCount, step);
                GatewayRequest request = new GatewayRequest(List.copyOf(messages), tools, choice,
                        emptyRecoveryUsed ? CallProfile.RECOVERY_ANSWER : spec.profile());
                recordManifest(spec, run.getId(), ++manifestSeq, config, request, toolDefinitionsHash, warnings);
                GatewayResponse response = modelGateway.call(config, request);
                usage.add(response);
                observeUsage(spec, response);
                if (response.provider() != null) {
                    provider = response.provider();
                }
                if (response.model() != null) {
                    model = response.model();
                }

                if (!response.hasToolCalls()) {
                    if (spec.requireToolCall() && !anyToolCallExecuted && !simulatedRetryUsed && !nativeRequired) {
                        simulatedRetryUsed = true;
                        messages.add(GatewayMessage.assistant(response.content(), List.of()));
                        messages.add(GatewayMessage.system(
                                "Server policy requires at least one relevant tool call before the final answer "
                                        + "for this turn. Call the most relevant tool now."));
                        continue;
                    }
                    if (isBlank(response.content())) {
                        if (!emptyRecoveryUsed) {
                            // Thinking can burn the whole max_tokens budget on hard turns
                            // (spike fact §3.4): retry once with thinking off. The empty
                            // assistant message is deliberately not appended to the context.
                            emptyRecoveryUsed = true;
                            warnings.add("empty_completion_recovery");
                            log.warn("Agent run got an empty completion turn={} run={} step={} finishReason={}",
                                    spec.turnId(), run.getId(), step, response.finishReason());
                            messages.add(GatewayMessage.system(
                                    "Your previous reply was empty. Provide the complete final answer "
                                            + "to the user now, in the user's language."));
                            continue;
                        }
                        throw new DomainException(ErrorCode.SERVICE_UNAVAILABLE,
                                "AI provider returned an empty response");
                    }
                    finalContent = response.content();
                    break;
                }

                messages.add(GatewayMessage.assistant(response.content(), response.toolCalls()));
                stateMachine.advance(run.getId(), AgentRunStateMachine.STATUS_TOOL_CALLING);
                for (GatewayToolCall call : response.toolCalls()) {
                    if (toolCallCount >= budget.maxToolCalls()) {
                        toolBudgetExhausted = true;
                        break;
                    }
                    if (budget.categoryExhausted(call.name(), searchCalls, fetchCalls)) {
                        messages.add(GatewayMessage.toolResult(call.callId(), budgetExceededPayload(call.name())));
                        continue;
                    }
                    ToolResult<String> result = toolBroker.execute(toolContext, run.getId(), ++toolSeq,
                            call.callId(), call.name(), call.argumentsJson());
                    toolCallCount++;
                    anyToolCallExecuted = true;
                    switch (budget.categoryOf(call.name())) {
                        case "search" -> searchCalls++;
                        case "fetch" -> fetchCalls++;
                        default -> {
                        }
                    }
                    messages.add(GatewayMessage.toolResult(call.callId(), result.data()));
                }
                stateMachine.recordProgress(run.getId(), step, toolCallCount);
                if (toolBudgetExhausted) {
                    warnings.add("tool_budget_exhausted");
                    break;
                }
            }

            if (finalContent == null) {
                // Step or tool budget exhausted: one final answer-only call without tools.
                if (!toolBudgetExhausted) {
                    warnings.add("step_budget_exhausted");
                }
                stateMachine.advance(run.getId(), AgentRunStateMachine.STATUS_GENERATING);
                contestGuardVerdict = applyContextLayerGuard(spec, contestPolicy, constrainedProblemIds,
                        contestGuardVerdict, messages, bootstrapMessageCount, steps + 1);
                GatewayRequest finalRequest = new GatewayRequest(List.copyOf(messages), List.of(),
                        ToolChoiceMode.AUTO, CallProfile.RECOVERY_ANSWER);
                recordManifest(spec, run.getId(), ++manifestSeq, config, finalRequest, toolDefinitionsHash, warnings);
                GatewayResponse finalResponse = modelGateway.call(config, finalRequest);
                usage.add(finalResponse);
                observeUsage(spec, finalResponse);
                if (finalResponse.provider() != null) {
                    provider = finalResponse.provider();
                }
                if (finalResponse.model() != null) {
                    model = finalResponse.model();
                }
                finalContent = finalResponse.content();
                if (isBlank(finalContent)) {
                    throw new DomainException(ErrorCode.SERVICE_UNAVAILABLE,
                            "AI provider returned an empty response");
                }
            }

            boolean missedRequired = spec.requireToolCall() && !anyToolCallExecuted;
            if (missedRequired) {
                warnings.add("missed_required_tool");
                log.warn("Agent run missed required tool call turn={} run={}", spec.turnId(), run.getId());
            }
            stateMachine.advance(run.getId(), AgentRunStateMachine.STATUS_FINAL_DRAFTED);
            // P0 output check is a pass-through marker; the real buffered check lands with P3 output modes.
            stateMachine.advance(run.getId(), AgentRunStateMachine.STATUS_OUTPUT_CHECKED);
            stateMachine.complete(run.getId(), steps, toolCallCount);
            return new AgentRunResult(run.getId(), finalContent, provider, model,
                    usage.promptTokens(), usage.completionTokens(), usage.cacheHitTokens(),
                    steps, toolCallCount, missedRequired, List.copyOf(warnings), contestGuardVerdict);
        } catch (RuntimeException ex) {
            stateMachine.fail(run.getId(), errorCodeOf(ex), steps, toolCallCount);
            throw ex;
        }
    }

    /**
     * L3 second layer (§5.3, P3-4): re-fingerprint the assembled messages right
     * before a model call. Newly matched problems' rules are appended as one
     * trailing system message, which then persists in the list for every later
     * step of this run. Returns the merged verdict for L4 (P3-5).
     */
    private GuardVerdict applyContextLayerGuard(AgentRunRequest spec, ContestPolicyView contestPolicy,
                                                Set<Long> constrainedProblemIds, GuardVerdict mergedVerdict,
                                                List<GatewayMessage> messages, int bootstrapMessageCount,
                                                int agentStep) {
        ContextFingerprintGuard.Evaluation evaluation = contextGuard.evaluate(spec.turnId(), spec.userId(),
                spec.conversationId(), contestPolicy, constrainedProblemIds, messages, bootstrapMessageCount, agentStep);
        if (evaluation == null) {
            return mergedVerdict;
        }
        if (evaluation.injectionText() != null) {
            messages.add(GatewayMessage.system(evaluation.injectionText()));
            constrainedProblemIds.addAll(evaluation.newlyMatchedProblemIds());
        }
        return mergedVerdict == null ? evaluation.verdict() : mergedVerdict.mergedWith(evaluation.verdict());
    }

    private void observeUsage(AgentRunRequest spec, GatewayResponse response) {
        if (spec.usageObserver() == null) {
            return;
        }
        try {
            spec.usageObserver().accept(ModelUsage.from(response));
        } catch (RuntimeException ex) {
            // Statistical observation must never change the Agent's control flow.
            log.warn("agent model-usage observer failed turn={} error={}", spec.turnId(), ex.toString());
        }
    }

    private void recordManifest(AgentRunRequest spec, long runId, int callSeq, AiModelEffectiveConfig config,
                                GatewayRequest request, String toolDefinitionsHash, List<String> warnings) {
        StringBuilder contextMaterial = new StringBuilder();
        for (GatewayMessage message : request.messages()) {
            contextMaterial.append(message.role()).append('\n');
            if (message.content() != null) {
                contextMaterial.append(message.content());
            }
            contextMaterial.append('\n');
        }
        manifestService.record(spec.turnId(), runId, callSeq, config.model(), BootstrapContextBuilder.PROMPT_VERSION,
                spec.policySnapshotId(), spec.sections(), toolDefinitionsHash,
                ContextManifestService.sha256(contextMaterial.toString()), null, null, warnings);
    }

    /** Category budget overflow is returned to the model as data, never executed. */
    private String budgetExceededPayload(String toolName) {
        ObjectNode payload = objectMapper.createObjectNode();
        payload.put("status", "BUDGET_EXCEEDED");
        payload.putNull("data");
        payload.put("tool", toolName == null ? "" : toolName);
        payload.put("instructionAllowed", false);
        payload.putArray("warnings").add("tool category budget exhausted; answer with what you have");
        return payload.toString();
    }

    private String errorCodeOf(RuntimeException ex) {
        if (ex instanceof DomainException domainException) {
            return domainException.errorCode().name();
        }
        return "AGENT_RUN_FAILURE";
    }

    private boolean isBlank(String value) {
        return value == null || value.isBlank();
    }
}
