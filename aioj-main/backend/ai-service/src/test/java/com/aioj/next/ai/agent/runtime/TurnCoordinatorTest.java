package com.aioj.next.ai.agent.runtime;

import com.aioj.next.ai.agent.AgentChatFacade.TurnHandle;
import com.aioj.next.ai.agent.AgentChatFacade.TurnResult;
import com.aioj.next.ai.agent.context.BootstrapContextBuilder;
import com.aioj.next.ai.agent.context.ContextSection;
import com.aioj.next.ai.agent.context.ContextSectionType;
import com.aioj.next.ai.agent.context.TrustLevel;
import com.aioj.next.ai.agent.policy.PolicySnapshotService;
import com.aioj.next.ai.config.AiProperties;
import com.aioj.next.ai.domain.AiCompletion;
import com.aioj.next.ai.domain.AiConversationService;
import com.aioj.next.ai.domain.AiQuotaService;
import com.aioj.next.ai.domain.AiTurnService;
import com.aioj.next.ai.domain.response.AiAssistantResponseNormalizer;
import com.aioj.next.ai.persistence.entity.AiConversationEntity;
import com.aioj.next.ai.persistence.entity.AiTurnEntity;
import com.aioj.next.contract.ai.AiChatMessageResponse;
import com.aioj.next.contract.ai.AiChatRequest;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class TurnCoordinatorTest {

    private static final Long USER_ID = 7L;
    private static final String CONVERSATION_ID = "c-agent-turn";

    private final AgentRuntime agentRuntime = mock(AgentRuntime.class);
    private final BootstrapContextBuilder bootstrapBuilder = mock(BootstrapContextBuilder.class);
    private final PolicySnapshotService policySnapshotService = mock(PolicySnapshotService.class);
    private final com.aioj.next.ai.agent.policy.ContestParticipationService participationService =
            mock(com.aioj.next.ai.agent.policy.ContestParticipationService.class);
    private final com.aioj.next.ai.agent.guard.ProblemFingerprintMatcher fingerprintMatcher =
            mock(com.aioj.next.ai.agent.guard.ProblemFingerprintMatcher.class);
    private final com.aioj.next.ai.agent.policy.GuardDecisionRecorder guardDecisionRecorder =
            mock(com.aioj.next.ai.agent.policy.GuardDecisionRecorder.class);
    private final com.aioj.next.ai.agent.guard.ContestOutputGuard contestOutputGuard =
            mock(com.aioj.next.ai.agent.guard.ContestOutputGuard.class);
    private final AiQuotaService quotaService = mock(AiQuotaService.class);
    private final AiConversationService conversationService = mock(AiConversationService.class);
    private final AiAssistantResponseNormalizer responseNormalizer = mock(AiAssistantResponseNormalizer.class);
    private final AiTurnService turnService = mock(AiTurnService.class);
    private final com.aioj.next.ai.agent.digest.TurnDigestService turnDigestService =
            mock(com.aioj.next.ai.agent.digest.TurnDigestService.class);
    private final com.aioj.next.ai.agent.understanding.TurnUnderstandingService turnUnderstandingService =
            mock(com.aioj.next.ai.agent.understanding.TurnUnderstandingService.class);

    private TurnCoordinator coordinator;

    @BeforeEach
    void setUp() {
        when(turnUnderstandingService.assess(anyString(), org.mockito.ArgumentMatchers.anyBoolean()))
                .thenReturn(com.aioj.next.ai.agent.understanding.TurnUnderstandingService.TurnUnderstanding.empty());
        // Default: L4 lets everything through; interception scenarios stub explicitly.
        when(contestOutputGuard.evaluate(anyString(), anyLong(), anyString(), anyString(),
                any(), any(), org.mockito.ArgumentMatchers.anyBoolean()))
                .thenReturn(com.aioj.next.ai.agent.guard.ContestOutputGuard.Verdict.pass());
        // P3-6: same default for the trigger-aware overload used by race re-checks.
        when(contestOutputGuard.evaluate(anyString(), anyLong(), anyString(), anyString(),
                any(), any(), org.mockito.ArgumentMatchers.anyBoolean(), any()))
                .thenReturn(com.aioj.next.ai.agent.guard.ContestOutputGuard.Verdict.pass());
        when(participationService.evaluate(org.mockito.ArgumentMatchers.anyLong(), anyString(), anyString()))
                .thenReturn(new com.aioj.next.ai.agent.policy.ContestParticipationService.ParticipationView(
                        com.aioj.next.ai.agent.policy.ParticipantStatus.NON_PARTICIPANT, List.of(), null));
        // P3-6 default: the pre-return recheck observes no state change; race tests
        // override this stub explicitly.
        when(policySnapshotService.recheckBeforeReturn(org.mockito.ArgumentMatchers.anyLong(), anyString(),
                anyString(), any()))
                .thenReturn(new PolicySnapshotService.PolicyRecheck(false,
                        new com.aioj.next.ai.agent.policy.ContestParticipationService.ParticipationView(
                                com.aioj.next.ai.agent.policy.ParticipantStatus.NON_PARTICIPANT, List.of(), null),
                        List.of()));
        coordinator = new TurnCoordinator(agentRuntime, bootstrapBuilder, policySnapshotService, participationService,
                fingerprintMatcher, guardDecisionRecorder, contestOutputGuard, quotaService, conversationService, responseNormalizer,
                turnService, turnDigestService, turnUnderstandingService, new AiProperties(), Runnable::run);
    }

    @Test
    void startRunsAgentPipelineAndCompletesTurn() {
        AiConversationEntity conversation = conversation();
        when(conversationService.resolveForWrite(eq(USER_ID), any(AiChatRequest.class))).thenReturn(conversation);
        when(turnService.beginTurn(eq(CONVERSATION_ID), eq("client-1")))
                .thenReturn(new AiTurnService.BeginTurnOutcome(turn("t-1", AiTurnService.STATUS_RECEIVED), true));
        PolicySnapshotService.PolicySnapshot snapshot =
                new PolicySnapshotService.PolicySnapshot("ps-1", null, List.of(), "{}", "", List.of());
        when(policySnapshotService.createForTurn(eq(USER_ID), eq("t-1"), eq(CONVERSATION_ID), any())).thenReturn(snapshot);
        when(bootstrapBuilder.build(eq(USER_ID), eq(CONVERSATION_ID), eq("你好"), eq(snapshot), any(), any(), anyInt(), anyInt()))
                .thenReturn(new BootstrapContextBuilder.BootstrapContext(List.of(
                        ContextSection.text(ContextSectionType.CURRENT_USER_REQUEST, 90, true, TrustLevel.USER_PROVIDED, "你好")),
                        List.of()));
        when(conversationService.appendMessage(eq(CONVERSATION_ID), eq(USER_ID), isNull(), eq("user"), eq("你好"),
                isNull(), eq("client-1"), isNull()))
                .thenReturn(message(100L, "user", "你好", "client-1", "COMPLETED"));
        when(conversationService.appendMessageWithStatus(eq(CONVERSATION_ID), eq(USER_ID), isNull(), eq("assistant"),
                eq(""), isNull(), eq("client-1:assistant"), isNull(), eq("RUNNING"), isNull()))
                .thenReturn(message(200L, "assistant", "", "client-1:assistant", "RUNNING"));
        when(turnService.advanceToGenerating("t-1", "t-1")).thenReturn(true);
        when(agentRuntime.run(any(AgentRuntime.AgentRunRequest.class)))
                .thenReturn(new AgentRuntime.AgentRunResult(42L, "你好，我是助教。", "deepseek", "deepseek-v4-pro",
                        30, 12, 0, 1, 0, false, List.of()));
        when(responseNormalizer.normalize(any(AiCompletion.class)))
                .thenAnswer(invocation -> new AiAssistantResponseNormalizer.NormalizedResponse(
                        invocation.getArgument(0), Map.of(), Map.of(), List.of(), false));
        when(turnService.completeTurn("t-1")).thenReturn(true);
        when(conversationService.completeMessage(eq(USER_ID), eq(200L), eq("你好，我是助教。"), eq("deepseek-v4-pro")))
                .thenReturn(message(200L, "assistant", "你好，我是助教。", "client-1:assistant", "COMPLETED"));
        when(conversationService.updateAutomaticMode(eq(USER_ID), eq(CONVERSATION_ID), any(AiChatRequest.class), any(AiCompletion.class)))
                .thenReturn("qa");

        TurnHandle handle = coordinator.start(USER_ID, request("client-1", "你好"));

        assertThat(handle.turnId()).isEqualTo("t-1");
        TurnResult result = handle.result().join();
        assertThat(result.assistant().content()).isEqualTo("你好，我是助教。");
        assertThat(result.assistant().status()).isEqualTo("COMPLETED");
        verify(turnService).markBuildingContext("t-1");
        verify(turnService).attachPolicySnapshot("t-1", "ps-1");
        verify(turnService).attachMessages("t-1", 100L, 200L);
        verify(quotaService).record(USER_ID, "deepseek", "deepseek-v4-pro", 30L, 12L, true);
        // Digest input must carry the freshly created message ids. The in-memory turn
        // entity predates attachMessages, so reading the ids from it would yield null.
        org.mockito.ArgumentCaptor<com.aioj.next.ai.agent.digest.TurnDigestInput> digestInput =
                org.mockito.ArgumentCaptor.forClass(com.aioj.next.ai.agent.digest.TurnDigestInput.class);
        verify(turnDigestService).recordCompletedTurn(digestInput.capture());
        assertThat(digestInput.getValue().turnId()).isEqualTo("t-1");
        assertThat(digestInput.getValue().userMessageId()).isEqualTo("100");
        assertThat(digestInput.getValue().assistantMessageId()).isEqualTo("200");
        // No RECENT_TURNS section in this bootstrap: understanding sees a first turn.
        verify(turnUnderstandingService).assess("你好", false);
        // P3-6: the pre-return time-race recheck ran and observed no change, so the
        // original flow continued untouched.
        verify(policySnapshotService).recheckBeforeReturn(eq(USER_ID), eq("t-1"), eq(CONVERSATION_ID), eq(snapshot));
        org.mockito.ArgumentCaptor<AgentRuntime.AgentRunRequest> runRequest =
                org.mockito.ArgumentCaptor.forClass(AgentRuntime.AgentRunRequest.class);
        verify(agentRuntime).run(runRequest.capture());
        assertThat(runRequest.getValue().requireToolCall()).isFalse();
    }

    @Test
    void participantSnapshotProjectsContestPolicyIntoAgentRunRequest() {
        AiConversationEntity conversation = conversation();
        when(conversationService.resolveForWrite(eq(USER_ID), any(AiChatRequest.class))).thenReturn(conversation);
        when(turnService.beginTurn(eq(CONVERSATION_ID), eq("client-1")))
                .thenReturn(new AiTurnService.BeginTurnOutcome(turn("t-1", AiTurnService.STATUS_RECEIVED), true));
        PolicySnapshotService.PolicySnapshot snapshot = new PolicySnapshotService.PolicySnapshot(
                "ps-1", com.aioj.next.ai.agent.policy.ParticipantStatus.PARTICIPANT_ACTIVE, List.of(7701L), "{}",
                "prompt",
                List.of(new com.aioj.next.contract.contest.RunningContestProblemStatement(
                        1001L, "比赛题面", 5501L, 7701L, 99001L,
                        com.aioj.next.contract.problem.ProblemVisibility.PUBLIC,
                        com.aioj.next.contract.contest.ContestAiPolicyMode.DEFAULT, null,
                        List.of(new com.aioj.next.contract.contest.RunningContestProblemOccurrence(5501L, 7701L, 99001L)))));
        when(policySnapshotService.createForTurn(eq(USER_ID), eq("t-1"), eq(CONVERSATION_ID), any())).thenReturn(snapshot);
        when(fingerprintMatcher.match(eq("讲一下第1题"), anyList()))
                .thenReturn(com.aioj.next.ai.agent.guard.GuardVerdict.pass());
        when(bootstrapBuilder.build(eq(USER_ID), eq(CONVERSATION_ID), eq("讲一下第1题"), eq(snapshot), any(), any(), anyInt(), anyInt()))
                .thenReturn(new BootstrapContextBuilder.BootstrapContext(List.of(
                        ContextSection.text(ContextSectionType.CURRENT_USER_REQUEST, 90, true, TrustLevel.USER_PROVIDED, "讲一下第1题")),
                        List.of()));
        when(conversationService.appendMessage(eq(CONVERSATION_ID), eq(USER_ID), isNull(), eq("user"), eq("讲一下第1题"),
                isNull(), eq("client-1"), isNull()))
                .thenReturn(message(100L, "user", "讲一下第1题", "client-1", "COMPLETED"));
        when(conversationService.appendMessageWithStatus(eq(CONVERSATION_ID), eq(USER_ID), isNull(), eq("assistant"),
                eq(""), isNull(), eq("client-1:assistant"), isNull(), eq("RUNNING"), isNull()))
                .thenReturn(message(200L, "assistant", "", "client-1:assistant", "RUNNING"));
        when(turnService.advanceToGenerating("t-1", "t-1")).thenReturn(true);
        when(agentRuntime.run(any(AgentRuntime.AgentRunRequest.class)))
                .thenReturn(new AgentRuntime.AgentRunResult(42L, "提示：想想贪心。", "deepseek", "deepseek-v4-pro",
                        30, 12, 0, 1, 0, false, List.of()));
        when(responseNormalizer.normalize(any(AiCompletion.class)))
                .thenAnswer(invocation -> new AiAssistantResponseNormalizer.NormalizedResponse(
                        invocation.getArgument(0), Map.of(), Map.of(), List.of(), false));
        when(turnService.completeTurn("t-1")).thenReturn(true);
        when(conversationService.completeMessage(eq(USER_ID), eq(200L), eq("提示：想想贪心。"), eq("deepseek-v4-pro")))
                .thenReturn(message(200L, "assistant", "提示：想想贪心。", "client-1:assistant", "COMPLETED"));
        when(conversationService.updateAutomaticMode(eq(USER_ID), eq(CONVERSATION_ID), any(AiChatRequest.class), any(AiCompletion.class)))
                .thenReturn("qa");

        TurnHandle handle = coordinator.start(USER_ID, request("client-1", "讲一下第1题"));

        assertThat(handle.result().join().assistant().content()).isEqualTo("提示：想想贪心。");
        org.mockito.ArgumentCaptor<AgentRuntime.AgentRunRequest> runRequest =
                org.mockito.ArgumentCaptor.forClass(AgentRuntime.AgentRunRequest.class);
        verify(agentRuntime).run(runRequest.capture());
        assertThat(runRequest.getValue().contestPolicy()).isNotNull();
        assertThat(runRequest.getValue().contestPolicy().isParticipant()).isTrue();
        assertThat(runRequest.getValue().contestPolicy().contestProblems()).containsOnlyKeys(1001L);
        // P3-4: the L3 message-layer verdict (PASS here) travels with the run request.
        assertThat(runRequest.getValue().messageLayerVerdict()).isNotNull();
        assertThat(runRequest.getValue().messageLayerVerdict().decision())
                .isEqualTo(com.aioj.next.ai.agent.policy.GuardDecision.PASS);
    }

    @Test
    void messageLayerMatchVerdictIsThreadedIntoAgentRunRequest() {
        AiConversationEntity conversation = conversation();
        when(conversationService.resolveForWrite(eq(USER_ID), any(AiChatRequest.class))).thenReturn(conversation);
        when(turnService.beginTurn(eq(CONVERSATION_ID), eq("client-1")))
                .thenReturn(new AiTurnService.BeginTurnOutcome(turn("t-1", AiTurnService.STATUS_RECEIVED), true));
        PolicySnapshotService.PolicySnapshot snapshot = new PolicySnapshotService.PolicySnapshot(
                "ps-1", com.aioj.next.ai.agent.policy.ParticipantStatus.PARTICIPANT_ACTIVE, List.of(7701L), "{}",
                "prompt",
                List.of(new com.aioj.next.contract.contest.RunningContestProblemStatement(
                        1002L, "私有题面", 5501L, 7701L, 99001L,
                        com.aioj.next.contract.problem.ProblemVisibility.PRIVATE,
                        com.aioj.next.contract.contest.ContestAiPolicyMode.DEFAULT, null,
                        List.of(new com.aioj.next.contract.contest.RunningContestProblemOccurrence(5501L, 7701L, 99001L)))));
        when(policySnapshotService.createForTurn(eq(USER_ID), eq("t-1"), eq(CONVERSATION_ID), any())).thenReturn(snapshot);
        com.aioj.next.ai.agent.guard.GuardVerdict constrain = com.aioj.next.ai.agent.guard.GuardVerdict.constrain(
                List.of(new com.aioj.next.ai.agent.policy.GuardDecisionRecorder.MatchedProblemRef(
                        1002L, 5501L, 7701L, 99001L, "PRIVATE", "DEFAULT")), 1.0);
        when(fingerprintMatcher.match(eq("题面粘贴"), anyList())).thenReturn(constrain);
        when(bootstrapBuilder.build(eq(USER_ID), eq(CONVERSATION_ID), eq("题面粘贴"), eq(snapshot), any(), any(), anyInt(), anyInt()))
                .thenReturn(new BootstrapContextBuilder.BootstrapContext(List.of(
                        ContextSection.text(ContextSectionType.CURRENT_USER_REQUEST, 90, true, TrustLevel.USER_PROVIDED, "题面粘贴")),
                        List.of()));
        when(conversationService.appendMessage(eq(CONVERSATION_ID), eq(USER_ID), isNull(), eq("user"), eq("题面粘贴"),
                isNull(), eq("client-1"), isNull()))
                .thenReturn(message(100L, "user", "题面粘贴", "client-1", "COMPLETED"));
        when(conversationService.appendMessageWithStatus(eq(CONVERSATION_ID), eq(USER_ID), isNull(), eq("assistant"),
                eq(""), isNull(), eq("client-1:assistant"), isNull(), eq("RUNNING"), isNull()))
                .thenReturn(message(200L, "assistant", "", "client-1:assistant", "RUNNING"));
        when(turnService.advanceToGenerating("t-1", "t-1")).thenReturn(true);
        when(agentRuntime.run(any(AgentRuntime.AgentRunRequest.class)))
                .thenReturn(new AgentRuntime.AgentRunResult(42L, "无可奉告。", "deepseek", "deepseek-v4-pro",
                        30, 12, 0, 1, 0, false, List.of()));
        when(responseNormalizer.normalize(any(AiCompletion.class)))
                .thenAnswer(invocation -> new AiAssistantResponseNormalizer.NormalizedResponse(
                        invocation.getArgument(0), Map.of(), Map.of(), List.of(), false));
        when(turnService.completeTurn("t-1")).thenReturn(true);
        when(conversationService.completeMessage(eq(USER_ID), eq(200L), eq("无可奉告。"), eq("deepseek-v4-pro")))
                .thenReturn(message(200L, "assistant", "无可奉告。", "client-1:assistant", "COMPLETED"));
        when(conversationService.updateAutomaticMode(eq(USER_ID), eq(CONVERSATION_ID), any(AiChatRequest.class), any(AiCompletion.class)))
                .thenReturn("qa");

        TurnHandle handle = coordinator.start(USER_ID, request("client-1", "题面粘贴"));

        assertThat(handle.result().join().assistant().content()).isEqualTo("无可奉告。");
        org.mockito.ArgumentCaptor<AgentRuntime.AgentRunRequest> runRequest =
                org.mockito.ArgumentCaptor.forClass(AgentRuntime.AgentRunRequest.class);
        verify(agentRuntime).run(runRequest.capture());
        // The CONSTRAIN verdict from the message layer seeds the context layer's
        // dedup set inside AgentRuntime.
        assertThat(runRequest.getValue().messageLayerVerdict()).isSameAs(constrain);
    }

    @Test
    void understandingToolFloorForcesRequiredToolCall() {
        AiConversationEntity conversation = conversation();
        when(conversationService.resolveForWrite(eq(USER_ID), any(AiChatRequest.class))).thenReturn(conversation);
        when(turnService.beginTurn(eq(CONVERSATION_ID), eq("client-1")))
                .thenReturn(new AiTurnService.BeginTurnOutcome(turn("t-1", AiTurnService.STATUS_RECEIVED), true));
        PolicySnapshotService.PolicySnapshot snapshot =
                new PolicySnapshotService.PolicySnapshot("ps-1", null, List.of(), "{}", "", List.of());
        when(policySnapshotService.createForTurn(eq(USER_ID), eq("t-1"), eq(CONVERSATION_ID), any())).thenReturn(snapshot);
        when(bootstrapBuilder.build(eq(USER_ID), eq(CONVERSATION_ID), eq("讲一下第2题"), eq(snapshot), any(), any(), anyInt(), anyInt()))
                .thenReturn(new BootstrapContextBuilder.BootstrapContext(List.of(
                        ContextSection.text(ContextSectionType.RECENT_TURNS, 40, false, TrustLevel.USER_PROVIDED, "earlier"),
                        ContextSection.text(ContextSectionType.CURRENT_USER_REQUEST, 90, true, TrustLevel.USER_PROVIDED, "讲一下第2题")),
                        List.of()));
        when(conversationService.appendMessage(eq(CONVERSATION_ID), eq(USER_ID), isNull(), eq("user"), eq("讲一下第2题"),
                isNull(), eq("client-1"), isNull()))
                .thenReturn(message(100L, "user", "讲一下第2题", "client-1", "COMPLETED"));
        when(conversationService.appendMessageWithStatus(eq(CONVERSATION_ID), eq(USER_ID), isNull(), eq("assistant"),
                eq(""), isNull(), eq("client-1:assistant"), isNull(), eq("RUNNING"), isNull()))
                .thenReturn(message(200L, "assistant", "", "client-1:assistant", "RUNNING"));
        when(turnService.advanceToGenerating("t-1", "t-1")).thenReturn(true);
        when(turnUnderstandingService.assess("讲一下第2题", true))
                .thenReturn(new com.aioj.next.ai.agent.understanding.TurnUnderstandingService.TurnUnderstanding(
                        "FOLLOW_UP", List.of("ORDINAL"), false, java.util.Set.of("CONTEXT_SEARCH")));
        when(agentRuntime.run(any(AgentRuntime.AgentRunRequest.class)))
                .thenReturn(new AgentRuntime.AgentRunResult(42L, "第二题是……", "deepseek", "deepseek-v4-pro",
                        30, 12, 0, 2, 1, false, List.of()));
        when(responseNormalizer.normalize(any(AiCompletion.class)))
                .thenAnswer(invocation -> new AiAssistantResponseNormalizer.NormalizedResponse(
                        invocation.getArgument(0), Map.of(), Map.of(), List.of(), false));
        when(turnService.completeTurn("t-1")).thenReturn(true);
        when(conversationService.completeMessage(eq(USER_ID), eq(200L), eq("第二题是……"), eq("deepseek-v4-pro")))
                .thenReturn(message(200L, "assistant", "第二题是……", "client-1:assistant", "COMPLETED"));
        when(conversationService.updateAutomaticMode(eq(USER_ID), eq(CONVERSATION_ID), any(AiChatRequest.class), any(AiCompletion.class)))
                .thenReturn("qa");

        TurnHandle handle = coordinator.start(USER_ID, request("client-1", "讲一下第2题"));

        assertThat(handle.result().join().assistant().content()).isEqualTo("第二题是……");
        org.mockito.ArgumentCaptor<AgentRuntime.AgentRunRequest> runRequest =
                org.mockito.ArgumentCaptor.forClass(AgentRuntime.AgentRunRequest.class);
        verify(agentRuntime).run(runRequest.capture());
        assertThat(runRequest.getValue().requireToolCall()).isTrue();
    }

    @Test
    void duplicateClientMessageIdResumesExistingTurnWithoutRegenerating() {
        AiConversationEntity conversation = conversation();
        when(conversationService.resolveForWrite(eq(USER_ID), any(AiChatRequest.class))).thenReturn(conversation);
        AiTurnEntity existing = turn("t-1", AiTurnService.STATUS_COMPLETED);
        existing.setUserMessageId("100");
        existing.setAssistantMessageId("200");
        when(turnService.beginTurn(eq(CONVERSATION_ID), eq("client-1")))
                .thenReturn(new AiTurnService.BeginTurnOutcome(existing, false));
        when(conversationService.getMessage(USER_ID, 100L))
                .thenReturn(message(100L, "user", "你好", "client-1", "COMPLETED"));
        when(conversationService.getMessage(USER_ID, 200L))
                .thenReturn(message(200L, "assistant", "你好，我是助教。", "client-1:assistant", "COMPLETED"));
        when(responseNormalizer.normalize(any(AiCompletion.class)))
                .thenAnswer(invocation -> new AiAssistantResponseNormalizer.NormalizedResponse(
                        invocation.getArgument(0), Map.of(), Map.of(), List.of(), false));

        TurnHandle handle = coordinator.start(USER_ID, request("client-1", "你好"));

        assertThat(handle.result().join().assistant().content()).isEqualTo("你好，我是助教。");
        verify(agentRuntime, never()).run(any());
        verify(conversationService, never()).appendMessage(anyString(), anyLong(), any(), anyString(), anyString(),
                any(), any(), any());
        verify(quotaService, never()).record(anyLong(), any(), any(), anyLong(), anyLong(), eq(true));
    }

    @Test
    void providerFailureFailsTurnAndRecordsFailedUsage() {
        AiConversationEntity conversation = conversation();
        when(conversationService.resolveForWrite(eq(USER_ID), any(AiChatRequest.class))).thenReturn(conversation);
        when(turnService.beginTurn(eq(CONVERSATION_ID), eq("client-1")))
                .thenReturn(new AiTurnService.BeginTurnOutcome(turn("t-1", AiTurnService.STATUS_RECEIVED), true));
        when(policySnapshotService.createForTurn(eq(USER_ID), eq("t-1"), eq(CONVERSATION_ID), any()))
                .thenReturn(new PolicySnapshotService.PolicySnapshot("ps-1", null, List.of(), "{}", "", List.of()));
        when(bootstrapBuilder.build(anyLong(), anyString(), anyString(), any(), any(), any(), anyInt(), anyInt()))
                .thenReturn(new BootstrapContextBuilder.BootstrapContext(List.of(), List.of()));
        when(conversationService.appendMessage(anyString(), anyLong(), any(), anyString(), anyString(),
                any(), any(), any()))
                .thenReturn(message(100L, "user", "你好", "client-1", "COMPLETED"));
        when(conversationService.appendMessageWithStatus(anyString(), anyLong(), any(), anyString(), anyString(),
                any(), any(), any(), anyString(), any()))
                .thenReturn(message(200L, "assistant", "", "client-1:assistant", "RUNNING"));
        when(turnService.advanceToGenerating("t-1", "t-1")).thenReturn(true);
        when(agentRuntime.run(any(AgentRuntime.AgentRunRequest.class)))
                .thenThrow(new RuntimeException("provider down"));
        when(turnService.failTurn(eq("t-1"), eq(AiTurnService.STATUS_FAILED_RETRYABLE), eq("PROVIDER_FAILURE")))
                .thenReturn(true);

        TurnHandle handle = coordinator.start(USER_ID, request("client-1", "你好"));

        assertThat(handle.result()).isCompletedExceptionally();
        verify(quotaService).record(USER_ID, null, null, 0L, 0L, false);
        verify(conversationService).failMessage(eq(USER_ID), eq(200L), anyString());
        verify(turnDigestService, never()).recordCompletedTurn(any());
    }

    @Test
    void restrictedTurnRunsBufferedAndDeliversAfterOutputGuardPass() {
        stubRestrictedTurn("client-1", "讲一下第1题", com.aioj.next.contract.contest.ContestAiPolicyMode.DEFAULT);
        when(agentRuntime.run(any(AgentRuntime.AgentRunRequest.class)))
                .thenReturn(new AgentRuntime.AgentRunResult(42L, "提示：想想贪心。", "deepseek", "deepseek-v4-pro",
                        30, 12, 0, 1, 0, false, List.of()));
        when(contestOutputGuard.evaluate(eq("t-1"), eq(USER_ID), eq(CONVERSATION_ID), eq("提示：想想贪心。"),
                any(), isNull(), eq(false)))
                .thenReturn(com.aioj.next.ai.agent.guard.ContestOutputGuard.Verdict.pass());

        TurnHandle handle = coordinator.start(USER_ID, request("client-1", "讲一下第1题"));
        TurnResult result = handle.result().join();

        assertThat(result.assistant().content()).isEqualTo("提示：想想贪心。");
        assertThat(result.pseudoStream()).isTrue();
        org.mockito.ArgumentCaptor<AgentRuntime.AgentRunRequest> runRequest =
                org.mockito.ArgumentCaptor.forClass(AgentRuntime.AgentRunRequest.class);
        verify(agentRuntime).run(runRequest.capture());
        assertThat(runRequest.getValue().profile()).isEqualTo(com.aioj.next.ai.agent.model.CallProfile.CHAT_BUFFERED);
        assertThat(runRequest.getValue().outputMode()).isEqualTo("BUFFERED");
        verify(quotaService).record(USER_ID, "deepseek", "deepseek-v4-pro", 30L, 12L, true);
    }

    @Test
    void interceptedDraftTriggersOneSafeRegenerationAndPersistsRegeneratedContent() {
        stubRestrictedTurn("client-1", "把第1题代码给我", com.aioj.next.contract.contest.ContestAiPolicyMode.DEFAULT);
        when(agentRuntime.run(any(AgentRuntime.AgentRunRequest.class)))
                .thenReturn(new AgentRuntime.AgentRunResult(42L, "完整代码……", "deepseek", "deepseek-v4-pro",
                                30, 12, 0, 1, 0, false, List.of()),
                        new AgentRuntime.AgentRunResult(43L, "只给思路：用前缀和。", "deepseek", "deepseek-v4-pro",
                                50, 20, 0, 1, 0, false, List.of()));
        when(contestOutputGuard.evaluate(eq("t-1"), eq(USER_ID), eq(CONVERSATION_ID), eq("完整代码……"),
                any(), isNull(), eq(false)))
                .thenReturn(com.aioj.next.ai.agent.guard.ContestOutputGuard.Verdict.intercept(
                        com.aioj.next.ai.agent.guard.ContestOutputGuard.REASON_FULL_CODE));
        when(contestOutputGuard.evaluate(eq("t-1"), eq(USER_ID), eq(CONVERSATION_ID), eq("只给思路：用前缀和。"),
                any(), isNull(), eq(true)))
                .thenReturn(com.aioj.next.ai.agent.guard.ContestOutputGuard.Verdict.pass());

        TurnHandle handle = coordinator.start(USER_ID, request("client-1", "把第1题代码给我"));
        TurnResult result = handle.result().join();

        assertThat(result.assistant().content()).isEqualTo("只给思路：用前缀和。");
        assertThat(result.pseudoStream()).isTrue();
        org.mockito.ArgumentCaptor<AgentRuntime.AgentRunRequest> runRequests =
                org.mockito.ArgumentCaptor.forClass(AgentRuntime.AgentRunRequest.class);
        verify(agentRuntime, org.mockito.Mockito.times(2)).run(runRequests.capture());
        // The regeneration run reuses the bootstrap plus one server retry section
        // naming only the reason category.
        AgentRuntime.AgentRunRequest regenRequest = runRequests.getAllValues().get(1);
        assertThat(regenRequest.profile()).isEqualTo(com.aioj.next.ai.agent.model.CallProfile.CHAT_BUFFERED);
        assertThat(regenRequest.sections())
                .anySatisfy(section -> {
                    assertThat(section.type()).isEqualTo(ContextSectionType.CONTEST_OUTPUT_GUARD_RETRY);
                    assertThat(section.content()).contains("complete submittable code disclosure");
                    assertThat(section.content()).doesNotContain("完整代码……");
                });
        // The violating first draft is never persisted; the regenerated content is.
        verify(conversationService).completeMessage(USER_ID, 200L, "只给思路：用前缀和。", "deepseek-v4-pro");
        // Exactly one usage record per turn, attributed to the deciding (regeneration) run.
        verify(quotaService, org.mockito.Mockito.times(1))
                .record(eq(USER_ID), any(), any(), anyLong(), anyLong(), eq(true));
        verify(quotaService).record(USER_ID, "deepseek", "deepseek-v4-pro", 50L, 20L, true);
    }

    @Test
    void telemetryRetainsInitialAndSafeRegenerationUsageWithoutChangingQuotaSemantics() {
        com.aioj.next.ai.agent.telemetry.ContestAiAssistanceTelemetryService telemetry =
                mock(com.aioj.next.ai.agent.telemetry.ContestAiAssistanceTelemetryService.class);
        coordinator.setContestAssistanceTelemetryService(telemetry);
        com.aioj.next.contract.contest.RunningContestParticipation attributed =
                new com.aioj.next.contract.contest.RunningContestParticipation(
                        5501L, 7701L, Instant.now().minusSeconds(60), Instant.now().plusSeconds(60));
        when(participationService.evaluate(anyLong(), anyString(), anyString()))
                .thenReturn(new com.aioj.next.ai.agent.policy.ContestParticipationService.ParticipationView(
                        com.aioj.next.ai.agent.policy.ParticipantStatus.PARTICIPANT_ACTIVE, List.of(attributed), attributed));
        com.aioj.next.ai.agent.telemetry.ContestAiAssistanceTelemetryService.TrackingContext telemetryContext =
                new com.aioj.next.ai.agent.telemetry.ContestAiAssistanceTelemetryService.TrackingContext(77L, "t-1");
        when(telemetry.begin(any(), any(), eq(attributed))).thenReturn(telemetryContext);
        stubRestrictedTurn("client-telemetry", "把第1题代码给我", com.aioj.next.contract.contest.ContestAiPolicyMode.DEFAULT);
        java.util.concurrent.atomic.AtomicInteger agentRuns = new java.util.concurrent.atomic.AtomicInteger();
        when(agentRuntime.run(any(AgentRuntime.AgentRunRequest.class))).thenAnswer(invocation -> {
            AgentRuntime.AgentRunRequest agentRequest = invocation.getArgument(0);
            int run = agentRuns.getAndIncrement();
            if (run == 0) {
                agentRequest.usageObserver().accept(new com.aioj.next.ai.agent.model.ModelUsage(
                        "deepseek", "deepseek-v4-pro", 11, 4));
                agentRequest.usageObserver().accept(new com.aioj.next.ai.agent.model.ModelUsage(
                        "deepseek", "deepseek-v4-pro", 19, 8));
                return new AgentRuntime.AgentRunResult(42L, "完整代码……", "deepseek", "deepseek-v4-pro",
                        30, 12, 0, 1, 0, false, List.of());
            }
            agentRequest.usageObserver().accept(new com.aioj.next.ai.agent.model.ModelUsage(
                    "deepseek", "deepseek-v4-pro", 50, 20));
            return new AgentRuntime.AgentRunResult(43L, "只给思路", "deepseek", "deepseek-v4-pro",
                    50, 20, 0, 1, 0, false, List.of());
        });
        when(contestOutputGuard.evaluate(eq("t-1"), eq(USER_ID), eq(CONVERSATION_ID), eq("完整代码……"),
                any(), isNull(), eq(false)))
                .thenReturn(com.aioj.next.ai.agent.guard.ContestOutputGuard.Verdict.intercept(
                        com.aioj.next.ai.agent.guard.ContestOutputGuard.REASON_FULL_CODE));
        when(contestOutputGuard.evaluate(eq("t-1"), eq(USER_ID), eq(CONVERSATION_ID), eq("只给思路"),
                any(), isNull(), eq(true)))
                .thenReturn(com.aioj.next.ai.agent.guard.ContestOutputGuard.Verdict.pass());

        coordinator.start(USER_ID, request("client-telemetry", "把第1题代码给我")).result().join();

        org.mockito.ArgumentCaptor<com.aioj.next.ai.agent.model.ModelUsage> usages =
                org.mockito.ArgumentCaptor.forClass(com.aioj.next.ai.agent.model.ModelUsage.class);
        verify(telemetry, org.mockito.Mockito.times(3)).recordUsage(eq(telemetryContext), anyString(), anyString(), usages.capture());
        assertThat(usages.getAllValues())
                .extracting(com.aioj.next.ai.agent.model.ModelUsage::promptTokens)
                .containsExactly(11L, 19L, 50L);
        assertThat(usages.getAllValues())
                .extracting(com.aioj.next.ai.agent.model.ModelUsage::completionTokens)
                .containsExactly(4L, 8L, 20L);
        // Quota stays on its pre-existing "one final completion" rule.
        verify(quotaService).record(USER_ID, "deepseek", "deepseek-v4-pro", 50L, 20L, true);
    }

    @Test
    void regenerationStillInterceptedPersistsSafeRefusal() {
        stubRestrictedTurn("client-1", "把第1题代码给我", com.aioj.next.contract.contest.ContestAiPolicyMode.DEFAULT);
        when(agentRuntime.run(any(AgentRuntime.AgentRunRequest.class)))
                .thenReturn(new AgentRuntime.AgentRunResult(42L, "完整代码……", "deepseek", "deepseek-v4-pro",
                                30, 12, 0, 1, 0, false, List.of()),
                        new AgentRuntime.AgentRunResult(43L, "还是完整代码……", "deepseek", "deepseek-v4-pro",
                                50, 20, 0, 1, 0, false, List.of()));
        when(contestOutputGuard.evaluate(eq("t-1"), eq(USER_ID), eq(CONVERSATION_ID), anyString(),
                any(), isNull(), org.mockito.ArgumentMatchers.anyBoolean()))
                .thenReturn(com.aioj.next.ai.agent.guard.ContestOutputGuard.Verdict.intercept(
                        com.aioj.next.ai.agent.guard.ContestOutputGuard.REASON_FULL_CODE));

        TurnHandle handle = coordinator.start(USER_ID, request("client-1", "把第1题代码给我"));
        TurnResult result = handle.result().join();

        // Final refusal: server-authored text is persisted and delivered; neither
        // violating draft reaches ai_messages. The turn still completes normally.
        assertThat(result.assistant().content()).isEqualTo(TurnCoordinator.CONTEST_SAFE_REFUSAL_TEXT);
        assertThat(result.pseudoStream()).isTrue();
        verify(turnService).completeTurn("t-1");
        verify(conversationService).completeMessage(USER_ID, 200L,
                TurnCoordinator.CONTEST_SAFE_REFUSAL_TEXT, "deepseek-v4-pro");
        verify(conversationService, never()).failMessage(eq(USER_ID), eq(200L), anyString());
        verify(quotaService, org.mockito.Mockito.times(1))
                .record(eq(USER_ID), any(), any(), anyLong(), anyLong(), eq(true));
        verify(agentRuntime, org.mockito.Mockito.times(2)).run(any(AgentRuntime.AgentRunRequest.class));
    }

    @Test
    void participantWithOnlyDisabledProblemsSkipsL4AndStreamsNormally() {
        stubRestrictedTurn("client-1", "随便聊聊", com.aioj.next.contract.contest.ContestAiPolicyMode.DISABLED);
        when(agentRuntime.run(any(AgentRuntime.AgentRunRequest.class)))
                .thenReturn(new AgentRuntime.AgentRunResult(42L, "好的。", "deepseek", "deepseek-v4-pro",
                        30, 12, 0, 1, 0, false, List.of()));

        TurnHandle handle = coordinator.start(USER_ID, request("client-1", "随便聊聊"));
        TurnResult result = handle.result().join();

        assertThat(result.assistant().content()).isEqualTo("好的。");
        assertThat(result.pseudoStream()).isFalse();
        org.mockito.ArgumentCaptor<AgentRuntime.AgentRunRequest> runRequest =
                org.mockito.ArgumentCaptor.forClass(AgentRuntime.AgentRunRequest.class);
        verify(agentRuntime).run(runRequest.capture());
        assertThat(runRequest.getValue().profile()).isEqualTo(com.aioj.next.ai.agent.model.CallProfile.CHAT_STREAM);
        assertThat(runRequest.getValue().outputMode()).isEqualTo("STREAM");
        // Empty constrained set: L4 skipped entirely, zero guard evaluations.
        verify(contestOutputGuard, never()).evaluate(anyString(), anyLong(), anyString(), anyString(),
                any(), any(), org.mockito.ArgumentMatchers.anyBoolean());
    }

    @Test
    void contestStartMidGenerationTriggersRecheckGuardAndOneRegeneration() {
        // Turn starts NON-participant: unrestricted, STREAM, L4 never runs initially.
        stubUnrestrictedTurn("client-race-1", "把第1题代码给我");
        when(agentRuntime.run(any(AgentRuntime.AgentRunRequest.class)))
                .thenReturn(new AgentRuntime.AgentRunResult(42L, "完整代码……", "deepseek", "deepseek-v4-pro",
                                30, 12, 0, 1, 0, false, List.of()),
                        new AgentRuntime.AgentRunResult(43L, "只给思路：用前缀和。", "deepseek", "deepseek-v4-pro",
                                50, 20, 0, 1, 0, false, List.of()));
        // The contest starts while the model is generating.
        when(policySnapshotService.recheckBeforeReturn(eq(USER_ID), eq("t-1"), eq(CONVERSATION_ID), any()))
                .thenReturn(recheckResult(true,
                        com.aioj.next.ai.agent.policy.ParticipantStatus.PARTICIPANT_ACTIVE, List.of(7701L),
                        List.of(contestProblem(1001L, com.aioj.next.contract.problem.ProblemVisibility.PUBLIC,
                                com.aioj.next.contract.contest.ContestAiPolicyMode.DEFAULT))));
        when(contestOutputGuard.evaluate(eq("t-1"), eq(USER_ID), eq(CONVERSATION_ID), eq("完整代码……"),
                any(), isNull(), eq(false), eq(com.aioj.next.ai.agent.guard.ContestOutputGuard.TRIGGER_RECHECK)))
                .thenReturn(com.aioj.next.ai.agent.guard.ContestOutputGuard.Verdict.intercept(
                        com.aioj.next.ai.agent.guard.ContestOutputGuard.REASON_FULL_CODE));
        when(contestOutputGuard.evaluate(eq("t-1"), eq(USER_ID), eq(CONVERSATION_ID), eq("只给思路：用前缀和。"),
                any(), isNull(), eq(true), eq(com.aioj.next.ai.agent.guard.ContestOutputGuard.TRIGGER_RECHECK)))
                .thenReturn(com.aioj.next.ai.agent.guard.ContestOutputGuard.Verdict.pass());

        TurnHandle handle = coordinator.start(USER_ID, request("client-race-1", "把第1题代码给我"));
        TurnResult result = handle.result().join();

        assertThat(result.assistant().content()).isEqualTo("只给思路：用前缀和。");
        assertThat(result.pseudoStream()).isTrue();
        org.mockito.ArgumentCaptor<AgentRuntime.AgentRunRequest> runRequests =
                org.mockito.ArgumentCaptor.forClass(AgentRuntime.AgentRunRequest.class);
        verify(agentRuntime, org.mockito.Mockito.times(2)).run(runRequests.capture());
        // The first run streamed (turn started unrestricted); the race-triggered
        // regeneration runs BUFFERED under the NEW contest policy with a null L3 verdict.
        AgentRuntime.AgentRunRequest first = runRequests.getAllValues().get(0);
        assertThat(first.profile()).isEqualTo(com.aioj.next.ai.agent.model.CallProfile.CHAT_STREAM);
        AgentRuntime.AgentRunRequest regen = runRequests.getAllValues().get(1);
        assertThat(regen.profile()).isEqualTo(com.aioj.next.ai.agent.model.CallProfile.CHAT_BUFFERED);
        assertThat(regen.contestPolicy().isParticipant()).isTrue();
        assertThat(regen.contestPolicy().contestProblems()).containsOnlyKeys(1001L);
        assertThat(regen.messageLayerVerdict()).isNull();
        assertThat(regen.sections()).anySatisfy(section -> {
            assertThat(section.type()).isEqualTo(ContextSectionType.CONTEST_OUTPUT_GUARD_RETRY);
            assertThat(section.content()).contains("complete submittable code disclosure");
            assertThat(section.content()).doesNotContain("完整代码……");
        });
        verify(conversationService).completeMessage(USER_ID, 200L, "只给思路：用前缀和。", "deepseek-v4-pro");
        verify(quotaService, org.mockito.Mockito.times(1))
                .record(eq(USER_ID), any(), any(), anyLong(), anyLong(), eq(true));
        verify(quotaService).record(USER_ID, "deepseek", "deepseek-v4-pro", 50L, 20L, true);
    }

    @Test
    void contestStartMidGenerationRegenerationStillInterceptedPersistsSafeRefusal() {
        stubUnrestrictedTurn("client-race-2", "把第1题代码给我");
        when(agentRuntime.run(any(AgentRuntime.AgentRunRequest.class)))
                .thenReturn(new AgentRuntime.AgentRunResult(42L, "完整代码……", "deepseek", "deepseek-v4-pro",
                                30, 12, 0, 1, 0, false, List.of()),
                        new AgentRuntime.AgentRunResult(43L, "还是完整代码……", "deepseek", "deepseek-v4-pro",
                                50, 20, 0, 1, 0, false, List.of()));
        when(policySnapshotService.recheckBeforeReturn(eq(USER_ID), eq("t-1"), eq(CONVERSATION_ID), any()))
                .thenReturn(recheckResult(true,
                        com.aioj.next.ai.agent.policy.ParticipantStatus.PARTICIPANT_ACTIVE, List.of(7701L),
                        List.of(contestProblem(1001L, com.aioj.next.contract.problem.ProblemVisibility.PUBLIC,
                                com.aioj.next.contract.contest.ContestAiPolicyMode.DEFAULT))));
        when(contestOutputGuard.evaluate(eq("t-1"), eq(USER_ID), eq(CONVERSATION_ID), anyString(),
                any(), isNull(), org.mockito.ArgumentMatchers.anyBoolean(),
                eq(com.aioj.next.ai.agent.guard.ContestOutputGuard.TRIGGER_RECHECK)))
                .thenReturn(com.aioj.next.ai.agent.guard.ContestOutputGuard.Verdict.intercept(
                        com.aioj.next.ai.agent.guard.ContestOutputGuard.REASON_FULL_CODE));

        TurnHandle handle = coordinator.start(USER_ID, request("client-race-2", "把第1题代码给我"));
        TurnResult result = handle.result().join();

        assertThat(result.assistant().content()).isEqualTo(TurnCoordinator.CONTEST_SAFE_REFUSAL_TEXT);
        assertThat(result.pseudoStream()).isTrue();
        verify(agentRuntime, org.mockito.Mockito.times(2)).run(any(AgentRuntime.AgentRunRequest.class));
        verify(conversationService).completeMessage(USER_ID, 200L,
                TurnCoordinator.CONTEST_SAFE_REFUSAL_TEXT, "deepseek-v4-pro");
        verify(quotaService, org.mockito.Mockito.times(1))
                .record(eq(USER_ID), any(), any(), anyLong(), anyLong(), eq(true));
    }

    @Test
    void contestEndMidGenerationReleasesGeneratedContentEvenAfterRefusal() {
        stubRestrictedTurn("client-race-3", "把第1题代码给我", com.aioj.next.contract.contest.ContestAiPolicyMode.DEFAULT);
        when(agentRuntime.run(any(AgentRuntime.AgentRunRequest.class)))
                .thenReturn(new AgentRuntime.AgentRunResult(42L, "完整代码……", "deepseek", "deepseek-v4-pro",
                                30, 12, 0, 1, 0, false, List.of()),
                        new AgentRuntime.AgentRunResult(43L, "还是完整代码……", "deepseek", "deepseek-v4-pro",
                                50, 20, 0, 1, 0, false, List.of()));
        when(contestOutputGuard.evaluate(eq("t-1"), eq(USER_ID), eq(CONVERSATION_ID), anyString(),
                any(), isNull(), org.mockito.ArgumentMatchers.anyBoolean()))
                .thenReturn(com.aioj.next.ai.agent.guard.ContestOutputGuard.Verdict.intercept(
                        com.aioj.next.ai.agent.guard.ContestOutputGuard.REASON_FULL_CODE));
        // The run leaves the guard window while the model is generating.
        when(policySnapshotService.recheckBeforeReturn(eq(USER_ID), eq("t-1"), eq(CONVERSATION_ID), any()))
                .thenReturn(recheckResult(true,
                        com.aioj.next.ai.agent.policy.ParticipantStatus.NON_PARTICIPANT, List.of(), List.of()));

        TurnHandle handle = coordinator.start(USER_ID, request("client-race-3", "把第1题代码给我"));
        TurnResult result = handle.result().join();

        // Both drafts were intercepted and the refusal was already decided, but with the
        // contest over there is no violation basis left: the latest generated draft is
        // delivered instead of the refusal text.
        assertThat(result.assistant().content()).isEqualTo("还是完整代码……");
        assertThat(result.pseudoStream()).isTrue();
        verify(conversationService).completeMessage(USER_ID, 200L, "还是完整代码……", "deepseek-v4-pro");
        verify(quotaService, org.mockito.Mockito.times(1))
                .record(eq(USER_ID), any(), any(), anyLong(), anyLong(), eq(true));
        verify(agentRuntime, org.mockito.Mockito.times(2)).run(any(AgentRuntime.AgentRunRequest.class));
    }

    @Test
    void newlyConstrainedProblemMidGenerationReGuardsPendingContent() {
        // Turn starts restricted on problem 1001 and the draft passed L4. While
        // generating, problem 1002 became constrained too (PRIVATE); the pending
        // content leaks it — the race re-guard catches that under the NEW policy.
        stubRestrictedTurn("client-race-4", "讲讲第2题", com.aioj.next.contract.contest.ContestAiPolicyMode.DEFAULT);
        when(agentRuntime.run(any(AgentRuntime.AgentRunRequest.class)))
                .thenReturn(new AgentRuntime.AgentRunResult(42L, "第2题题面复述……", "deepseek", "deepseek-v4-pro",
                                30, 12, 0, 1, 0, false, List.of()),
                        new AgentRuntime.AgentRunResult(43L, "第2题不能讲，给学习方向。", "deepseek", "deepseek-v4-pro",
                                50, 20, 0, 1, 0, false, List.of()));
        // Initial L4 passes via the setUp default stub.
        when(policySnapshotService.recheckBeforeReturn(eq(USER_ID), eq("t-1"), eq(CONVERSATION_ID), any()))
                .thenReturn(recheckResult(true,
                        com.aioj.next.ai.agent.policy.ParticipantStatus.PARTICIPANT_ACTIVE, List.of(7701L),
                        List.of(contestProblem(1001L, com.aioj.next.contract.problem.ProblemVisibility.PUBLIC,
                                        com.aioj.next.contract.contest.ContestAiPolicyMode.DEFAULT),
                                contestProblem(1002L, com.aioj.next.contract.problem.ProblemVisibility.PRIVATE,
                                        com.aioj.next.contract.contest.ContestAiPolicyMode.DEFAULT))));
        when(contestOutputGuard.evaluate(eq("t-1"), eq(USER_ID), eq(CONVERSATION_ID), eq("第2题题面复述……"),
                any(), isNull(), eq(false), eq(com.aioj.next.ai.agent.guard.ContestOutputGuard.TRIGGER_RECHECK)))
                .thenReturn(com.aioj.next.ai.agent.guard.ContestOutputGuard.Verdict.intercept(
                        com.aioj.next.ai.agent.guard.ContestOutputGuard.REASON_PRIVATE_STATEMENT));
        when(contestOutputGuard.evaluate(eq("t-1"), eq(USER_ID), eq(CONVERSATION_ID), eq("第2题不能讲，给学习方向。"),
                any(), isNull(), eq(true), eq(com.aioj.next.ai.agent.guard.ContestOutputGuard.TRIGGER_RECHECK)))
                .thenReturn(com.aioj.next.ai.agent.guard.ContestOutputGuard.Verdict.pass());

        TurnHandle handle = coordinator.start(USER_ID, request("client-race-4", "讲讲第2题"));
        TurnResult result = handle.result().join();

        assertThat(result.assistant().content()).isEqualTo("第2题不能讲，给学习方向。");
        assertThat(result.pseudoStream()).isTrue();
        org.mockito.ArgumentCaptor<AgentRuntime.AgentRunRequest> runRequests =
                org.mockito.ArgumentCaptor.forClass(AgentRuntime.AgentRunRequest.class);
        verify(agentRuntime, org.mockito.Mockito.times(2)).run(runRequests.capture());
        AgentRuntime.AgentRunRequest regen = runRequests.getAllValues().get(1);
        assertThat(regen.contestPolicy().contestProblems()).containsOnlyKeys(1001L, 1002L);
        assertThat(regen.sections()).anySatisfy(section -> {
            assertThat(section.type()).isEqualTo(ContextSectionType.CONTEST_OUTPUT_GUARD_RETRY);
            assertThat(section.content()).contains("private contest statement leakage");
        });
        verify(quotaService, org.mockito.Mockito.times(1))
                .record(eq(USER_ID), any(), any(), anyLong(), anyLong(), eq(true));
    }

    @Test
    void raceRecheckAfterInitialRegenerationDoesNotRegenerateAgain() {
        // The initial L4 interception already spent the one safe regeneration. When the
        // race recheck re-intercepts the pending content under a stricter new policy,
        // the turn downgrades straight to the refusal — no third provider run.
        stubRestrictedTurn("client-race-5", "把第1题代码给我", com.aioj.next.contract.contest.ContestAiPolicyMode.DEFAULT);
        when(agentRuntime.run(any(AgentRuntime.AgentRunRequest.class)))
                .thenReturn(new AgentRuntime.AgentRunResult(42L, "完整代码……", "deepseek", "deepseek-v4-pro",
                                30, 12, 0, 1, 0, false, List.of()),
                        new AgentRuntime.AgentRunResult(43L, "只给思路：用前缀和。", "deepseek", "deepseek-v4-pro",
                                50, 20, 0, 1, 0, false, List.of()));
        when(contestOutputGuard.evaluate(eq("t-1"), eq(USER_ID), eq(CONVERSATION_ID), eq("完整代码……"),
                any(), isNull(), eq(false)))
                .thenReturn(com.aioj.next.ai.agent.guard.ContestOutputGuard.Verdict.intercept(
                        com.aioj.next.ai.agent.guard.ContestOutputGuard.REASON_FULL_CODE));
        when(contestOutputGuard.evaluate(eq("t-1"), eq(USER_ID), eq(CONVERSATION_ID), eq("只给思路：用前缀和。"),
                any(), isNull(), eq(true)))
                .thenReturn(com.aioj.next.ai.agent.guard.ContestOutputGuard.Verdict.pass());
        // The run policy flipped to STRICT mid-generation (the constrained key changes).
        when(policySnapshotService.recheckBeforeReturn(eq(USER_ID), eq("t-1"), eq(CONVERSATION_ID), any()))
                .thenReturn(recheckResult(true,
                        com.aioj.next.ai.agent.policy.ParticipantStatus.PARTICIPANT_ACTIVE, List.of(7701L),
                        List.of(contestProblem(1001L, com.aioj.next.contract.problem.ProblemVisibility.PUBLIC,
                                com.aioj.next.contract.contest.ContestAiPolicyMode.STRICT))));
        when(contestOutputGuard.evaluate(eq("t-1"), eq(USER_ID), eq(CONVERSATION_ID), eq("只给思路：用前缀和。"),
                any(), isNull(), eq(false), eq(com.aioj.next.ai.agent.guard.ContestOutputGuard.TRIGGER_RECHECK)))
                .thenReturn(com.aioj.next.ai.agent.guard.ContestOutputGuard.Verdict.intercept(
                        com.aioj.next.ai.agent.guard.ContestOutputGuard.REASON_FULL_CODE));

        TurnHandle handle = coordinator.start(USER_ID, request("client-race-5", "把第1题代码给我"));
        TurnResult result = handle.result().join();

        assertThat(result.assistant().content()).isEqualTo(TurnCoordinator.CONTEST_SAFE_REFUSAL_TEXT);
        // Exactly two provider runs: initial + the one shared regeneration. The race
        // re-interception cannot spend another regeneration.
        verify(agentRuntime, org.mockito.Mockito.times(2)).run(any(AgentRuntime.AgentRunRequest.class));
        verify(conversationService).completeMessage(USER_ID, 200L,
                TurnCoordinator.CONTEST_SAFE_REFUSAL_TEXT, "deepseek-v4-pro");
        verify(quotaService, org.mockito.Mockito.times(1))
                .record(eq(USER_ID), any(), any(), anyLong(), anyLong(), eq(true));
    }

    @Test
    void recheckLookupFailureFailsTurnClosed() {
        stubRestrictedTurn("client-race-6", "你好", com.aioj.next.contract.contest.ContestAiPolicyMode.DEFAULT);
        when(agentRuntime.run(any(AgentRuntime.AgentRunRequest.class)))
                .thenReturn(new AgentRuntime.AgentRunResult(42L, "提示：想想贪心。", "deepseek", "deepseek-v4-pro",
                        30, 12, 0, 1, 0, false, List.of()));
        // Recheck lookup failure: fail-closed like the turn-start L1/L2 failures.
        when(policySnapshotService.recheckBeforeReturn(eq(USER_ID), eq("t-1"), eq(CONVERSATION_ID), any()))
                .thenThrow(new com.aioj.next.common.error.DomainException(
                        com.aioj.next.common.error.ErrorCode.SERVICE_UNAVAILABLE, "比赛状态确认暂时不可用，请稍后重试"));
        when(turnService.failTurn(eq("t-1"), eq(AiTurnService.STATUS_FAILED_RETRYABLE), eq("SERVICE_UNAVAILABLE")))
                .thenReturn(true);

        TurnHandle handle = coordinator.start(USER_ID, request("client-race-6", "你好"));

        assertThat(handle.result()).isCompletedExceptionally();
        verify(quotaService).record(USER_ID, null, null, 0L, 0L, false);
        verify(conversationService).failMessage(eq(USER_ID), eq(200L), anyString());
        verify(conversationService, never()).completeMessage(any(), any(), anyString(), anyString());
        verify(turnDigestService, never()).recordCompletedTurn(any());
    }

    @Test
    void startFallsBackToConversationProblemIdAndRelaysEntryMetadata() {
        // F1/F2 wiring: a follow-up turn without request.problemId inherits the
        // conversation-bound problem; contest/submission/selection contexts pass through.
        stubUnrestrictedTurn("client-entry-1", "分析这次提交");
        AiConversationEntity conversation = conversation();
        conversation.setProblemId(880011L);
        when(conversationService.resolveForWrite(eq(USER_ID), any(AiChatRequest.class))).thenReturn(conversation);
        AiChatRequest.SelectionContext selection = new AiChatRequest.SelectionContext(
                "sel-1", CONVERSATION_ID, "assistant_message", null, null, "选中的文本", null,
                null, null, null, null, "explain");
        AiChatRequest.ContestContext contest = new AiChatRequest.ContestContext(5501L, 7701L, 99001L);
        AiChatRequest.SubmissionContext submission = new AiChatRequest.SubmissionContext(123456L, "analyze", null, null);
        AiChatRequest request = new AiChatRequest(CONVERSATION_ID, null, "分析这次提交", null, null, null, null,
                "client-entry-1", selection, contest, submission);

        coordinator.start(USER_ID, request);

        org.mockito.ArgumentCaptor<BootstrapContextBuilder.EntryContext> entryCaptor =
                org.mockito.ArgumentCaptor.forClass(BootstrapContextBuilder.EntryContext.class);
        verify(bootstrapBuilder).build(eq(USER_ID), eq(CONVERSATION_ID), eq("分析这次提交"), any(), any(),
                entryCaptor.capture(), anyInt(), anyInt());
        assertThat(entryCaptor.getValue().problemId()).isEqualTo(880011L);
        assertThat(entryCaptor.getValue().selectionContext()).isSameAs(selection);
        assertThat(entryCaptor.getValue().contestContext()).isSameAs(contest);
        assertThat(entryCaptor.getValue().submissionContext()).isSameAs(submission);
    }

    @Test
    void startPrefersRequestProblemIdOverConversationFallback() {
        stubUnrestrictedTurn("client-entry-2", "讲一下这道题");
        AiConversationEntity conversation = conversation();
        conversation.setProblemId(880011L);
        when(conversationService.resolveForWrite(eq(USER_ID), any(AiChatRequest.class))).thenReturn(conversation);
        // This request carries its own problemId, so the message persistence stubs
        // from stubUnrestrictedTurn (null problemId) are narrowed to the request value.
        when(conversationService.appendMessage(eq(CONVERSATION_ID), eq(USER_ID), eq(990022L), eq("user"),
                eq("讲一下这道题"), isNull(), eq("client-entry-2"), isNull()))
                .thenReturn(message(100L, "user", "讲一下这道题", "client-entry-2", "COMPLETED"));
        when(conversationService.appendMessageWithStatus(eq(CONVERSATION_ID), eq(USER_ID), eq(990022L),
                eq("assistant"), eq(""), isNull(), eq("client-entry-2:assistant"), isNull(), eq("RUNNING"), isNull()))
                .thenReturn(message(200L, "assistant", "", "client-entry-2:assistant", "RUNNING"));
        AiChatRequest request = new AiChatRequest(CONVERSATION_ID, 990022L, "讲一下这道题", null, null, null, null,
                "client-entry-2", null, null, null);

        coordinator.start(USER_ID, request);

        org.mockito.ArgumentCaptor<BootstrapContextBuilder.EntryContext> entryCaptor =
                org.mockito.ArgumentCaptor.forClass(BootstrapContextBuilder.EntryContext.class);
        verify(bootstrapBuilder).build(eq(USER_ID), eq(CONVERSATION_ID), eq("讲一下这道题"), any(), any(),
                entryCaptor.capture(), anyInt(), anyInt());
        assertThat(entryCaptor.getValue().problemId()).isEqualTo(990022L);
    }

    /** Non-participant turn-start: baseline snapshot, no L3, unrestricted STREAM run. */
    private void stubUnrestrictedTurn(String clientMessageId, String message) {
        AiConversationEntity conversation = conversation();
        when(conversationService.resolveForWrite(eq(USER_ID), any(AiChatRequest.class))).thenReturn(conversation);
        when(turnService.beginTurn(eq(CONVERSATION_ID), eq(clientMessageId)))
                .thenReturn(new AiTurnService.BeginTurnOutcome(turn("t-1", AiTurnService.STATUS_RECEIVED), true));
        PolicySnapshotService.PolicySnapshot snapshot = new PolicySnapshotService.PolicySnapshot(
                "ps-0", com.aioj.next.ai.agent.policy.ParticipantStatus.NON_PARTICIPANT, List.of(), "{}", "", List.of());
        when(policySnapshotService.createForTurn(eq(USER_ID), eq("t-1"), eq(CONVERSATION_ID), any())).thenReturn(snapshot);
        when(bootstrapBuilder.build(eq(USER_ID), eq(CONVERSATION_ID), eq(message), eq(snapshot), any(), any(), anyInt(), anyInt()))
                .thenReturn(new BootstrapContextBuilder.BootstrapContext(List.of(
                        ContextSection.text(ContextSectionType.CURRENT_USER_REQUEST, 90, true, TrustLevel.USER_PROVIDED, message)),
                        List.of()));
        when(conversationService.appendMessage(eq(CONVERSATION_ID), eq(USER_ID), isNull(), eq("user"), eq(message),
                isNull(), eq(clientMessageId), isNull()))
                .thenReturn(message(100L, "user", message, clientMessageId, "COMPLETED"));
        when(conversationService.appendMessageWithStatus(eq(CONVERSATION_ID), eq(USER_ID), isNull(), eq("assistant"),
                eq(""), isNull(), eq(clientMessageId + ":assistant"), isNull(), eq("RUNNING"), isNull()))
                .thenReturn(message(200L, "assistant", "", clientMessageId + ":assistant", "RUNNING"));
        when(turnService.advanceToGenerating("t-1", "t-1")).thenReturn(true);
        when(responseNormalizer.normalize(any(AiCompletion.class)))
                .thenAnswer(invocation -> new AiAssistantResponseNormalizer.NormalizedResponse(
                        invocation.getArgument(0), Map.of(), Map.of(), List.of(), false));
        when(turnService.completeTurn("t-1")).thenReturn(true);
        when(conversationService.completeMessage(eq(USER_ID), eq(200L), anyString(), anyString()))
                .thenAnswer(invocation -> message(200L, "assistant", invocation.getArgument(2),
                        clientMessageId + ":assistant", "COMPLETED"));
        when(conversationService.updateAutomaticMode(eq(USER_ID), eq(CONVERSATION_ID), any(AiChatRequest.class), any(AiCompletion.class)))
                .thenReturn("qa");
    }

    /** A recheck outcome as PolicySnapshotService would compute it for the given CURRENT state. */
    private PolicySnapshotService.PolicyRecheck recheckResult(boolean changed,
            com.aioj.next.ai.agent.policy.ParticipantStatus status, List<Long> runIds,
            List<com.aioj.next.contract.contest.RunningContestProblemStatement> statements) {
        List<com.aioj.next.contract.contest.RunningContestParticipation> runs = runIds.stream()
                .map(runId -> new com.aioj.next.contract.contest.RunningContestParticipation(
                        5501L, runId, Instant.now().minusSeconds(3600), Instant.now().plusSeconds(3600)))
                .toList();
        return new PolicySnapshotService.PolicyRecheck(changed,
                new com.aioj.next.ai.agent.policy.ContestParticipationService.ParticipationView(
                        status, runs, runs.isEmpty() ? null : runs.get(0)),
                statements == null ? List.of() : statements);
    }

    private com.aioj.next.contract.contest.RunningContestProblemStatement contestProblem(long problemId,
            com.aioj.next.contract.problem.ProblemVisibility visibility,
            com.aioj.next.contract.contest.ContestAiPolicyMode mode) {
        return new com.aioj.next.contract.contest.RunningContestProblemStatement(
                problemId, "题面" + problemId, 5501L, 7701L, 99000L + problemId, visibility, mode, null,
                List.of(new com.aioj.next.contract.contest.RunningContestProblemOccurrence(5501L, 7701L, 99000L + problemId)));
    }

    /**
     * Common stubs for one participant turn whose snapshot constrains exactly one
     * problem with the given policy mode. completeMessage echoes the delivered
     * content so assertions read what would be persisted.
     */
    private void stubRestrictedTurn(String clientMessageId, String message,
                                    com.aioj.next.contract.contest.ContestAiPolicyMode policyMode) {
        AiConversationEntity conversation = conversation();
        when(conversationService.resolveForWrite(eq(USER_ID), any(AiChatRequest.class))).thenReturn(conversation);
        when(turnService.beginTurn(eq(CONVERSATION_ID), eq(clientMessageId)))
                .thenReturn(new AiTurnService.BeginTurnOutcome(turn("t-1", AiTurnService.STATUS_RECEIVED), true));
        PolicySnapshotService.PolicySnapshot snapshot = new PolicySnapshotService.PolicySnapshot(
                "ps-1", com.aioj.next.ai.agent.policy.ParticipantStatus.PARTICIPANT_ACTIVE, List.of(7701L), "{}",
                "prompt",
                List.of(new com.aioj.next.contract.contest.RunningContestProblemStatement(
                        1001L, "比赛题面", 5501L, 7701L, 99001L,
                        com.aioj.next.contract.problem.ProblemVisibility.PUBLIC,
                        policyMode, null,
                        List.of(new com.aioj.next.contract.contest.RunningContestProblemOccurrence(5501L, 7701L, 99001L)))));
        when(policySnapshotService.createForTurn(eq(USER_ID), eq("t-1"), eq(CONVERSATION_ID), any())).thenReturn(snapshot);
        when(fingerprintMatcher.match(eq(message), anyList()))
                .thenReturn(com.aioj.next.ai.agent.guard.GuardVerdict.pass());
        when(bootstrapBuilder.build(eq(USER_ID), eq(CONVERSATION_ID), eq(message), eq(snapshot), any(), any(), anyInt(), anyInt()))
                .thenReturn(new BootstrapContextBuilder.BootstrapContext(List.of(
                        ContextSection.text(ContextSectionType.CURRENT_USER_REQUEST, 90, true, TrustLevel.USER_PROVIDED, message)),
                        List.of()));
        when(conversationService.appendMessage(eq(CONVERSATION_ID), eq(USER_ID), isNull(), eq("user"), eq(message),
                isNull(), eq(clientMessageId), isNull()))
                .thenReturn(message(100L, "user", message, clientMessageId, "COMPLETED"));
        when(conversationService.appendMessageWithStatus(eq(CONVERSATION_ID), eq(USER_ID), isNull(), eq("assistant"),
                eq(""), isNull(), eq(clientMessageId + ":assistant"), isNull(), eq("RUNNING"), isNull()))
                .thenReturn(message(200L, "assistant", "", clientMessageId + ":assistant", "RUNNING"));
        when(turnService.advanceToGenerating("t-1", "t-1")).thenReturn(true);
        when(responseNormalizer.normalize(any(AiCompletion.class)))
                .thenAnswer(invocation -> new AiAssistantResponseNormalizer.NormalizedResponse(
                        invocation.getArgument(0), Map.of(), Map.of(), List.of(), false));
        when(turnService.completeTurn("t-1")).thenReturn(true);
        when(conversationService.completeMessage(eq(USER_ID), eq(200L), anyString(), anyString()))
                .thenAnswer(invocation -> message(200L, "assistant", invocation.getArgument(2),
                        clientMessageId + ":assistant", "COMPLETED"));
        when(conversationService.updateAutomaticMode(eq(USER_ID), eq(CONVERSATION_ID), any(AiChatRequest.class), any(AiCompletion.class)))
                .thenReturn("qa");
    }

    private AiChatRequest request(String clientMessageId, String message) {
        return new AiChatRequest(CONVERSATION_ID, null, message, null, null, null, null,
                clientMessageId, null, null, null);
    }

    private AiConversationEntity conversation() {
        AiConversationEntity conversation = new AiConversationEntity();
        conversation.setId(CONVERSATION_ID);
        conversation.setUserId(USER_ID);
        return conversation;
    }

    private AiTurnEntity turn(String id, String status) {
        AiTurnEntity turn = new AiTurnEntity();
        turn.setId(id);
        turn.setConversationId(CONVERSATION_ID);
        turn.setStatus(status);
        return turn;
    }

    private AiChatMessageResponse message(Long id, String role, String content, String clientMessageId, String status) {
        return new AiChatMessageResponse(id, CONVERSATION_ID, null, clientMessageId, role, content, null,
                status, null, Instant.now(), "COMPLETED".equals(status) ? Instant.now() : null);
    }
}
