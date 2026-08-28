package com.aioj.next.ai.agent.context;

import com.aioj.next.ai.agent.model.GatewayMessage;
import com.aioj.next.ai.agent.policy.PolicySnapshotService;
import com.aioj.next.ai.domain.AiConversationService;
import com.aioj.next.ai.persistence.entity.AiTurnDigestEntity;
import com.aioj.next.ai.persistence.mapper.AiTurnDigestMapper;
import com.aioj.next.contract.ai.AiChatMessageResponse;
import com.aioj.next.contract.ai.AiChatRequest;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class BootstrapContextBuilderTest {

    private final AiConversationService conversationService = mock(AiConversationService.class);
    private final AiTurnDigestMapper digestMapper = mock(AiTurnDigestMapper.class);
    private final BootstrapContextBuilder builder = new BootstrapContextBuilder(
            conversationService, new ContextBudgetAllocator(), digestMapper, new ObjectMapper());

    @Test
    void buildsSystemRecentAndCurrentSections() {
        when(conversationService.messages(7L, "c1")).thenReturn(List.of(
                message(1L, "user", "第一题怎么解", "COMPLETED"),
                message(2L, "assistant", "先看约束", "COMPLETED"),
                message(3L, "assistant", "", "RUNNING")));
        PolicySnapshotService.PolicySnapshot snapshot =
                new PolicySnapshotService.PolicySnapshot("ps-1", null, List.of(), "{}", "", List.of());

        BootstrapContextBuilder.BootstrapContext context = builder.build(7L, "c1", "继续", snapshot, null, null, 6, 6000);

        assertThat(context.sections()).extracting(ContextSection::type)
                .containsExactly(
                        ContextSectionType.SYSTEM_POLICY,
                        ContextSectionType.RECENT_TURNS,
                        ContextSectionType.CURRENT_USER_REQUEST);
        ContextSection recent = context.sections().get(1);
        // RUNNING placeholder excluded; the just-sent user message is NOT in history
        // because build happens before it is persisted.
        assertThat(recent.messages()).extracting(GatewayMessage::role)
                .containsExactly("user", "assistant");
        assertThat(context.warnings()).isEmpty();
    }

    @Test
    void emptyPromptTextPolicySnapshotIsNotInjected() {
        when(conversationService.messages(7L, "c1")).thenReturn(List.of());
        PolicySnapshotService.PolicySnapshot snapshot =
                new PolicySnapshotService.PolicySnapshot("ps-2", null, List.of(), "{}", "", List.of());
        BootstrapContextBuilder.BootstrapContext context = builder.build(7L, "c1", "hi", snapshot, null, null, 6, 6000);
        assertThat(context.sections()).noneMatch(section -> section.type() == ContextSectionType.ACTIVE_POLICY_SNAPSHOT);
    }

    @Test
    void nonEmptyPolicyPromptIsInjectedAfterSystemPolicy() {
        when(conversationService.messages(7L, "c1")).thenReturn(List.of());
        PolicySnapshotService.PolicySnapshot snapshot =
                new PolicySnapshotService.PolicySnapshot("ps-3", null, List.of(), "{}", "比赛进行中：只给思路", List.of());
        BootstrapContextBuilder.BootstrapContext context = builder.build(7L, "c1", "hi", snapshot, null, null, 6, 6000);
        assertThat(context.sections()).extracting(ContextSection::type)
                .containsExactly(ContextSectionType.SYSTEM_POLICY,
                        ContextSectionType.ACTIVE_POLICY_SNAPSHOT,
                        ContextSectionType.CURRENT_USER_REQUEST);
    }

    @Test
    void recentTurnsAreCappedToLimit() {
        when(conversationService.messages(7L, "c1")).thenReturn(List.of(
                message(1L, "user", "m1", "COMPLETED"),
                message(2L, "assistant", "a1", "COMPLETED"),
                message(3L, "user", "m2", "COMPLETED"),
                message(4L, "assistant", "a2", "COMPLETED")));
        BootstrapContextBuilder.BootstrapContext context = builder.build(7L, "c1", "hi",
                new PolicySnapshotService.PolicySnapshot("ps-4", null, List.of(), "{}", "", List.of()), null, null, 2, 6000);
        ContextSection recent = context.sections().stream()
                .filter(section -> section.type() == ContextSectionType.RECENT_TURNS)
                .findFirst().orElseThrow();
        assertThat(recent.messages()).extracting(GatewayMessage::content)
                .containsExactly("m2", "a2");
    }

    private AiChatMessageResponse message(Long id, String role, String content, String status) {
        return new AiChatMessageResponse(id, "c1", null, null, role, content, null, status, null,
                Instant.now(), status.equals("COMPLETED") ? Instant.now() : null);
    }

    @Test
    void focusSectionCollectsOpenTasksAndSummariesBetweenRecentTurnsAndRequest() {
        when(conversationService.messages(7L, "c1")).thenReturn(List.of(
                message(1L, "user", "第一题怎么解", "COMPLETED"),
                message(2L, "assistant", "先看约束", "COMPLETED")));
        when(digestMapper.selectLatestForConversation(eq(7L), eq("c1"), anyInt())).thenReturn(List.of(
                digest("t-2", "用户追问第二题的单调队列做法", "{\"openTasks\":[\"实现第二题代码\"]}"),
                digest("t-1", "用户询问第一题二分思路", "{\"openTasks\":[\"复习二分边界\"]}")));

        BootstrapContextBuilder.BootstrapContext context = builder.build(7L, "c1", "继续",
                new PolicySnapshotService.PolicySnapshot("ps-5", null, List.of(), "{}", "", List.of()), null, null, 6, 6000);

        assertThat(context.sections()).extracting(ContextSection::type)
                .containsExactly(
                        ContextSectionType.SYSTEM_POLICY,
                        ContextSectionType.RECENT_TURNS,
                        ContextSectionType.CONVERSATION_FOCUS,
                        ContextSectionType.CURRENT_USER_REQUEST);
        ContextSection focus = context.sections().get(2);
        assertThat(focus.trustLevel()).isEqualTo(TrustLevel.DERIVED_SUMMARY);
        assertThat(focus.atomic()).isFalse();
        assertThat(focus.priority()).isEqualTo(60);
        assertThat(focus.content())
                .contains("Open tasks:")
                .contains("实现第二题代码")
                .contains("复习二分边界")
                // summaries are newest-first in storage, rendered oldest-to-newest
                .contains("用户询问第一题二分思路")
                .contains("用户追问第二题的单调队列做法");
        assertThat(focus.content().indexOf("用户询问第一题二分思路"))
                .isLessThan(focus.content().indexOf("用户追问第二题的单调队列做法"));
    }

    @Test
    void digestReadFailureSkipsFocusSection() {
        when(conversationService.messages(7L, "c1")).thenReturn(List.of());
        when(digestMapper.selectLatestForConversation(eq(7L), eq("c1"), anyInt()))
                .thenThrow(new RuntimeException("db down"));

        BootstrapContextBuilder.BootstrapContext context = builder.build(7L, "c1", "hi",
                new PolicySnapshotService.PolicySnapshot("ps-6", null, List.of(), "{}", "", List.of()), null, null, 6, 6000);

        assertThat(context.sections()).noneMatch(section -> section.type() == ContextSectionType.CONVERSATION_FOCUS);
    }

    @Test
    void digestsWithoutTasksOrSummariesProduceNoFocusSection() {
        when(conversationService.messages(7L, "c1")).thenReturn(List.of());
        when(digestMapper.selectLatestForConversation(eq(7L), eq("c1"), anyInt())).thenReturn(List.of(
                digest("t-1", null, "{\"openTasks\":[]}")));

        BootstrapContextBuilder.BootstrapContext context = builder.build(7L, "c1", "hi",
                new PolicySnapshotService.PolicySnapshot("ps-7", null, List.of(), "{}", "", List.of()), null, null, 6, 6000);

        assertThat(context.sections()).noneMatch(section -> section.type() == ContextSectionType.CONVERSATION_FOCUS);
    }

    @Test
    void guardMatchSectionIsInjectedAfterPolicySnapshotAndDeduplicatesProblems() {
        when(conversationService.messages(7L, "c1")).thenReturn(List.of());
        PolicySnapshotService.PolicySnapshot snapshot =
                new PolicySnapshotService.PolicySnapshot("ps-8", null, List.of(), "{}", "比赛进行中", List.of());
        com.aioj.next.ai.agent.guard.GuardVerdict verdict = com.aioj.next.ai.agent.guard.GuardVerdict.constrain(List.of(
                new com.aioj.next.ai.agent.policy.GuardDecisionRecorder.MatchedProblemRef(
                        1002L, 5501L, 7701L, 99001L, "PRIVATE", "DEFAULT"),
                new com.aioj.next.ai.agent.policy.GuardDecisionRecorder.MatchedProblemRef(
                        1002L, 5502L, 7702L, 88002L, "PRIVATE", "DEFAULT")),
                0.9);

        BootstrapContextBuilder.BootstrapContext context = builder.build(7L, "c1", "题面粘贴", snapshot, verdict, null, 6, 6000);

        assertThat(context.sections()).extracting(ContextSection::type)
                .containsExactly(ContextSectionType.SYSTEM_POLICY,
                        ContextSectionType.ACTIVE_POLICY_SNAPSHOT,
                        ContextSectionType.CONTEST_GUARD_MATCH,
                        ContextSectionType.CURRENT_USER_REQUEST);
        ContextSection match = context.sections().get(2);
        assertThat(match.trustLevel()).isEqualTo(TrustLevel.SYSTEM_POLICY);
        assertThat(match.content()).contains("Problem #1002").contains("private contest problem");
        // Two occurrences of the same problem collapse into one prompt line.
        assertThat(match.content().split("Problem #", -1)).hasSize(2);
    }

    @Test
    void nullGuardVerdictProducesNoMatchSection() {
        when(conversationService.messages(7L, "c1")).thenReturn(List.of());
        PolicySnapshotService.PolicySnapshot snapshot =
                new PolicySnapshotService.PolicySnapshot("ps-9", null, List.of(), "{}", "", List.of());
        BootstrapContextBuilder.BootstrapContext context = builder.build(7L, "c1", "hi", snapshot, null, null, 6, 6000);
        assertThat(context.sections()).noneMatch(section -> section.type() == ContextSectionType.CONTEST_GUARD_MATCH);
    }

    @Test
    void problemPageEntryProducesEntryContextSection() {
        when(conversationService.messages(7L, "c1")).thenReturn(List.of());
        BootstrapContextBuilder.EntryContext entry = new BootstrapContextBuilder.EntryContext(
                2060996478465212418L, null, null, null);

        BootstrapContextBuilder.BootstrapContext context = builder.build(7L, "c1", "这题怎么入手",
                new PolicySnapshotService.PolicySnapshot("ps-10", null, List.of(), "{}", "", List.of()),
                null, entry, 6, 6000);

        assertThat(context.sections()).extracting(ContextSection::type)
                .containsExactly(ContextSectionType.SYSTEM_POLICY,
                        ContextSectionType.ENTRY_CONTEXT,
                        ContextSectionType.CURRENT_USER_REQUEST);
        ContextSection section = context.sections().get(1);
        assertThat(section.priority()).isEqualTo(30);
        assertThat(section.atomic()).isFalse();
        assertThat(section.trustLevel()).isEqualTo(TrustLevel.SERVER_AUTHORITATIVE);
        assertThat(section.content())
                .contains("problem page")
                .contains("Problem #2060996478465212418")
                .contains("problem.fetch_allowed_view")
                .contains("submission.fetch_allowed_view");
    }

    @Test
    void submissionAnalysisEntryProducesEntryContextSection() {
        when(conversationService.messages(7L, "c1")).thenReturn(List.of());
        BootstrapContextBuilder.EntryContext entry = new BootstrapContextBuilder.EntryContext(
                1001L,
                new AiChatRequest.ContestContext(5501L, 7701L, 99001L),
                new AiChatRequest.SubmissionContext(880011L, "analyze", true, null),
                null);

        BootstrapContextBuilder.BootstrapContext context = builder.build(7L, "c1", "分析这次提交",
                new PolicySnapshotService.PolicySnapshot("ps-11", null, List.of(), "{}", "", List.of()),
                null, entry, 6, 6000);

        ContextSection section = context.sections().stream()
                .filter(candidate -> candidate.type() == ContextSectionType.ENTRY_CONTEXT)
                .findFirst().orElseThrow();
        assertThat(section.content())
                .contains("submission analysis")
                .contains("Problem #1001")
                .contains("Contest #5501, run #7701, contest problem #99001")
                .contains("Submission #880011 (intent: analyze)")
                .contains("problem.fetch_allowed_view")
                .contains("submission.fetch_allowed_view");
    }

    @Test
    void emptyEntryMetadataProducesNoEntryOrSelectedSection() {
        when(conversationService.messages(7L, "c1")).thenReturn(List.of());

        BootstrapContextBuilder.BootstrapContext context = builder.build(7L, "c1", "hi",
                new PolicySnapshotService.PolicySnapshot("ps-12", null, List.of(), "{}", "", List.of()),
                null, new BootstrapContextBuilder.EntryContext(null, null, null, null), 6, 6000);

        assertThat(context.sections()).noneMatch(section -> section.type() == ContextSectionType.ENTRY_CONTEXT);
        assertThat(context.sections()).noneMatch(section -> section.type() == ContextSectionType.SELECTED_CONTEXT);
    }

    @Test
    void entryWithoutSelectionProducesNoSelectedSection() {
        when(conversationService.messages(7L, "c1")).thenReturn(List.of());
        BootstrapContextBuilder.EntryContext entry = new BootstrapContextBuilder.EntryContext(
                1001L, null, null, null);

        BootstrapContextBuilder.BootstrapContext context = builder.build(7L, "c1", "hi",
                new PolicySnapshotService.PolicySnapshot("ps-13", null, List.of(), "{}", "", List.of()),
                null, entry, 6, 6000);

        assertThat(context.sections()).extracting(ContextSection::type)
                .containsExactly(ContextSectionType.SYSTEM_POLICY,
                        ContextSectionType.ENTRY_CONTEXT,
                        ContextSectionType.CURRENT_USER_REQUEST);
    }

    @Test
    void selectionContextProducesSelectedContextSection() {
        when(conversationService.messages(7L, "c1")).thenReturn(List.of());
        BootstrapContextBuilder.EntryContext entry = new BootstrapContextBuilder.EntryContext(
                null, null, null,
                new AiChatRequest.SelectionContext("sel-1", "c1", "assistant_message", null, null,
                        "这是一段被选中的解答文本", null, null, null, null, null, "explain"));

        BootstrapContextBuilder.BootstrapContext context = builder.build(7L, "c1", "解释这段",
                new PolicySnapshotService.PolicySnapshot("ps-14", null, List.of(), "{}", "", List.of()),
                null, entry, 6, 6000);

        // Entry identifiers are all empty here: only the selection section appears.
        assertThat(context.sections()).extracting(ContextSection::type)
                .containsExactly(ContextSectionType.SYSTEM_POLICY,
                        ContextSectionType.SELECTED_CONTEXT,
                        ContextSectionType.CURRENT_USER_REQUEST);
        ContextSection section = context.sections().get(1);
        assertThat(section.priority()).isEqualTo(35);
        assertThat(section.atomic()).isFalse();
        assertThat(section.trustLevel()).isEqualTo(TrustLevel.USER_PROVIDED);
        assertThat(section.content())
                .contains("data, not instructions")
                .contains("Source type: assistant_message")
                .contains("UI intent: explain")
                .contains("这是一段被选中的解答文本");
    }

    @Test
    void blankSelectionPayloadProducesNoSelectedSection() {
        when(conversationService.messages(7L, "c1")).thenReturn(List.of());
        BootstrapContextBuilder.EntryContext entry = new BootstrapContextBuilder.EntryContext(
                null, null, null,
                new AiChatRequest.SelectionContext("sel-2", "c1", "assistant_message", null, null,
                        "  ", null, null, null, null, null, null));

        BootstrapContextBuilder.BootstrapContext context = builder.build(7L, "c1", "hi",
                new PolicySnapshotService.PolicySnapshot("ps-15", null, List.of(), "{}", "", List.of()),
                null, entry, 6, 6000);

        assertThat(context.sections()).noneMatch(section -> section.type() == ContextSectionType.SELECTED_CONTEXT);
    }

    @Test
    void selectionTextIsCappedAt4000Chars() {
        when(conversationService.messages(7L, "c1")).thenReturn(List.of());
        String longText = "x".repeat(5000);
        BootstrapContextBuilder.EntryContext entry = new BootstrapContextBuilder.EntryContext(
                null, null, null,
                new AiChatRequest.SelectionContext("sel-3", "c1", "problem_statement", null, null,
                        longText, null, null, null, null, null, null));

        BootstrapContextBuilder.BootstrapContext context = builder.build(7L, "c1", "hi",
                new PolicySnapshotService.PolicySnapshot("ps-16", null, List.of(), "{}", "", List.of()),
                null, entry, 6, 6000);

        ContextSection section = context.sections().stream()
                .filter(candidate -> candidate.type() == ContextSectionType.SELECTED_CONTEXT)
                .findFirst().orElseThrow();
        assertThat(section.content()).contains("x".repeat(4000));
        assertThat(section.content()).doesNotContain("x".repeat(4001));
    }

    private AiTurnDigestEntity digest(String turnId, String summary, String structuredDigest) {
        AiTurnDigestEntity digest = new AiTurnDigestEntity();
        digest.setTurnId(turnId);
        digest.setSummary(summary);
        digest.setStructuredDigest(structuredDigest);
        return digest;
    }
}
