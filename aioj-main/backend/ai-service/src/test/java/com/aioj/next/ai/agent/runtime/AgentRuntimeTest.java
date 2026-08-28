package com.aioj.next.ai.agent.runtime;

import com.aioj.next.ai.agent.context.ContextManifestService;
import com.aioj.next.ai.agent.context.ContextSection;
import com.aioj.next.ai.agent.context.ContextSectionRenderer;
import com.aioj.next.ai.agent.context.ContextSectionType;
import com.aioj.next.ai.agent.context.DataClassification;
import com.aioj.next.ai.agent.context.TrustLevel;
import com.aioj.next.ai.agent.guard.ContextFingerprintGuard;
import com.aioj.next.ai.agent.guard.GuardVerdict;
import com.aioj.next.ai.agent.guard.ProblemFingerprintMatcher;
import com.aioj.next.ai.agent.model.CallProfile;
import com.aioj.next.ai.agent.model.CallSettings;
import com.aioj.next.ai.agent.model.GatewayMessage;
import com.aioj.next.ai.agent.model.GatewayRequest;
import com.aioj.next.ai.agent.model.GatewayResponse;
import com.aioj.next.ai.agent.model.GatewayToolCall;
import com.aioj.next.ai.agent.model.ModelGateway;
import com.aioj.next.ai.agent.model.ModelUsage;
import com.aioj.next.ai.agent.model.ProviderCapabilities;
import com.aioj.next.ai.agent.model.ToolCallAdapter;
import com.aioj.next.ai.agent.model.ToolChoiceMode;
import com.aioj.next.ai.agent.policy.ContestPolicyView;
import com.aioj.next.ai.agent.policy.GuardDecision;
import com.aioj.next.ai.agent.policy.GuardDecisionRecorder;
import com.aioj.next.ai.agent.policy.GuardLayer;
import com.aioj.next.ai.agent.policy.ParticipantStatus;
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
import com.aioj.next.ai.domain.AiModelConfigService;
import com.aioj.next.ai.domain.AiModelEffectiveConfig;
import com.aioj.next.ai.domain.AiModelScope;
import com.aioj.next.ai.config.AiProperties;
import com.aioj.next.ai.persistence.mapper.AiAgentRunMapper;
import com.aioj.next.common.error.DomainException;
import com.aioj.next.common.error.ErrorCode;
import com.aioj.next.contract.contest.ContestAiPolicyMode;
import com.aioj.next.contract.contest.RunningContestProblemOccurrence;
import com.aioj.next.contract.problem.ProblemVisibility;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Queue;
import java.util.Set;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.atLeastOnce;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

class AgentRuntimeTest {

    private static final String CONTEST_STATEMENT = """
            星港间距
            题目描述
            在遥远的星系中，有 n 个星港排成一条直线，第 i 个星港的坐标为 xi。
            你需要选择恰好 m 个星港建立补给站，使得任意两个相邻补给站之间的最小距离最大化。
            输入格式
            第一行两个整数 n 和 m。
            第二行 n 个整数 x1 x2 ... xn。
            输出格式
            输出一个整数，表示最大化后的最小距离。
            样例输入
            5 3
            1 2 8 4 9
            样例输出
            3
            """;

    private final ObjectMapper objectMapper = new ObjectMapper();
    private final AiModelConfigService configService = mock(AiModelConfigService.class);
    private final ContextManifestService manifestService = mock(ContextManifestService.class);
    private final ToolAuditService auditService = mock(ToolAuditService.class);
    private final AiAgentRunMapper runMapper = mock(AiAgentRunMapper.class);
    private final ContextFingerprintGuard contextGuard = mock(ContextFingerprintGuard.class);
    private final GuardDecisionRecorder guardRecorder = mock(GuardDecisionRecorder.class);

    private FakeAdapter adapter;
    private AtomicInteger toolExecutions;
    private AgentRuntime runtime;

