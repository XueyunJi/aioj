package com.aioj.next.ai.domain;

import com.aioj.next.ai.config.AiProperties;
import com.aioj.next.contract.ai.AiChatRequest;
import com.aioj.next.contract.contest.ContestAiPolicyMode;
import com.aioj.next.contract.contest.RunningContestParticipation;
import com.aioj.next.contract.contest.RunningContestProblemOccurrence;
import com.aioj.next.contract.contest.RunningContestProblemStatement;
import com.aioj.next.contract.problem.ProblemVisibility;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.ArgumentMatchers.anyDouble;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class ContestTurnGuardTest {
    private static final Long USER_ID = 1L;
    private static final String STATEMENT_TEXT = "contest problem statement text";

    @Mock
    private ProblemServiceClient problemServiceClient;
    @Mock
    private AiProvider aiProvider;
    @Mock
    private AiModelCompletionClient completionClient;
    @Mock
    private AiModelConfigResolver configResolver;
    @Mock
    private OperationAuditWriter auditWriter;

    private ContestTurnGuard guard;

    @BeforeEach
    void setUp() {
        guard = new ContestTurnGuard(problemServiceClient, aiProvider, new AiProperties(),
                completionClient, configResolver, auditWriter, new ObjectMapper());
    }

    private RunningContestParticipation participation(Instant endAt) {
        return new RunningContestParticipation(501L, 601L, Instant.now().minusSeconds(3600), endAt);
    }

    private RunningContestProblemStatement statement(long problemId, ProblemVisibility visibility, ContestAiPolicyMode mode, String notes) {
        return new RunningContestProblemStatement(
                problemId,
                STATEMENT_TEXT,
                501L,
                601L,
                701L,
                visibility,
                mode,
                notes,
                List.of(new RunningContestProblemOccurrence(501L, 601L, 701L))
        );
    }

    private AiChatRequest request(String message) {
        return new AiChatRequest("conv-1", null, message, "assist", null, null, null, "client-1", null);
    }

    private AiChatRequest requestWithCode(String message, String code) {
        return new AiChatRequest("conv-1", null, message, "assist", null,
                new AiChatRequest.CodeContext("cpp", code), null, "client-1", null);
    }

    private void mockParticipant(RunningContestProblemStatement... statements) {
        when(problemServiceClient.runningParticipations(USER_ID))
                .thenReturn(List.of(participation(Instant.now().plusSeconds(3600))));
        when(problemServiceClient.runningContestProblemStatements(USER_ID)).thenReturn(List.of(statements));
    }

    /** Embeds any input as the unit-x vector and statements as unit-x too: cosine 1.0. */
    private void mockEmbedDirectHit() {
        when(aiProvider.embed(anyString())).thenReturn(Optional.of(List.of(1.0, 0.0)));
    }

    /** Input vector [0.6, 0.8] vs statement [1, 0]: cosine 0.6, inside the gray zone. */
    private void mockEmbedGrayZone() {
        when(aiProvider.embed(anyString())).thenAnswer(invocation -> {
            String text = invocation.getArgument(0);
            return Optional.of(STATEMENT_TEXT.equals(text) ? List.of(1.0, 0.0) : List.of(0.6, 0.8));
        });
    }

    @Test
    void nonParticipantPassesWithoutAuditOrStatementFetch() {
        when(problemServiceClient.runningParticipations(USER_ID)).thenReturn(List.of());

        ContestTurnGuard.GuardDecision decision = guard.evaluateAndApply(USER_ID, request("hello"));

        assertThat(decision.decision()).isEqualTo(ContestTurnGuard.Decision.PASS);
        assertThat(decision.participant()).isFalse();
        assertThat(decision.request().message()).isEqualTo("hello");
        verify(problemServiceClient, never()).runningContestProblemStatements(any());
        verify(auditWriter, never()).record(any(), any(), any(), any(), any(), any(), any(), any(), any());
    }

    @Test
    void disabledModeProblemsAreExcludedFromDetection() {
        mockParticipant(statement(1001L, ProblemVisibility.PRIVATE, ContestAiPolicyMode.DISABLED, null));

        ContestTurnGuard.GuardDecision decision = guard.evaluateAndApply(USER_ID, request(STATEMENT_TEXT));

        assertThat(decision.decision()).isEqualTo(ContestTurnGuard.Decision.PASS);
        assertThat(decision.participant()).isTrue();
        verify(aiProvider, never()).embed(anyString());
        verifyEvaluatedAudit("PASS");
    }

    @Test
    void privateProblemDirectHitRefuses() {
        mockParticipant(statement(1001L, ProblemVisibility.PRIVATE, ContestAiPolicyMode.DEFAULT, null));
        mockEmbedDirectHit();

        ContestTurnGuard.GuardDecision decision = guard.evaluateAndApply(USER_ID, request(STATEMENT_TEXT));

        assertThat(decision.refused()).isTrue();
        assertThat(decision.refusal()).isEqualTo(ContestTurnGuard.REFUSAL_MESSAGE);
        assertThat(decision.firstMatchedProblemId()).isEqualTo(1001L);
        assertThat(decision.contestId()).isEqualTo(501L);
        assertThat(decision.contestRunId()).isEqualTo(601L);
        verify(completionClient, never()).complete(any(AiModelEffectiveConfig.class), anyList(), anyDouble(), anyInt(), anyBoolean());
        verify(auditWriter).record(eq("AI_CONTEST_LEAK_PARTICIPANT_BLOCKED"), eq("CONTEST_AI_POLICY"), eq(1001L),
                eq("BLOCKED"), any(), eq(USER_ID), eq(501L), eq(601L), eq(USER_ID));
        verifyEvaluatedAudit("REFUSE");
    }

    @Test
    void strictModePublicProblemRefuses() {
        mockParticipant(statement(1001L, ProblemVisibility.PUBLIC, ContestAiPolicyMode.STRICT, null));
        mockEmbedDirectHit();

        ContestTurnGuard.GuardDecision decision = guard.evaluateAndApply(USER_ID, request(STATEMENT_TEXT));

        assertThat(decision.refused()).isTrue();
        verifyEvaluatedAudit("REFUSE");
    }

    @Test
    void publicDefaultDirectHitConstrainsAndStripsCodeContext() {
        mockParticipant(statement(1001L, ProblemVisibility.PUBLIC, ContestAiPolicyMode.DEFAULT, "禁止给代码"));
        mockEmbedDirectHit();
        when(problemServiceClient.problemTitles(List.of(1001L)))
                .thenReturn(List.of(new com.aioj.next.contract.ai.ProblemTitleInfo(1001L, "星港建设", ProblemVisibility.PUBLIC)));
        AiChatRequest.SelectionContext selection = new AiChatRequest.SelectionContext(
                "sel-1", "conv-1", "message", "1", "user", "selected", "selected-md", null, null,
                new AiChatRequest.SelectedCodeContext("cpp", "main", "main", "2", "hash", false), null, "ask");

        AiChatRequest request = new AiChatRequest("conv-1", null, STATEMENT_TEXT, "assist", null,
                new AiChatRequest.CodeContext("cpp", "int main() {}"), null, "client-1", selection, null, null);
        ContestTurnGuard.GuardDecision decision = guard.evaluateAndApply(USER_ID, request);

        assertThat(decision.constrained()).isTrue();
        assertThat(decision.request().codeContext()).isNull();
        assertThat(decision.request().selectionContext().codeContext()).isNull();
        assertThat(decision.request().selectionContext().selectedText()).isEqualTo("selected");
        assertThat(decision.policyBlock()).contains("[Contest Policy]", "#1001", "星港建设", "禁止给代码");
        verify(completionClient, never()).complete(any(AiModelEffectiveConfig.class), anyList(), anyDouble(), anyInt(), anyBoolean());
        verifyEvaluatedAudit("CONSTRAIN");
    }

    @Test
    void grayZoneJudgeRelatedConstrains() {
        mockParticipant(statement(1001L, ProblemVisibility.PUBLIC, ContestAiPolicyMode.DEFAULT, null));
        mockEmbedGrayZone();
        when(configResolver.effectiveConfig(AiModelScope.TEXT_GENERATION)).thenReturn(judgeConfig());
        when(completionClient.complete(any(AiModelEffectiveConfig.class), anyList(), anyDouble(), anyInt(), anyBoolean()))
                .thenReturn(new AiModelCompletionClient.CompletionResult(
                        "{\"related\":true,\"reason\":\"asks about the problem\"}", "dashscope", "qwen", 10, 5));
        when(problemServiceClient.problemTitles(List.of(1001L))).thenReturn(List.of());

        ContestTurnGuard.GuardDecision decision = guard.evaluateAndApply(USER_ID, request("gray zone question"));

        assertThat(decision.constrained()).isTrue();
        assertThat(decision.judgeUsed()).isTrue();
        assertThat(decision.judgeError()).isNull();
        assertThat(decision.maxSimilarity()).isCloseTo(0.6, org.assertj.core.data.Offset.offset(0.001));
        verifyEvaluatedAudit("CONSTRAIN");
    }

    @Test
    void grayZoneJudgeUnrelatedPasses() {
        mockParticipant(statement(1001L, ProblemVisibility.PUBLIC, ContestAiPolicyMode.DEFAULT, null));
        mockEmbedGrayZone();
        when(configResolver.effectiveConfig(AiModelScope.TEXT_GENERATION)).thenReturn(judgeConfig());
        when(completionClient.complete(any(AiModelEffectiveConfig.class), anyList(), anyDouble(), anyInt(), anyBoolean()))
                .thenReturn(new AiModelCompletionClient.CompletionResult(
                        "{\"related\":false,\"reason\":\"generic sorting question\"}", "dashscope", "qwen", 10, 5));

        ContestTurnGuard.GuardDecision decision = guard.evaluateAndApply(USER_ID, request("gray zone question"));

        assertThat(decision.decision()).isEqualTo(ContestTurnGuard.Decision.PASS);
        assertThat(decision.judgeUsed()).isTrue();
        assertThat(decision.matchedProblems()).isEmpty();
        verifyEvaluatedAudit("PASS");
    }

    @Test
    void judgeCallFailureConservativelyMatchesWithJudgeError() {
        mockParticipant(statement(1001L, ProblemVisibility.PUBLIC, ContestAiPolicyMode.DEFAULT, null));
        mockEmbedGrayZone();
        when(configResolver.effectiveConfig(AiModelScope.TEXT_GENERATION)).thenReturn(judgeConfig());
        when(completionClient.complete(any(AiModelEffectiveConfig.class), anyList(), anyDouble(), anyInt(), anyBoolean()))
                .thenThrow(new IllegalStateException("upstream closed"));
        when(problemServiceClient.problemTitles(List.of(1001L))).thenReturn(List.of());

        ContestTurnGuard.GuardDecision decision = guard.evaluateAndApply(USER_ID, request("gray zone question"));

        assertThat(decision.constrained()).isTrue();
        assertThat(decision.judgeUsed()).isTrue();
        assertThat(decision.judgeError()).isEqualTo("JUDGE_CALL_FAILED");
        verifyEvaluatedAudit("CONSTRAIN");
    }

    @Test
    void unparsableJudgeOutputConservativelyMatchesWithJudgeError() {
        mockParticipant(statement(1001L, ProblemVisibility.PUBLIC, ContestAiPolicyMode.DEFAULT, null));
        mockEmbedGrayZone();
        when(configResolver.effectiveConfig(AiModelScope.TEXT_GENERATION)).thenReturn(judgeConfig());
        when(completionClient.complete(any(AiModelEffectiveConfig.class), anyList(), anyDouble(), anyInt(), anyBoolean()))
                .thenReturn(new AiModelCompletionClient.CompletionResult("not json", "dashscope", "qwen", 10, 5));
        when(problemServiceClient.problemTitles(List.of(1001L))).thenReturn(List.of());

        ContestTurnGuard.GuardDecision decision = guard.evaluateAndApply(USER_ID, request("gray zone question"));

        assertThat(decision.constrained()).isTrue();
        assertThat(decision.judgeError()).isEqualTo("JUDGE_CALL_FAILED");
    }

    @Test
    void embeddingUnavailableWritesDegradedAuditAndPasses() {
        mockParticipant(statement(1001L, ProblemVisibility.PRIVATE, ContestAiPolicyMode.DEFAULT, null));
        when(aiProvider.embed(anyString())).thenReturn(Optional.empty());

        ContestTurnGuard.GuardDecision decision = guard.evaluateAndApply(USER_ID, request(STATEMENT_TEXT));

        assertThat(decision.decision()).isEqualTo(ContestTurnGuard.Decision.PASS);
        verify(auditWriter).record(eq("AI_CONTEST_GUARD_DEGRADED"), eq("CONTEST_AI_POLICY"), isNull(),
                eq("DEGRADED"), any(), eq(USER_ID), eq(501L), eq(601L), eq(USER_ID));
        verifyEvaluatedAudit("PASS");
    }

    @Test
    void graceWindowParticipationMarksInGrace() {
        when(problemServiceClient.runningParticipations(USER_ID))
                .thenReturn(List.of(participation(Instant.now().minusSeconds(10))));
        when(problemServiceClient.runningContestProblemStatements(USER_ID))
                .thenReturn(List.of(statement(1001L, ProblemVisibility.PUBLIC, ContestAiPolicyMode.DEFAULT, null)));
        // Orthogonal vectors: cosine 0.0, below the recall threshold.
        when(aiProvider.embed(anyString())).thenAnswer(invocation -> {
            String text = invocation.getArgument(0);
            return Optional.of(STATEMENT_TEXT.equals(text) ? List.of(1.0, 0.0) : List.of(0.0, 1.0));
        });

        ContestTurnGuard.GuardDecision decision = guard.evaluateAndApply(USER_ID, request("unrelated question"));

        assertThat(decision.decision()).isEqualTo(ContestTurnGuard.Decision.PASS);
        assertThat(decision.inGrace()).isTrue();
        @SuppressWarnings("unchecked")
        ArgumentCaptor<Map<String, Object>> summaryCaptor = ArgumentCaptor.forClass(Map.class);
        verify(auditWriter).record(eq("AI_CONTEST_GUARD_EVALUATED"), eq("CONTEST_AI_POLICY"), isNull(),
                eq("PASS"), summaryCaptor.capture(), eq(USER_ID), eq(501L), eq(601L), eq(USER_ID));
        assertThat(summaryCaptor.getValue()).containsEntry("inGrace", true);
    }

    @Test
    void overlappingWindowsAttributeToInProgressRunNotGraceTail() {
        Instant now = Instant.now();
        RunningContestParticipation graceTail = new RunningContestParticipation(501L, 601L, now.minusSeconds(3600), now.minusSeconds(10));
        RunningContestParticipation inProgress = new RunningContestParticipation(502L, 602L, now.minusSeconds(600), now.plusSeconds(3600));
        when(problemServiceClient.runningParticipations(USER_ID))
                .thenReturn(List.of(graceTail, inProgress));
        when(problemServiceClient.runningContestProblemStatements(USER_ID))
                .thenReturn(List.of(statement(1001L, ProblemVisibility.PUBLIC, ContestAiPolicyMode.DEFAULT, null)));
        // Orthogonal vectors: cosine 0.0, below the recall threshold.
        when(aiProvider.embed(anyString())).thenAnswer(invocation -> {
            String text = invocation.getArgument(0);
            return Optional.of(STATEMENT_TEXT.equals(text) ? List.of(1.0, 0.0) : List.of(0.0, 1.0));
        });

        ContestTurnGuard.GuardDecision decision = guard.evaluateAndApply(USER_ID, request("unrelated question"));

        assertThat(decision.decision()).isEqualTo(ContestTurnGuard.Decision.PASS);
        assertThat(decision.contestId()).isEqualTo(502L);
        assertThat(decision.contestRunId()).isEqualTo(602L);
        assertThat(decision.inGrace()).isFalse();
        @SuppressWarnings("unchecked")
        ArgumentCaptor<Map<String, Object>> summaryCaptor = ArgumentCaptor.forClass(Map.class);
        verify(auditWriter).record(eq("AI_CONTEST_GUARD_EVALUATED"), eq("CONTEST_AI_POLICY"), isNull(),
                eq("PASS"), summaryCaptor.capture(), eq(USER_ID), eq(502L), eq(602L), eq(USER_ID));
        assertThat(summaryCaptor.getValue()).containsEntry("inGrace", false);
    }

    @Test
    void requestContestContextIsKeptAdvisoryOnly() {
        mockParticipant(statement(1001L, ProblemVisibility.PUBLIC, ContestAiPolicyMode.DEFAULT, null));
        mockEmbedDirectHit();
        lenient().when(problemServiceClient.problemTitles(List.of(1001L))).thenReturn(List.of());
        AiChatRequest request = new AiChatRequest("conv-1", null, STATEMENT_TEXT, "assist", null, null, null,
                "client-1", null, new AiChatRequest.ContestContext(501L, 601L, 701L), null);

        ContestTurnGuard.GuardDecision decision = guard.evaluateAndApply(USER_ID, request);

        assertThat(decision.constrained()).isTrue();
        assertThat(decision.request().contestContext()).isNotNull();
        assertThat(decision.request().contestContext().contestId()).isEqualTo(501L);
    }

    private void verifyEvaluatedAudit(String status) {
        @SuppressWarnings("unchecked")
        ArgumentCaptor<Map<String, Object>> summaryCaptor = ArgumentCaptor.forClass(Map.class);
        verify(auditWriter).record(eq("AI_CONTEST_GUARD_EVALUATED"), eq("CONTEST_AI_POLICY"), any(),
                eq(status), summaryCaptor.capture(), eq(USER_ID), any(), any(), eq(USER_ID));
        assertThat(summaryCaptor.getValue()).containsKeys("decision", "matchedProblemIds", "maxSimilarity", "judgeUsed", "inGrace");
    }

    private AiModelEffectiveConfig judgeConfig() {
        return new AiModelEffectiveConfig(
                AiModelScope.TEXT_GENERATION,
                true,
                false,
                "CONFIG",
                "dashscope",
                "https://example.invalid/chat/completions",
                "sk-test",
                "sk-***",
                "CONFIG",
                null,
                "qwen-plus",
                false,
                false,
                null,
                null,
                null,
                null,
                null,
                null
        );
    }
}
