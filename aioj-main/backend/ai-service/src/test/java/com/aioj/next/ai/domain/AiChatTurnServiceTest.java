package com.aioj.next.ai.domain;

import com.aioj.next.ai.domain.clarification.ClarificationSchemaRepairer;
import com.aioj.next.ai.domain.context.AiContextBuildReport;
import com.aioj.next.ai.domain.memory.AiAfterTurnMemoryProfileEventService;
import com.aioj.next.ai.domain.memory.AiMemoryClarificationService;
import com.aioj.next.ai.domain.response.AiAssistantResponseNormalizer;
import com.aioj.next.contract.ai.AiChatMessageResponse;
import com.aioj.next.contract.ai.AiChatRequest;
import com.aioj.next.contract.contest.ContestAiPolicyMode;
import com.aioj.next.contract.contest.ContestAiPolicyResponse;
import com.aioj.next.contract.problem.ProblemVisibility;
import com.aioj.next.ai.persistence.entity.AiConversationEntity;
import com.aioj.next.ai.persistence.entity.AiTurnEntity;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.mockito.ArgumentCaptor;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.Executor;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class AiChatTurnServiceTest {
    private static final Long USER_ID = 7L;
    private static final String CONVERSATION_ID = "c-async-turn";

    @Test
    void startCreatesUserAndRunningAssistantBeforeBackgroundCompletion() {
        Fixture fixture = new Fixture(new CapturingExecutor());
        when(fixture.provider.chat(any(AiChatRequest.class), any(AiChatContext.class)))
                .thenReturn(new AiCompletion("你好，我会继续帮你分析。", "mock", "mock-model", 12, 8));
        when(fixture.contextService.prepareCompletionForTurn(eq(USER_ID), eq(fixture.conversation), any(AiChatRequest.class), any(AiCompletion.class)))
                .thenAnswer(invocation -> invocation.getArgument(3));
        when(fixture.conversationService.completeMessage(eq(USER_ID), eq(200L), eq("你好，我会继续帮你分析。"), eq("mock-model")))
                .thenReturn(message(200L, "assistant", "你好，我会继续帮你分析。", "client-1:assistant", "COMPLETED"));
        when(fixture.conversationService.updateAutomaticMode(eq(USER_ID), eq(CONVERSATION_ID), any(AiChatRequest.class), any(AiCompletion.class)))
                .thenReturn("qa");

        AiChatTurnService.TurnHandle handle = fixture.service.start(USER_ID, request("client-1"));

        assertThat(handle.user().id()).isEqualTo(100L);
        assertThat(handle.assistant().id()).isEqualTo(200L);
        assertThat(handle.assistant().status()).isEqualTo("RUNNING");
        assertThat(handle.result()).isNotDone();

        fixture.executor.runCaptured();

        assertThat(handle.result()).isCompleted();
        assertThat(handle.result().join().assistant().status()).isEqualTo("COMPLETED");
        verify(fixture.quotaService).record(USER_ID, "mock", "mock-model", 12, 8, true);
        verify(fixture.contextService).afterTurn(eq(USER_ID), eq(fixture.conversation), any(AiChatRequest.class), any(AiCompletion.class), eq(fixture.context), eq(100L), eq(200L));
        verify(fixture.afterTurnEventService).recordCompletedTurn(eq(USER_ID), eq(fixture.conversation), any(AiChatRequest.class), eq(handle.user()), eq(handle.result().join().assistant()));
    }

    @Test
    void backgroundTurnContinuesWithoutAnyStreamConsumer() {
        Fixture fixture = new Fixture(new CapturingExecutor());
        when(fixture.provider.chat(any(AiChatRequest.class), any(AiChatContext.class)))
                .thenReturn(new AiCompletion("后台已完成。", "mock", "mock-model", 10, 5));
        when(fixture.contextService.prepareCompletionForTurn(eq(USER_ID), eq(fixture.conversation), any(AiChatRequest.class), any(AiCompletion.class)))
                .thenAnswer(invocation -> invocation.getArgument(3));
        when(fixture.conversationService.completeMessage(eq(USER_ID), eq(200L), eq("后台已完成。"), eq("mock-model")))
                .thenReturn(message(200L, "assistant", "后台已完成。", "client-2:assistant", "COMPLETED"));
        when(fixture.conversationService.updateAutomaticMode(eq(USER_ID), eq(CONVERSATION_ID), any(AiChatRequest.class), any(AiCompletion.class)))
                .thenReturn("qa");

        AiChatTurnService.TurnHandle handle = fixture.service.start(USER_ID, request("client-2"));

        fixture.executor.runCaptured();

        assertThat(handle.result().join().assistant().content()).isEqualTo("后台已完成。");
        verify(fixture.conversationService).completeMessage(USER_ID, 200L, "后台已完成。", "mock-model");
    }

    @Test
    void startUsesContestPolicyResolvedFromSelectedSubmissionContext() {
        Fixture fixture = new Fixture(new CapturingExecutor());
        ContestAiPolicyResponse selectedSubmissionPolicy = new ContestAiPolicyResponse(
                true,
                1L,
                2L,
                3L,
                99L,
                null,
                null,
                null,
                true,
                true,
                true,
                false,
                false,
                true,
                12,
                "比赛进行中隐藏源码。"
        );
        AiChatContext selectedSubmissionContext = new AiChatContext(
                "",
                "",
                "",
                "",
                "[Selected Submission Context]\ncontestActive: true",
                Map.of("submissionId", "123", "contestActive", true),
                AiContextBuildReport.empty(),
                selectedSubmissionPolicy
        );
        when(fixture.contextService.build(eq(USER_ID), eq(fixture.conversation), any(AiChatRequest.class)))
                .thenReturn(selectedSubmissionContext);
        when(fixture.contextService.snapshot(selectedSubmissionContext)).thenReturn("{}");
        when(fixture.provider.chat(any(AiChatRequest.class), eq(selectedSubmissionContext)))
                .thenReturn(new AiCompletion("我只能给调试方向。", "mock", "mock-model", 10, 5));
        when(fixture.contextService.prepareCompletionForTurn(eq(USER_ID), eq(fixture.conversation), any(AiChatRequest.class), any(AiCompletion.class)))
                .thenAnswer(invocation -> invocation.getArgument(3));
        when(fixture.conversationService.completeMessage(eq(USER_ID), eq(200L), eq("我只能给调试方向。"), eq("mock-model")))
                .thenReturn(message(200L, "assistant", "我只能给调试方向。", "client-contest:assistant", "COMPLETED"));
        when(fixture.conversationService.updateAutomaticMode(eq(USER_ID), eq(CONVERSATION_ID), any(AiChatRequest.class), any(AiCompletion.class)))
                .thenReturn("qa");

        AiChatTurnService.TurnHandle handle = fixture.service.start(USER_ID, request("client-contest"));
        fixture.executor.runCaptured();

        assertThat(handle.result()).isCompleted();
        verify(fixture.responsePolicyGuard).guard(eq(USER_ID), eq(CONVERSATION_ID), any(AiCompletion.class), eq(true));
    }

    @Test
    @SuppressWarnings({"rawtypes", "unchecked"})
    void responseGuardReplacementIsPersistedAuditedAndQueuedForMemory() {
        Fixture fixture = new Fixture(new CapturingExecutor());
        when(fixture.contestTurnGuard.evaluateAndApply(eq(USER_ID), any(AiChatRequest.class)))
                .thenAnswer(invocation -> constrainDecision(invocation.getArgument(1)));
        AiCompletion unsafe = new AiCompletion("""
                ```cpp
                #include <bits/stdc++.h>
                using namespace std;
                int main() { int n; cin >> n; cout << n << '\\n'; }
                ```
                """, "mock", "mock-model", 10, 20);
        AiCompletion safe = new AiCompletion("比赛进行中我不能给出完整可提交代码或标程。", "mock", "mock-model", 10, 20, "DEBUG", null, null, null);
        when(fixture.provider.chat(any(AiChatRequest.class), any(AiChatContext.class))).thenReturn(unsafe);
        when(fixture.contextService.prepareCompletionForTurn(eq(USER_ID), eq(fixture.conversation), any(AiChatRequest.class), eq(unsafe)))
                .thenReturn(unsafe);
        when(fixture.responsePolicyGuard.guard(eq(USER_ID), eq(CONVERSATION_ID), eq(unsafe), eq(true)))
                .thenReturn(new AiResponsePolicyGuard.GuardedCompletion(safe, true, "SUBMIT_READY_CODE_DETECTED"));
        when(fixture.conversationService.completeMessage(eq(USER_ID), eq(200L), eq(safe.content()), eq("mock-model")))
                .thenReturn(message(200L, "assistant", safe.content(), "client-contest-code:assistant", "COMPLETED"));
        when(fixture.conversationService.updateAutomaticMode(eq(USER_ID), eq(CONVERSATION_ID), any(AiChatRequest.class), eq(safe)))
                .thenReturn("qa");

        AiChatTurnService.TurnHandle handle = fixture.service.start(USER_ID, request("client-contest-code"));
        fixture.executor.runCaptured();

        assertThat(handle.result()).isCompleted();
        verify(fixture.conversationService).completeMessage(USER_ID, 200L, safe.content(), "mock-model");
        ArgumentCaptor<AiChatMessageResponse> assistantCaptor = ArgumentCaptor.forClass(AiChatMessageResponse.class);
        verify(fixture.afterTurnEventService).recordCompletedTurn(eq(USER_ID), eq(fixture.conversation), any(AiChatRequest.class), any(AiChatMessageResponse.class), assistantCaptor.capture());
        assertThat(assistantCaptor.getValue().content()).isEqualTo(safe.content());
        assertThat(assistantCaptor.getValue().content()).doesNotContain("#include", "int main", "cin >>");
        ArgumentCaptor<Map> summaryCaptor = ArgumentCaptor.forClass(Map.class);
        verify(fixture.auditWriter).record(
                eq("AI_CONTEST_RESPONSE_REPLACED"),
                eq("CONTEST_AI_POLICY"),
                eq(33L),
                eq("REPLACED"),
                summaryCaptor.capture(),
                eq(USER_ID),
                eq(11L),
                eq(22L),
                eq(USER_ID)
        );
        assertThat(summaryCaptor.getValue().toString())
                .contains("SUBMIT_READY_CODE_DETECTED", CONVERSATION_ID)
                .doesNotContain("#include", "int main", "cin >>");
    }

    @Test
    void responseAuditFailureDoesNotFailSafeReplacement() {
        Fixture fixture = new Fixture(new CapturingExecutor());
        when(fixture.contestTurnGuard.evaluateAndApply(eq(USER_ID), any(AiChatRequest.class)))
                .thenAnswer(invocation -> constrainDecision(invocation.getArgument(1)));
        AiCompletion unsafe = new AiCompletion("""
                ```cpp
                #include <bits/stdc++.h>
                int main() { return 0; }
                ```
                """, "mock", "mock-model", 10, 20);
        AiCompletion safe = new AiCompletion("比赛进行中我不能给出完整可提交代码或标程。", "mock", "mock-model", 10, 20);
        when(fixture.provider.chat(any(AiChatRequest.class), any(AiChatContext.class))).thenReturn(unsafe);
        when(fixture.contextService.prepareCompletionForTurn(eq(USER_ID), eq(fixture.conversation), any(AiChatRequest.class), eq(unsafe)))
                .thenReturn(unsafe);
        when(fixture.responsePolicyGuard.guard(eq(USER_ID), eq(CONVERSATION_ID), eq(unsafe), eq(true)))
                .thenReturn(new AiResponsePolicyGuard.GuardedCompletion(safe, true, "SUBMIT_READY_CODE_DETECTED"));
        doThrow(new IllegalStateException("audit unavailable"))
                .when(fixture.auditWriter).record(any(), any(), any(), any(), any(), any(), any(), any(), any());
        when(fixture.conversationService.completeMessage(eq(USER_ID), eq(200L), eq(safe.content()), eq("mock-model")))
                .thenReturn(message(200L, "assistant", safe.content(), "client-audit-fail:assistant", "COMPLETED"));
        when(fixture.conversationService.updateAutomaticMode(eq(USER_ID), eq(CONVERSATION_ID), any(AiChatRequest.class), eq(safe)))
                .thenReturn("qa");

        AiChatTurnService.TurnHandle handle = fixture.service.start(USER_ID, request("client-audit-fail"));
        fixture.executor.runCaptured();

        assertThat(handle.result()).isCompleted();
        assertThat(handle.result().join().assistant().content()).isEqualTo(safe.content());
        verify(fixture.conversationService).completeMessage(USER_ID, 200L, safe.content(), "mock-model");
    }

    @Test
    void providerFailureMarksAssistantFailedAndDoesNotExtractMemory() {
        Fixture fixture = new Fixture(new CapturingExecutor());
        when(fixture.provider.chat(any(AiChatRequest.class), any(AiChatContext.class)))
                .thenThrow(new IllegalStateException("upstream closed"));

        AiChatTurnService.TurnHandle handle = fixture.service.start(USER_ID, request("client-3"));
        fixture.executor.runCaptured();

        assertThatThrownBy(() -> handle.result().join())
                .hasRootCauseMessage("AI provider call failed");
        verify(fixture.conversationService).failMessage(USER_ID, 200L, "AI provider call failed");
        verify(fixture.quotaService).record(USER_ID, "mock-provider", "mock-model", 0, 0, false);
        verify(fixture.afterTurnEventService, never()).recordCompletedTurn(any(), any(), any(), any(), any());
    }

    @Test
    void afterTurnJobEnqueueFailureDoesNotFailCompletedChat() {
        Fixture fixture = new Fixture(new CapturingExecutor());
        when(fixture.provider.chat(any(AiChatRequest.class), any(AiChatContext.class)))
                .thenReturn(new AiCompletion("回答已经完成。", "mock", "mock-model", 10, 5));
        when(fixture.contextService.prepareCompletionForTurn(eq(USER_ID), eq(fixture.conversation), any(AiChatRequest.class), any(AiCompletion.class)))
                .thenAnswer(invocation -> invocation.getArgument(3));
        when(fixture.conversationService.completeMessage(eq(USER_ID), eq(200L), eq("回答已经完成。"), eq("mock-model")))
                .thenReturn(message(200L, "assistant", "回答已经完成。", "client-event-fail:assistant", "COMPLETED"));
        when(fixture.conversationService.updateAutomaticMode(eq(USER_ID), eq(CONVERSATION_ID), any(AiChatRequest.class), any(AiCompletion.class)))
                .thenReturn("qa");
        doThrow(new IllegalStateException("event store unavailable"))
                .when(fixture.afterTurnEventService).recordCompletedTurn(any(), any(), any(), any(), any());

        AiChatTurnService.TurnHandle handle = fixture.service.start(USER_ID, request("client-event-fail"));
        fixture.executor.runCaptured();

        assertThat(handle.result()).isCompleted();
        assertThat(handle.result().join().assistant().content()).isEqualTo("回答已经完成。");
        verify(fixture.conversationService).completeMessage(USER_ID, 200L, "回答已经完成。", "mock-model");
    }

    @Test
    void eligibleMemoryCandidateClarificationIsAttachedAndMarkedAsked() {
        Fixture fixture = new Fixture(new CapturingExecutor());
        AiCompletion.Clarification clarification = new AiCompletion.Clarification(
                "memory_candidate_701",
                "confirm",
                "确认学习记忆",
                "需要记住这个候选偏好吗？",
                AiCompletion.ClarificationInput.empty(),
                List.of(),
                "ask_user",
                null
        );
        AiMemoryClarificationService.PlannedClarification plan =
                new AiMemoryClarificationService.PlannedClarification(701L, CONVERSATION_ID, clarification);
        when(fixture.provider.chat(any(AiChatRequest.class), any(AiChatContext.class)))
                .thenReturn(new AiCompletion("我先继续回答问题。", "mock", "mock-model", 10, 5));
        when(fixture.contextService.prepareCompletionForTurn(eq(USER_ID), eq(fixture.conversation), any(AiChatRequest.class), any(AiCompletion.class)))
                .thenAnswer(invocation -> invocation.getArgument(3));
        when(fixture.memoryClarificationService.planClarification(USER_ID, CONVERSATION_ID))
                .thenReturn(Optional.of(plan));
        when(fixture.conversationService.completeMessage(eq(USER_ID), eq(200L), eq("我先继续回答问题。"), eq("mock-model")))
                .thenReturn(message(200L, "assistant", "我先继续回答问题。", "client-memory-clarify:assistant", "COMPLETED"));
        when(fixture.conversationService.updateAutomaticMode(eq(USER_ID), eq(CONVERSATION_ID), any(AiChatRequest.class), any(AiCompletion.class)))
                .thenReturn("qa");

        AiChatTurnService.TurnHandle handle = fixture.service.start(USER_ID, request("client-memory-clarify"));
        fixture.executor.runCaptured();

        AiCompletion completion = handle.result().join().completion();
        assertThat(completion.hasClarification()).isTrue();
        assertThat(completion.clarification().id()).isEqualTo("memory_candidate_701");
        verify(fixture.memoryClarificationService).markAsked(USER_ID, plan, 200L);
    }

    @Test
    void providerClarificationIsNotOverriddenByMemoryCandidateClarification() {
        Fixture fixture = new Fixture(new CapturingExecutor());
        AiCompletion.Clarification providerClarification = new AiCompletion.Clarification(
                "provider_clarify",
                "helpful",
                "确认题意",
                "你想先看哪一部分？",
                AiCompletion.ClarificationInput.empty(),
                List.of(),
                "ask_user",
                null
        );
        when(fixture.provider.chat(any(AiChatRequest.class), any(AiChatContext.class)))
                .thenReturn(new AiCompletion("先分析边界。", "mock", "mock-model", 10, 5, providerClarification));
        when(fixture.contextService.prepareCompletionForTurn(eq(USER_ID), eq(fixture.conversation), any(AiChatRequest.class), any(AiCompletion.class)))
                .thenAnswer(invocation -> invocation.getArgument(3));
        when(fixture.conversationService.completeMessage(eq(USER_ID), eq(200L), eq("先分析边界。"), eq("mock-model")))
                .thenReturn(message(200L, "assistant", "先分析边界。", "client-provider-clarify:assistant", "COMPLETED"));
        when(fixture.conversationService.updateAutomaticMode(eq(USER_ID), eq(CONVERSATION_ID), any(AiChatRequest.class), any(AiCompletion.class)))
                .thenReturn("qa");

        AiChatTurnService.TurnHandle handle = fixture.service.start(USER_ID, request("client-provider-clarify"));
        fixture.executor.runCaptured();

        assertThat(handle.result().join().completion().clarification().id()).isEqualTo("provider_clarify");
        verify(fixture.memoryClarificationService, never()).planClarification(any(), any());
    }

    @Test
    void memoryClarificationAnswerIsAppliedBeforeContextBuildWithoutBlockingChat() {
        Fixture fixture = new Fixture(new CapturingExecutor());
        AiChatRequest.ClarificationAnswer answer = new AiChatRequest.ClarificationAnswer(
                "memory_candidate_701",
                "需要记住吗？",
                "记住",
                List.of("记住"),
                ""
        );
        AiChatRequest request = new AiChatRequest(CONVERSATION_ID, null, "hello", "assist", null, null, answer, "client-answer", null);
        when(fixture.provider.chat(any(AiChatRequest.class), any(AiChatContext.class)))
                .thenReturn(new AiCompletion("收到。", "mock", "mock-model", 10, 5));
        when(fixture.contextService.prepareCompletionForTurn(eq(USER_ID), eq(fixture.conversation), any(AiChatRequest.class), any(AiCompletion.class)))
                .thenAnswer(invocation -> invocation.getArgument(3));
        when(fixture.conversationService.completeMessage(eq(USER_ID), eq(200L), eq("收到。"), eq("mock-model")))
                .thenReturn(message(200L, "assistant", "收到。", "client-answer:assistant", "COMPLETED"));
        when(fixture.conversationService.updateAutomaticMode(eq(USER_ID), eq(CONVERSATION_ID), any(AiChatRequest.class), any(AiCompletion.class)))
                .thenReturn("qa");

        AiChatTurnService.TurnHandle handle = fixture.service.start(USER_ID, request);
        fixture.executor.runCaptured();

        assertThat(handle.result()).isCompleted();
        verify(fixture.memoryClarificationService).applyAnswer(USER_ID, CONVERSATION_ID, answer);
        verify(fixture.memoryClarificationService, never()).planClarification(any(), any());
    }

    @Test
    void turnTimeoutMarksAssistantFailedAndIgnoresLateProviderWork() throws Exception {
        Fixture fixture = new Fixture(new CapturingExecutor(), 10);
        when(fixture.provider.chat(any(AiChatRequest.class), any(AiChatContext.class)))
                .thenReturn(new AiCompletion("迟到的回答不应该落库。", "mock", "mock-model", 10, 5));

        AiChatTurnService.TurnHandle handle = fixture.service.start(USER_ID, request("client-timeout"));

        Thread.sleep(80);

        assertThat(handle.result()).isCompletedExceptionally();
        assertThatThrownBy(() -> handle.result().join())
                .hasRootCauseMessage("AI provider call timed out");
        verify(fixture.conversationService).failMessage(USER_ID, 200L, "AI provider call timed out");
        verify(fixture.quotaService).record(USER_ID, "mock-provider", "mock-model", 0, 0, false);

        fixture.executor.runCaptured();

        verify(fixture.provider, never()).chat(any(AiChatRequest.class), any(AiChatContext.class));
        verify(fixture.conversationService, never()).completeMessage(eq(USER_ID), eq(200L), any(), any());
        verify(fixture.afterTurnEventService, never()).recordCompletedTurn(any(), any(), any(), any(), any());
    }

    @Test
    void duplicateClientTurnIdReplaysStoredAssistantWithoutSecondProviderCall() {
        Fixture fixture = new Fixture(new CapturingExecutor());
        stubSuccessfulCompletion(fixture, "你好，我会继续帮你分析。", "client-dup");

        AiChatTurnService.TurnHandle first = fixture.service.start(USER_ID, request("client-dup"));
        fixture.executor.runCaptured();
        assertThat(first.result()).isCompleted();

        AiChatTurnService.TurnHandle duplicate = fixture.service.start(USER_ID, request("client-dup"));

        assertThat(duplicate.result()).isCompleted();
        assertThat(duplicate.turnId()).isEqualTo(first.turnId());
        assertThat(duplicate.result().join().assistant().content()).isEqualTo("你好，我会继续帮你分析。");
        verify(fixture.provider, times(1)).chat(any(AiChatRequest.class), any(AiChatContext.class));
        verify(fixture.conversationService, times(1))
                .appendMessage(eq(CONVERSATION_ID), eq(USER_ID), any(), eq("user"), eq("hello"), any(), any(), eq("{}"));
        verify(fixture.conversationService, times(1)).completeMessage(eq(USER_ID), eq(200L), any(), any());
        verify(fixture.quotaService, times(1)).record(USER_ID, "mock", "mock-model", 12, 8, true);
        verify(fixture.quotaService, never()).record(eq(USER_ID), any(), any(), eq(0L), eq(0L), eq(false));
    }

    @Test
    void duplicateWhileInFlightAttachesToSameTurnWithoutRegeneration() {
        Fixture fixture = new Fixture(new CapturingExecutor());
        stubSuccessfulCompletion(fixture, "回答已经完成。", "client-inflight");

        AiChatTurnService.TurnHandle first = fixture.service.start(USER_ID, request("client-inflight"));
        AiChatTurnService.TurnHandle duplicate = fixture.service.start(USER_ID, request("client-inflight"));

        assertThat(duplicate.turnId()).isEqualTo(first.turnId());
        fixture.executor.runCaptured();

        assertThat(first.result().join().assistant().content()).isEqualTo("回答已经完成。");
        assertThat(duplicate.result().join().assistant().content()).isEqualTo("回答已经完成。");
        verify(fixture.provider, times(1)).chat(any(AiChatRequest.class), any(AiChatContext.class));
        verify(fixture.conversationService, times(1))
                .appendMessage(eq(CONVERSATION_ID), eq(USER_ID), any(), eq("user"), eq("hello"), any(), any(), eq("{}"));
        verify(fixture.quotaService, times(1)).record(USER_ID, "mock", "mock-model", 12, 8, true);
    }

    @Test
    void duplicateResumeFailureDoesNotMutateInFlightTurn() {
        Fixture fixture = new Fixture(new CapturingExecutor());
        stubSuccessfulCompletion(fixture, "回答已经完成。", "client-shared");

        AiChatTurnService.TurnHandle first = fixture.service.start(USER_ID, request("client-shared"));
        when(fixture.conversationService.getMessage(eq(USER_ID), eq(100L)))
                .thenThrow(new IllegalStateException("database unavailable"));

        assertThatThrownBy(() -> fixture.service.start(USER_ID, request("client-shared")))
                .hasMessageContaining("database unavailable");
        assertThat(fixture.turnService.findById(first.turnId()).getStatus())
                .isEqualTo(AiTurnService.STATUS_BUILDING_CONTEXT);

        fixture.executor.runCaptured();

        assertThat(first.result()).isCompleted();
        assertThat(fixture.turnService.findById(first.turnId()).getStatus())
                .isEqualTo(AiTurnService.STATUS_COMPLETED);
    }

    @Test
    void resumeByTurnIdReplaysCompletedTurnWithoutProviderCall() {
        Fixture fixture = new Fixture(new CapturingExecutor());
        stubSuccessfulCompletion(fixture, "已完成的回答。", "client-resume");

        AiChatTurnService.TurnHandle first = fixture.service.start(USER_ID, request("client-resume"));
        fixture.executor.runCaptured();
        assertThat(first.result()).isCompleted();

        AiChatTurnService.TurnHandle resumed = fixture.service.resume(USER_ID, first.turnId(), request("client-resume"));

        assertThat(resumed.result()).isCompleted();
        assertThat(resumed.result().join().assistant().content()).isEqualTo("已完成的回答。");
        verify(fixture.provider, times(1)).chat(any(AiChatRequest.class), any(AiChatContext.class));
    }

    @Test
    void duplicateOfFailedTurnSurfacesStoredFailureWithoutRegeneration() {
        Fixture fixture = new Fixture(new CapturingExecutor());
        when(fixture.provider.chat(any(AiChatRequest.class), any(AiChatContext.class)))
                .thenThrow(new IllegalStateException("upstream closed"));

        AiChatTurnService.TurnHandle first = fixture.service.start(USER_ID, request("client-fail"));
        fixture.executor.runCaptured();
        assertThatThrownBy(() -> first.result().join())
                .hasRootCauseMessage("AI provider call failed");

        when(fixture.conversationService.getMessage(eq(USER_ID), eq(100L)))
                .thenReturn(message(100L, "user", "hello", "client-fail", "COMPLETED"));
        when(fixture.conversationService.getMessage(eq(USER_ID), eq(200L)))
                .thenReturn(new AiChatMessageResponse(
                        200L,
                        CONVERSATION_ID,
                        null,
                        "client-fail:assistant",
                        "assistant",
                        "",
                        "mock-model",
                        "FAILED",
                        "AI provider call failed",
                        Instant.now(),
                        Instant.now()
                ));

        AiChatTurnService.TurnHandle duplicate = fixture.service.start(USER_ID, request("client-fail"));

        assertThatThrownBy(() -> duplicate.result().join())
                .hasRootCauseMessage("AI provider call failed");
        verify(fixture.provider, times(1)).chat(any(AiChatRequest.class), any(AiChatContext.class));
        verify(fixture.conversationService, times(1))
                .appendMessage(eq(CONVERSATION_ID), eq(USER_ID), any(), eq("user"), eq("hello"), any(), any(), eq("{}"));
        verify(fixture.quotaService, times(1)).record(USER_ID, "mock-provider", "mock-model", 0, 0, false);
    }

    @Test
    void sequentialTurnsOfSameConversationGetMonotonicTurnSeq() {
        Fixture fixture = new Fixture(new CapturingExecutor());
        stubSuccessfulCompletion(fixture, "顺序回答。", "client-seq-a");

        AiChatTurnService.TurnHandle first = fixture.service.start(USER_ID, request("client-seq-a"));
        AiChatTurnService.TurnHandle second = fixture.service.start(USER_ID, request("client-seq-b"));
        fixture.executor.runCaptured();
        fixture.executor.runCaptured();

        assertThat(first.result()).isCompleted();
        assertThat(second.result()).isCompleted();
        long firstSeq = fixture.turnService.findById(first.turnId()).getTurnSeq();
        long secondSeq = fixture.turnService.findById(second.turnId()).getTurnSeq();
        assertThat(secondSeq).isGreaterThan(firstSeq);
    }

    @Test
    void refusedTurnOwnsARefusedTurnRowAndSkipsDuplicatePersistence() {
        Fixture fixture = new Fixture(new CapturingExecutor());
        when(fixture.contestTurnGuard.evaluateAndApply(eq(USER_ID), any(AiChatRequest.class)))
                .thenAnswer(invocation -> refuseDecision(invocation.getArgument(1)));

        assertThatThrownBy(() -> fixture.service.start(USER_ID, refuseRequest("client-refuse")))
                .isInstanceOf(ContestProblemLeakBlockedException.class);

        AiTurnEntity turn = fixture.turnService.findByClientTurnId(CONVERSATION_ID, "client-refuse");
        assertThat(turn).isNotNull();
        assertThat(turn.getStatus()).isEqualTo(AiTurnService.STATUS_REFUSED);
        assertThat(turn.getErrorCode()).isEqualTo(AiTurnService.ERROR_CONTEST_GUARD_REFUSE);
        assertThat(AiTurnService.isTerminal(turn.getStatus())).isTrue();

        // Same clientTurnId retry: the existing REFUSED row is found, nothing is persisted again.
        assertThatThrownBy(() -> fixture.service.start(USER_ID, refuseRequest("client-refuse")))
                .isInstanceOf(ContestProblemLeakBlockedException.class);

        assertThat(fixture.turnService.findByClientTurnId(CONVERSATION_ID, "client-refuse").getId())
                .isEqualTo(turn.getId());
        verify(fixture.conversationService, times(1)).appendMessage(
                eq(CONVERSATION_ID), eq(USER_ID), eq(33L), eq("user"), eq("contest leak question"),
                isNull(), eq("client-refuse"), isNull(), any(AiChatRequest.ContestContext.class));
        verify(fixture.quotaService, times(1)).record(eq(USER_ID), isNull(), isNull(), eq(0L), eq(0L), eq(false),
                any(AiChatRequest.ContestContext.class));
        verify(fixture.provider, never()).chat(any(AiChatRequest.class), any(AiChatContext.class));
    }

    private static AiChatRequest refuseRequest(String clientMessageId) {
        return new AiChatRequest(CONVERSATION_ID, null, "contest leak question", "assist", null, null, null, clientMessageId, null);
    }

    private static ContestTurnGuard.GuardDecision refuseDecision(AiChatRequest request) {
        ContestTurnGuard.MatchedProblem matched = new ContestTurnGuard.MatchedProblem(
                33L, ProblemVisibility.PRIVATE, ContestAiPolicyMode.DEFAULT, null, 0.97, 11L, 22L, 44L);
        return new ContestTurnGuard.GuardDecision(
                ContestTurnGuard.Decision.REFUSE,
                request,
                true,
                11L,
                22L,
                List.of(matched),
                0.97,
                false,
                null,
                false,
                null,
                ContestTurnGuard.REFUSAL_MESSAGE
        );
    }

    private static void stubSuccessfulCompletion(Fixture fixture, String content, String clientMessageId) {
        when(fixture.provider.chat(any(AiChatRequest.class), any(AiChatContext.class)))
                .thenReturn(new AiCompletion(content, "mock", "mock-model", 12, 8));
        when(fixture.contextService.prepareCompletionForTurn(eq(USER_ID), eq(fixture.conversation), any(AiChatRequest.class), any(AiCompletion.class)))
                .thenAnswer(invocation -> invocation.getArgument(3));
        when(fixture.conversationService.completeMessage(eq(USER_ID), eq(200L), eq(content), eq("mock-model")))
                .thenReturn(message(200L, "assistant", content, clientMessageId + ":assistant", "COMPLETED"));
        when(fixture.conversationService.updateAutomaticMode(eq(USER_ID), eq(CONVERSATION_ID), any(AiChatRequest.class), any(AiCompletion.class)))
                .thenReturn("qa");
        lenient().when(fixture.conversationService.getMessage(eq(USER_ID), eq(100L)))
                .thenReturn(message(100L, "user", "hello", clientMessageId, "COMPLETED"));
        lenient().when(fixture.conversationService.getMessage(eq(USER_ID), eq(200L)))
                .thenReturn(message(200L, "assistant", content, clientMessageId + ":assistant", "COMPLETED"));
    }

    private static AiChatRequest request(String clientMessageId) {
        return new AiChatRequest(CONVERSATION_ID, null, "hello", "assist", null, null, null, clientMessageId, null);
    }

    private static AiChatMessageResponse message(Long id, String role, String content, String clientMessageId, String status) {
        Instant now = Instant.now();
        return new AiChatMessageResponse(
                id,
                CONVERSATION_ID,
                null,
                clientMessageId,
                role,
                content,
                role.equals("assistant") ? "mock-model" : null,
                status,
                null,
                now,
                "RUNNING".equals(status) ? null : now
        );
    }

    private static ContestTurnGuard.GuardDecision constrainDecision(AiChatRequest request) {
        ContestTurnGuard.MatchedProblem matched = new ContestTurnGuard.MatchedProblem(
                33L, ProblemVisibility.PUBLIC, ContestAiPolicyMode.DEFAULT, null, 0.9, 11L, 22L, 44L);
        return new ContestTurnGuard.GuardDecision(
                ContestTurnGuard.Decision.CONSTRAIN,
                request,
                true,
                11L,
                22L,
                List.of(matched),
                0.9,
                false,
                null,
                false,
                "[Contest Policy]\n- 题目 #33",
                null
        );
    }

    private static final class Fixture {
        final AiProvider provider = mock(AiProvider.class);
        final AiQuotaService quotaService = mock(AiQuotaService.class);
        final AiConversationService conversationService = mock(AiConversationService.class);
        final AiContextService contextService = mock(AiContextService.class);
        final AiAfterTurnMemoryProfileEventService afterTurnEventService = mock(AiAfterTurnMemoryProfileEventService.class);
        final AiMemoryClarificationService memoryClarificationService = mock(AiMemoryClarificationService.class);
        final ContestTurnGuard contestTurnGuard = mock(ContestTurnGuard.class);
        final AiResponsePolicyGuard responsePolicyGuard = mock(AiResponsePolicyGuard.class);
        final OperationAuditWriter auditWriter = mock(OperationAuditWriter.class);
        final InMemoryAiTurnService turnService = new InMemoryAiTurnService();
        final CapturingExecutor executor;
        final AiConversationEntity conversation = new AiConversationEntity();
        final AiChatContext context = new AiChatContext("", "", "", "", "");
        final AiChatTurnService service;

        Fixture(CapturingExecutor executor) {
            this(executor, 210_000L);
        }

        Fixture(CapturingExecutor executor, long turnTimeoutMs) {
            this.executor = executor;
            conversation.setId(CONVERSATION_ID);
            conversation.setUserId(USER_ID);
            conversation.setCreatedAt(LocalDateTime.now());
            conversation.setUpdatedAt(LocalDateTime.now());
            lenient().when(provider.providerName()).thenReturn("mock-provider");
            lenient().when(provider.model()).thenReturn("mock-model");
            when(conversationService.resolveForWrite(eq(USER_ID), any(AiChatRequest.class))).thenReturn(conversation);
            when(contextService.build(eq(USER_ID), eq(conversation), any(AiChatRequest.class))).thenReturn(context);
            when(contextService.snapshot(any(AiChatContext.class))).thenReturn("{}");
            when(contestTurnGuard.evaluateAndApply(eq(USER_ID), any(AiChatRequest.class)))
                    .thenAnswer(invocation -> ContestTurnGuard.GuardDecision.pass(invocation.getArgument(1)));
            when(responsePolicyGuard.guard(eq(USER_ID), eq(CONVERSATION_ID), any(AiCompletion.class), anyBoolean()))
                    .thenAnswer(invocation -> new AiResponsePolicyGuard.GuardedCompletion(invocation.getArgument(2), false, null));
            when(conversationService.appendMessage(eq(CONVERSATION_ID), eq(USER_ID), any(), eq("user"), eq("hello"), any(), any(), eq("{}")))
                    .thenReturn(message(100L, "user", "hello", "client-1", "COMPLETED"));
            lenient().when(conversationService.appendMessage(eq(CONVERSATION_ID), eq(USER_ID), any(), eq("user"), eq("hello"), any(), any(), eq("{}"), any()))
                    .thenReturn(message(100L, "user", "hello", "client-1", "COMPLETED"));
            when(conversationService.appendMessageWithStatus(eq(CONVERSATION_ID), eq(USER_ID), any(), eq("assistant"), eq(""), any(), any(), any(), eq("RUNNING"), any()))
                    .thenReturn(message(200L, "assistant", "", "client-1:assistant", "RUNNING"));
            lenient().when(conversationService.appendMessageWithStatus(eq(CONVERSATION_ID), eq(USER_ID), any(), eq("assistant"), eq(""), any(), any(), any(), eq("RUNNING"), any(), any()))
                    .thenReturn(message(200L, "assistant", "", "client-1:assistant", "RUNNING"));
            lenient().when(conversationService.getOwnedConversation(eq(USER_ID), eq(CONVERSATION_ID))).thenReturn(conversation);
            service = new AiChatTurnService(
                    provider,
                    quotaService,
                    conversationService,
                    contextService,
                    afterTurnEventService,
                    memoryClarificationService,
                    new AiCapacityService(new com.aioj.next.ai.config.AiProperties()),
                    new AiAssistantResponseNormalizer(new ObjectMapper().findAndRegisterModules(), new ClarificationSchemaRepairer()),
                    contestTurnGuard,
                    responsePolicyGuard,
                    turnService,
                    auditWriter,
                    executor,
                    turnTimeoutMs
            );
        }
    }

    private static final class CapturingExecutor implements Executor {
        private final java.util.Queue<Runnable> captured = new java.util.concurrent.ConcurrentLinkedQueue<>();

        @Override
        public void execute(Runnable command) {
            captured.add(command);
        }

        void runCaptured() {
            Runnable next = captured.poll();
            assertThat(next).isNotNull();
            next.run();
        }
    }
}