    @BeforeEach
    void setUp() throws Exception {
        adapter = new FakeAdapter();
        toolExecutions = new AtomicInteger();
        when(configService.effectiveConfig(AiModelScope.TEXT_GENERATION)).thenReturn(config());
        ModelGateway gateway = new ModelGateway(configService, List.of(adapter));
        ToolRegistry registry = new ToolRegistry(List.of(searchTool()));
        ToolBroker broker = new ToolBroker(registry, new ToolAuthorizationService(),
                new ToolResultSanitizer(objectMapper), auditService, objectMapper);
        runtime = new AgentRuntime(gateway, registry, broker, new ContextSectionRenderer(),
                manifestService, new AgentRunStateMachine(runMapper, objectMapper), objectMapper, contextGuard);
    }

    @Test
    void toolCallExecutesThroughBrokerAndLoopTerminatesWithFinalAnswer() {
        adapter.script.add(toolCallResponse());
        adapter.script.add(finalResponse("基于搜索结果：二分边界要注意 +1。"));

        AgentRuntime.AgentRunResult result = runtime.run(spec(false, budget(8, 6, 3, 3)));

        assertThat(result.content()).contains("二分边界");
        assertThat(result.toolCallCount()).isEqualTo(1);
        assertThat(result.steps()).isEqualTo(2);
        assertThat(result.missedRequiredTool()).isFalse();
        assertThat(toolExecutions.get()).isEqualTo(1);
        // Second model call received the sanitized tool payload as a tool message.
        GatewayRequest secondCall = adapter.requests.get(1);
        assertThat(secondCall.messages()).anySatisfy(message -> {
            assertThat(message.role()).isEqualTo("tool");
            assertThat(message.toolCallId()).isEqualTo("call-1");
            assertThat(message.content()).contains("instructionAllowed").contains("二分");
        });
        // Every model call is manifest-audited.
        verify(manifestService, times(2)).record(anyString(), anyLong(), anyInt(), anyString(), anyString(),
                anyString(), anyList(), anyString(), anyString(), isNull(), isNull(), anyList());
    }

    @Test
    void usageObserverReceivesEveryActualModelInvocation() {
        adapter.script.add(toolCallResponse());
        adapter.script.add(finalResponse("最终回答"));
        List<ModelUsage> observed = new ArrayList<>();
        AgentRuntime.AgentRunRequest request = new AgentRuntime.AgentRunRequest(
                "turn-1", 7L, "c1", "ps-1", Set.of("AI_CHAT"), sections(), false,
                "STREAM", CallProfile.CHAT_STREAM, budget(8, 6, 3, 3), null, null, observed::add);

        runtime.run(request);

        assertThat(observed).hasSize(2);
        assertThat(observed).allSatisfy(usage -> {
            assertThat(usage.promptTokens()).isEqualTo(10L);
            assertThat(usage.completionTokens()).isEqualTo(5L);
            assertThat(usage.reported()).isTrue();
        });
    }

    @Test
    void requiredToolCallIsSimulatedForDeepSeekAndSucceedsAfterNudge() {
        adapter.script.add(finalResponse("不需要工具")); // model initially refuses to call a tool
        adapter.script.add(toolCallResponse());
        adapter.script.add(finalResponse("最终回答"));

        AgentRuntime.AgentRunResult result = runtime.run(spec(true, budget(8, 6, 3, 3)));

        assertThat(result.content()).isEqualTo("最终回答");
        assertThat(result.missedRequiredTool()).isFalse();
        assertThat(result.warnings()).doesNotContain("missed_required_tool");
        // DeepSeek is never sent tool_choice=required: runtime simulates with a system nudge.
        assertThat(adapter.requests).allSatisfy(request -> assertThat(request.toolChoice()).isEqualTo(ToolChoiceMode.AUTO));
        GatewayRequest retry = adapter.requests.get(1);
        assertThat(retry.messages()).anySatisfy(message -> {
            assertThat(message.role()).isEqualTo("system");
            assertThat(message.content()).contains("requires at least one relevant tool call");
        });
    }

    @Test
    void missedRequiredToolIsReportedWhenModelNeverCalls() {
        adapter.script.add(finalResponse("直接回答"));
        adapter.script.add(finalResponse("还是直接回答"));

        AgentRuntime.AgentRunResult result = runtime.run(spec(true, budget(8, 6, 3, 3)));

        assertThat(result.missedRequiredTool()).isTrue();
        assertThat(result.warnings()).contains("missed_required_tool");
        assertThat(result.toolCallCount()).isZero();
    }

    @Test
    void toolBudgetExhaustionStopsExecutionAndForcesAnswer() {
        adapter.script.add(new GatewayResponse("", List.of(
                        new GatewayToolCall("call-1", "context.search_exact", "{\"exactTerms\":[\"a\"]}"),
                        new GatewayToolCall("call-2", "context.search_exact", "{\"exactTerms\":[\"b\"]}")),
                "tool_calls", 10, 5, 0, "deepseek", "deepseek-v4-pro"));
        adapter.script.add(finalResponse("预算耗尽后的尽力回答"));

        AgentRuntime.AgentRunResult result = runtime.run(spec(false, budget(8, 1, 3, 3)));

        assertThat(result.content()).isEqualTo("预算耗尽后的尽力回答");
        assertThat(result.toolCallCount()).isEqualTo(1);
        assertThat(toolExecutions.get()).isEqualTo(1);
        assertThat(result.warnings()).contains("tool_budget_exhausted");
        // The final answer call is made without tools.
        GatewayRequest lastCall = adapter.requests.get(adapter.requests.size() - 1);
        assertThat(lastCall.tools()).isEmpty();
    }

    @Test
    void categoryBudgetOverflowIsReturnedToModelAsData() {
        adapter.script.add(toolCallResponse());
        adapter.script.add(finalResponse("没有搜索配额了"));

        AgentRuntime.AgentRunResult result = runtime.run(spec(false, budget(8, 6, 0, 3)));

        assertThat(result.content()).isEqualTo("没有搜索配额了");
        assertThat(toolExecutions.get()).isZero();
        assertThat(result.toolCallCount()).isZero();
        GatewayRequest secondCall = adapter.requests.get(1);
        assertThat(secondCall.messages()).anySatisfy(message -> {
            assertThat(message.role()).isEqualTo("tool");
            assertThat(message.content()).contains("BUDGET_EXCEEDED");
        });
    }

    @Test
    void emptyCompletionGetsOneThinkingOffRecoveryRetry() {
        adapter.script.add(emptyResponse("length"));
        adapter.script.add(finalResponse("恢复后的完整回答"));

        AgentRuntime.AgentRunResult result = runtime.run(spec(false, budget(8, 6, 3, 3)));

        assertThat(result.content()).isEqualTo("恢复后的完整回答");
        assertThat(result.warnings()).contains("empty_completion_recovery");
        // The recovery retry runs with the thinking-off recovery profile.
        assertThat(adapter.requests.get(1).profile()).isEqualTo(CallProfile.RECOVERY_ANSWER);
        assertThat(adapter.requests.get(1).messages()).anySatisfy(message -> {
            assertThat(message.role()).isEqualTo("system");
            assertThat(message.content()).contains("previous reply was empty");
        });
    }

    @Test
    void repeatedEmptyCompletionFailsTheRunAsRetryable() {
        adapter.script.add(emptyResponse("length"));
        adapter.script.add(emptyResponse("length"));

        assertThatThrownBy(() -> runtime.run(spec(false, budget(8, 6, 3, 3))))
                .isInstanceOf(DomainException.class)
                .satisfies(ex -> assertThat(((DomainException) ex).errorCode())
                        .isEqualTo(ErrorCode.SERVICE_UNAVAILABLE));
    }

    @Test
    void budgetExhaustedFinalAnswerUsesRecoveryProfileAndRejectsBlank() {
        adapter.script.add(new GatewayResponse("", List.of(
                        new GatewayToolCall("call-1", "context.search_exact", "{\"exactTerms\":[\"a\"]}"),
                        new GatewayToolCall("call-2", "context.search_exact", "{\"exactTerms\":[\"b\"]}")),
                "tool_calls", 10, 5, 0, "deepseek", "deepseek-v4-pro"));
        adapter.script.add(emptyResponse("length"));

        assertThatThrownBy(() -> runtime.run(spec(false, budget(8, 1, 3, 3))))
                .isInstanceOf(DomainException.class)
                .satisfies(ex -> assertThat(((DomainException) ex).errorCode())
                        .isEqualTo(ErrorCode.SERVICE_UNAVAILABLE));
        GatewayRequest finalCall = adapter.requests.get(adapter.requests.size() - 1);
        assertThat(finalCall.tools()).isEmpty();
        assertThat(finalCall.profile()).isEqualTo(CallProfile.RECOVERY_ANSWER);
    }

    @Test
    void nonParticipantTurnNeverSeesProblemSearchTool() throws Exception {
        AgentRuntime runtimeWithProblemTool = runtimeWith(List.of(searchTool(), problemSearchTool(new AtomicReference<>())));
        adapter.script.add(finalResponse("无搜索工具的回答"));
        adapter.script.add(finalResponse("空策略视图同样过滤"));

        runtimeWithProblemTool.run(spec(false, budget(8, 6, 3, 3)));
        AgentRuntime.AgentRunRequest nonParticipantViewSpec = new AgentRuntime.AgentRunRequest(
                "turn-2", 7L, "c1", "ps-1", Set.of("AI_CHAT"), sections(), false, "STREAM",
                CallProfile.CHAT_STREAM, budget(8, 6, 3, 3), ContestPolicyView.nonParticipant());
        runtimeWithProblemTool.run(nonParticipantViewSpec);

        assertThat(adapter.requests.get(0).tools()).extracting(ToolDescriptor::name)
                .containsExactly("context.search_exact");
        assertThat(adapter.requests.get(1).tools()).extracting(ToolDescriptor::name)
                .containsExactly("context.search_exact");
    }

    @Test
    void participantTurnKeepsProblemSearchAndThreadsContestPolicy() throws Exception {
        AtomicReference<ToolExecutionContext> seenContext = new AtomicReference<>();
        AgentRuntime runtimeWithProblemTool = runtimeWith(List.of(problemSearchTool(seenContext)));
        adapter.script.add(new GatewayResponse("", List.of(
                        new GatewayToolCall("call-1", "problem.search", "{\"query\":\"二分\"}")),
                "tool_calls", 10, 5, 0, "deepseek", "deepseek-v4-pro"));
        adapter.script.add(finalResponse("基于比赛题面的回答"));
        ContestPolicyView view = new ContestPolicyView(ParticipantStatus.PARTICIPANT_ACTIVE, Map.of());

        AgentRuntime.AgentRunResult result = runtimeWithProblemTool.run(new AgentRuntime.AgentRunRequest(
                "turn-1", 7L, "c1", "ps-1", Set.of("AI_CHAT"), sections(), false, "STREAM",
                CallProfile.CHAT_STREAM, budget(8, 6, 3, 3), view));

        assertThat(result.content()).isEqualTo("基于比赛题面的回答");
        assertThat(adapter.requests.get(0).tools()).extracting(ToolDescriptor::name)
                .containsExactly("problem.search");
        assertThat(seenContext.get().contestPolicy()).isSameAs(view);
    }

    @Test
    void historyStatementPasteInjectsConstraintBeforeFirstModelCall() throws Exception {
        AgentRuntime guarded = runtimeWith(List.of(searchTool()), realContextGuard());
        adapter.script.add(finalResponse("只能给思路，不能给完整代码"));

        AgentRuntime.AgentRunResult result = guarded.run(participantSpec(sectionsWithHistory(), null));

        // L3 second layer (§5.3): the history paste bypassed the message layer, so the
        // assembled-context match injects the rules before the very first model call.
        assertThat(adapter.requests.get(0).messages()).anySatisfy(message -> {
            assertThat(message.role()).isEqualTo("system");
            assertThat(message.content())
                    .contains("[Contest Guard Match — server fingerprint result for the assembled context; enforce]")
                    .contains("Problem #1002 (PRIVATE, policy DEFAULT)");
        });
        assertThat(result.contestGuardVerdict()).isNotNull();
        assertThat(result.contestGuardVerdict().hasMatches()).isTrue();
        verify(guardRecorder).record(anyString(), anyLong(), anyString(), eq(GuardLayer.L3_FINGERPRINT_CTX),
                eq(GuardDecision.CONSTRAIN), anyList(), eq("fingerprint_match"), any(), eq(false), any());
    }

    @Test
    void toolResultStatementInjectsOnceAndPersistsAcrossLaterSteps() throws Exception {
        AgentRuntime guarded = runtimeWith(List.of(statementTool()), realContextGuard());
        adapter.script.add(toolCallResponse());
        adapter.script.add(toolCallResponse());
        adapter.script.add(finalResponse("最终回答"));

        AgentRuntime.AgentRunResult result = guarded.run(participantSpec(sections(), null));

        assertThat(result.steps()).isEqualTo(3);
        // First call predates the tool result: no injection yet.
        assertThat(adapter.requests.get(0).messages())
                .noneMatch(message -> message.content() != null && message.content().contains("Contest Guard Match"));
        // The injection is appended once and then stays for every later step.
        assertThat(adapter.requests.get(1).messages().stream()
                .filter(message -> message.content() != null && message.content().contains("assembled context")).count())
                .isEqualTo(1);
        assertThat(adapter.requests.get(2).messages().stream()
                .filter(message -> message.content() != null && message.content().contains("assembled context")).count())
                .isEqualTo(1);
        // Every model call ran the context layer: 1 PASS + 2 CONSTRAIN audits (§5.6).
        verify(guardRecorder, times(1)).record(anyString(), anyLong(), anyString(), eq(GuardLayer.L3_FINGERPRINT_CTX),
                eq(GuardDecision.PASS), anyList(), anyString(), any(), eq(false), any());
        verify(guardRecorder, times(2)).record(anyString(), anyLong(), anyString(), eq(GuardLayer.L3_FINGERPRINT_CTX),
                eq(GuardDecision.CONSTRAIN), anyList(), anyString(), any(), eq(false), any());
    }

    @Test
    void nonParticipantSkipsContextGuardWithoutAudit() throws Exception {
        AgentRuntime guarded = runtimeWith(List.of(searchTool()), realContextGuard());
        adapter.script.add(finalResponse("普通练习回答"));

        AgentRuntime.AgentRunResult result = guarded.run(spec(false, budget(8, 6, 3, 3)));

        assertThat(result.contestGuardVerdict()).isNull();
        assertThat(adapter.requests.get(0).messages())
                .noneMatch(message -> message.content() != null && message.content().contains("Contest Guard Match"));
        verifyNoInteractions(guardRecorder);
    }

    @Test
    void messageLayerVerdictSeedsConstrainedSetAndSkipsReinjection() throws Exception {
        AgentRuntime guarded = runtimeWith(List.of(searchTool()), realContextGuard());
        adapter.script.add(finalResponse("按第一层规则处理"));
        GuardVerdict messageVerdict = GuardVerdict.constrain(List.of(
                new GuardDecisionRecorder.MatchedProblemRef(1002L, 5501L, 7701L, 99001L, "PRIVATE", "DEFAULT")), 0.9);

        AgentRuntime.AgentRunResult result = guarded.run(participantSpec(sectionsWithHistory(), messageVerdict));

        // The context layer still matches and audits, but the problem the message
        // layer already constrained is not re-injected (dedup by problemId).
        assertThat(adapter.requests.get(0).messages())
                .noneMatch(message -> message.content() != null && message.content().contains("assembled context"));
        verify(guardRecorder).record(anyString(), anyLong(), anyString(), eq(GuardLayer.L3_FINGERPRINT_CTX),
                eq(GuardDecision.CONSTRAIN), anyList(), eq("fingerprint_match"), any(), eq(false), any());
        // The merged turn verdict (message ∪ context layer) rides the result for P3-5 L4.
        assertThat(result.contestGuardVerdict().hasMatches()).isTrue();
        assertThat(result.contestGuardVerdict().matchedProblems())
                .extracting(GuardDecisionRecorder.MatchedProblemRef::problemId)
                .containsExactly(1002L);
    }

    private AgentRuntime.AgentRunRequest participantSpec(List<ContextSection> sections, GuardVerdict messageVerdict) {
        return new AgentRuntime.AgentRunRequest("turn-1", 7L, "c1", "ps-1", Set.of("AI_CHAT"), sections, false,
                "STREAM", CallProfile.CHAT_STREAM, budget(8, 6, 3, 3), participantView(), messageVerdict);
    }

    private ContestPolicyView participantView() {
        return new ContestPolicyView(ParticipantStatus.PARTICIPANT_ACTIVE, Map.of(1002L,
                new ContestPolicyView.ContestProblemPolicy(1002L, ProblemVisibility.PRIVATE, ContestAiPolicyMode.DEFAULT,
                        null, CONTEST_STATEMENT, List.of(new RunningContestProblemOccurrence(5501L, 7701L, 99001L)))));
    }

    private List<ContextSection> sectionsWithHistory() {
        return List.of(
                ContextSection.text(ContextSectionType.SYSTEM_POLICY, 10, true, TrustLevel.SYSTEM_POLICY, "sys"),
                ContextSection.conversation(ContextSectionType.RECENT_TURNS, 40, TrustLevel.USER_PROVIDED,
                        List.of(GatewayMessage.user("之前那题：" + CONTEST_STATEMENT),
                                GatewayMessage.assistant("先读题面", List.of())),
                        List.of()),
                ContextSection.text(ContextSectionType.CURRENT_USER_REQUEST, 90, true, TrustLevel.USER_PROVIDED, "继续"));
    }

    private AgentTool statementTool() throws Exception {
        // Same descriptor shape as searchTool, but the payload carries a running-contest
        // statement (fetched original entering the context as a tool result).
        JsonNode schema = objectMapper.readTree("""
                {"type":"object","additionalProperties":false,"required":["exactTerms"],
                 "properties":{"exactTerms":{"type":"array","items":{"type":"string","minLength":1}}}}
                """);
        ToolDescriptor descriptor = new ToolDescriptor("context.search_exact", "1.0.0", "search", schema,
                ToolRiskLevel.LOW, true, true, Set.of("AI_CHAT"), Set.of(DataClassification.USER_PRIVATE),
                2000, Duration.ofSeconds(5), ToolAuditLevel.FULL);
        return new AgentTool() {
            @Override
            public ToolDescriptor descriptor() {
                return descriptor;
            }

            @Override
            public ToolResult<Object> execute(ToolExecutionContext context, JsonNode input) {
                toolExecutions.incrementAndGet();
                return ToolResult.success(null, Map.of("statement", CONTEST_STATEMENT),
                        List.of(), DataClassification.USER_PRIVATE, TrustLevel.USER_PROVIDED);
            }
        };
    }

    private GatewayResponse emptyResponse(String finishReason) {
        return new GatewayResponse("", List.of(), finishReason, 10, 5, 0, "deepseek", "deepseek-v4-pro");
    }

    private AgentRuntime.AgentRunRequest spec(boolean requireToolCall, LoopBudget budget) {
        return new AgentRuntime.AgentRunRequest("turn-1", 7L, "c1", "ps-1", Set.of("AI_CHAT"),
                sections(), requireToolCall, "STREAM", CallProfile.CHAT_STREAM, budget);
    }

    private List<ContextSection> sections() {
        return List.of(
                ContextSection.text(ContextSectionType.SYSTEM_POLICY, 10, true, TrustLevel.SYSTEM_POLICY, "sys"),
                ContextSection.text(ContextSectionType.CURRENT_USER_REQUEST, 90, true, TrustLevel.USER_PROVIDED, "查一下二分的讨论"));
    }

    private AgentRuntime runtimeWith(List<AgentTool> tools) {
        return runtimeWith(tools, contextGuard);
    }

    private AgentRuntime runtimeWith(List<AgentTool> tools, ContextFingerprintGuard guard) {
        ModelGateway gateway = new ModelGateway(configService, List.of(adapter));
        ToolRegistry registry = new ToolRegistry(tools);
        ToolBroker broker = new ToolBroker(registry, new ToolAuthorizationService(),
                new ToolResultSanitizer(objectMapper), auditService, objectMapper);
        return new AgentRuntime(gateway, registry, broker, new ContextSectionRenderer(),
                manifestService, new AgentRunStateMachine(runMapper, objectMapper), objectMapper, guard);
    }

    /** Real L3 context layer on top of a mock audit recorder, for the P3-4 tests. */
    private ContextFingerprintGuard realContextGuard() {
        return new ContextFingerprintGuard(new ProblemFingerprintMatcher(new AiProperties()), guardRecorder);
    }

    private AgentTool problemSearchTool(AtomicReference<ToolExecutionContext> seenContext) throws Exception {
        JsonNode schema = objectMapper.readTree("""
                {"type":"object","additionalProperties":false,"required":["query"],
                 "properties":{"query":{"type":"string","minLength":1}}}
                """);
        ToolDescriptor descriptor = new ToolDescriptor("problem.search", "1.0.0", "contest problem search", schema,
                ToolRiskLevel.LOW, true, true, Set.of("AI_CHAT"), Set.of(DataClassification.CONTEST_PUBLIC_ACTIVE),
                2000, Duration.ofSeconds(5), ToolAuditLevel.FULL);
        return new AgentTool() {
            @Override
            public ToolDescriptor descriptor() {
                return descriptor;
            }

            @Override
            public ToolResult<Object> execute(ToolExecutionContext context, JsonNode input) {
                seenContext.set(context);
                toolExecutions.incrementAndGet();
                return ToolResult.success(null, Map.of("hits", List.of()), List.of(),
                        DataClassification.CONTEST_PUBLIC_ACTIVE, TrustLevel.USER_PROVIDED);
            }
        };
    }

    private LoopBudget budget(int steps, int toolCalls, int search, int fetch) {
        return new LoopBudget(steps, toolCalls, search, fetch);
    }

    private GatewayResponse toolCallResponse() {
        return new GatewayResponse("", List.of(
                new GatewayToolCall("call-1", "context.search_exact", "{\"exactTerms\":[\"二分\"]}")),
                "tool_calls", 10, 5, 0, "deepseek", "deepseek-v4-pro");
    }

    private GatewayResponse finalResponse(String content) {
        return new GatewayResponse(content, List.of(), "stop", 10, 5, 0, "deepseek", "deepseek-v4-pro");
    }

    private AiModelEffectiveConfig config() {
        return new AiModelEffectiveConfig(AiModelScope.TEXT_GENERATION, true, false, "DATABASE",
                "deepseek", "https://api.deepseek.com/chat/completions", "sk-test", "sk-***", "environment",
                "DEEPSEEK_API_KEY", "deepseek-v4-pro", false, false, "high", 0.3, 4096, null, null, null);
    }

    private AgentTool searchTool() throws Exception {
        JsonNode schema = objectMapper.readTree("""
                {"type":"object","additionalProperties":false,"required":["exactTerms"],
                 "properties":{"exactTerms":{"type":"array","items":{"type":"string","minLength":1}}}}
                """);
        ToolDescriptor descriptor = new ToolDescriptor("context.search_exact", "1.0.0", "search", schema,
                ToolRiskLevel.LOW, true, true, Set.of("AI_CHAT"), Set.of(DataClassification.USER_PRIVATE),
                2000, Duration.ofSeconds(5), ToolAuditLevel.FULL);
        return new AgentTool() {
            @Override
            public ToolDescriptor descriptor() {
                return descriptor;
            }

            @Override
            public ToolResult<Object> execute(ToolExecutionContext context, JsonNode input) {
                toolExecutions.incrementAndGet();
                return ToolResult.success(null, Map.of("hits", List.of(Map.of("excerpt", "二分查找的讨论"))),
                        List.of(), DataClassification.USER_PRIVATE, TrustLevel.USER_PROVIDED);
            }
        };
    }

    private static final class FakeAdapter implements ToolCallAdapter {
        private final Queue<GatewayResponse> script = new ArrayDeque<>();
        private final List<GatewayRequest> requests = new ArrayList<>();

        @Override
        public String provider() {
            return "deepseek";
        }

        @Override
        public ProviderCapabilities capabilities() {
            return ProviderCapabilities.deepSeek();
        }

        @Override
        public GatewayResponse execute(AiModelEffectiveConfig config, CallSettings settings, GatewayRequest request) {
            requests.add(request);
            return script.remove();
        }
    }
}
