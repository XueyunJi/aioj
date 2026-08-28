package com.aioj.next.ai.domain.context;

import com.aioj.next.ai.persistence.entity.AiConversationProblemEntity;
import com.aioj.next.contract.ai.AiChatRequest;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * W1.7 pure resolver rules: ordinal/batch/latest patterns, priority order, ambiguity and
 * legacy NULL-ordinal compatibility.
 */
class ReferenceResolverTest {
    private final ReferenceResolver resolver = new ReferenceResolver();

    @Test
    void singleSetSecondProblemResolvesDirectly() {
        List<AiConversationProblemEntity> rows = List.of(
                row(1L, 101L, "两数之和", "m1", 1, 1),
                row(2L, 102L, "星港建设", "m1", 2, 2)
        );

        ReferenceResolver.Outcome outcome = resolve("讲一下第2题", rows, "m1", null);

        assertThat(outcome.resolutions()).hasSize(1);
        ReferenceResolver.Resolution resolution = outcome.resolutions().get(0);
        assertThat(resolution.resolved()).isTrue();
        assertThat(resolution.resolver()).isEqualTo("active_set_ordinal");
        assertThat(resolution.resolvedProblemId()).isEqualTo(102L);
        assertThat(resolution.setId()).isEqualTo("m1");
        assertThat(resolution.setOrdinal()).isEqualTo(2);
        assertThat(resolution.conversationOrdinal()).isEqualTo(2);
        assertThat(resolution.clarificationIssued()).isFalse();
    }

    @Test
    void whitespaceBetweenTokensStillMatches() {
        List<AiConversationProblemEntity> rows = List.of(
                row(1L, 101L, "第一批第一题", "m1", 1, 1),
                row(2L, 102L, "第一批第二题", "m1", 2, 2),
                row(3L, 103L, "第二批第一题", "m2", 1, 3),
                row(4L, 104L, "第二批第二题", "m2", 2, 4)
        );

        ReferenceResolver.Outcome plain = resolve("请给我讲讲第 1 题", rows, null, null);
        assertThat(plain.resolutions()).hasSize(1);
        assertThat(plain.resolutions().get(0).resolved()).isTrue();
        assertThat(plain.resolutions().get(0).resolver()).isEqualTo("conversation_ordinal");
        assertThat(plain.resolutions().get(0).resolvedProblemId()).isEqualTo(101L);

        ReferenceResolver.Outcome qualified = resolve("上一批的第 2 题怎么做", rows, "m2", "m1");
        assertThat(qualified.resolutions()).hasSize(1);
        assertThat(qualified.resolutions().get(0).resolver()).isEqualTo("last_set_ordinal");
        assertThat(qualified.resolutions().get(0).resolvedProblemId()).isEqualTo(102L);

        ReferenceResolver.Outcome chineseNumeral = resolve("第 二 道题我还不会", rows, "m1", null);
        assertThat(chineseNumeral.resolutions()).hasSize(1);
        assertThat(chineseNumeral.resolutions().get(0).resolvedProblemId()).isEqualTo(102L);

        ReferenceResolver.Outcome bare = resolve("第 2 个怎么做", rows, "m1", null);
        assertThat(bare.resolutions()).hasSize(1);
        assertThat(bare.resolutions().get(0).resolvedProblemId()).isEqualTo(102L);

        // Guard rails still apply: spaced non-problem usages must not match.
        assertThat(resolve("第 2 个测试点过不了", rows, "m1", null).resolutions()).isEmpty();
        assertThat(resolve("第 2 题库里还有很多", rows, "m1", null).resolutions()).isEmpty();
    }

    @Test
    void batchQualifiersDistinguishTwoSets() {
        List<AiConversationProblemEntity> rows = List.of(
                row(1L, 101L, "第一批第一题", "m1", 1, 1),
                row(2L, 102L, "第一批第二题", "m1", 2, 2),
                row(3L, 103L, "第二批第一题", "m2", 1, 3),
                row(4L, 104L, "第二批第二题", "m2", 2, 4)
        );

        ReferenceResolver.Outcome previous = resolve("上一批的第2题怎么做", rows, "m2", "m1");
        ReferenceResolver.Outcome current = resolve("讲一下这一批第2题", rows, "m2", "m1");

        assertThat(previous.resolutions()).hasSize(1);
        assertThat(previous.resolutions().get(0).resolver()).isEqualTo("last_set_ordinal");
        assertThat(previous.resolutions().get(0).resolvedProblemId()).isEqualTo(102L);
        assertThat(current.resolutions()).hasSize(1);
        assertThat(current.resolutions().get(0).resolver()).isEqualTo("active_set_ordinal");
        assertThat(current.resolutions().get(0).resolvedProblemId()).isEqualTo(104L);
    }

    @Test
    void unqualifiedOrdinalAcrossTwoSetsIssuesClarificationInsteadOfBinding() {
        List<AiConversationProblemEntity> rows = List.of(
                row(1L, 101L, "第一批第一题", "m1", 1, 1),
                row(2L, 102L, "第一批第二题", "m1", 2, 2),
                row(3L, 103L, "第二批第一题", "m2", 1, 3),
                row(4L, 104L, "第二批第二题", "m2", 2, 4)
        );

        ReferenceResolver.Outcome outcome = resolve("第2题的思路是什么", rows, "m2", "m1");

        assertThat(outcome.resolutions()).hasSize(1);
        ReferenceResolver.Resolution resolution = outcome.resolutions().get(0);
        assertThat(resolution.resolved()).isFalse();
        assertThat(resolution.clarificationIssued()).isTrue();
        assertThat(resolution.alternatives()).hasSize(2);
        assertThat(resolution.alternatives()).extracting(ReferenceResolver.Candidate::problemId)
                .containsExactlyInAnyOrder(102L, 104L);
        assertThat(outcome.firstAmbiguous()).isSameAs(resolution);
    }

    @Test
    void lastProblemResolvesLatestConversationOrdinal() {
        List<AiConversationProblemEntity> rows = List.of(
                row(1L, 101L, "旧题", "m1", 1, 1),
                row(2L, 102L, "中间题", "m1", 2, 2),
                row(3L, 103L, "最新题", "m2", 1, 3)
        );

        assertThat(resolve("最后一题再讲一遍", rows, "m2", "m1").resolutions().get(0).resolvedProblemId()).isEqualTo(103L);
        assertThat(resolve("最新那道题呢", rows, "m2", "m1").resolutions().get(0).resolvedProblemId()).isEqualTo(103L);
        assertThat(resolve("最后一题再讲一遍", rows, "m2", "m1").resolutions().get(0).resolver()).isEqualTo("latest_problem");
    }

    @Test
    void chineseNumeralOrdinalResolves() {
        List<AiConversationProblemEntity> rows = List.of(
                row(1L, 101L, "题一", "m1", 1, 1),
                row(2L, 102L, "题二", "m1", 2, 2),
                row(3L, 103L, "题三", "m1", 3, 3)
        );

        ReferenceResolver.Outcome outcome = resolve("第三题我还不会", rows, "m1", null);

        assertThat(outcome.resolutions()).hasSize(1);
        assertThat(outcome.resolutions().get(0).resolvedProblemId()).isEqualTo(103L);
    }

    @Test
    void globalConversationOrdinalUsedWhenNoSetKnown() {
        List<AiConversationProblemEntity> rows = List.of(
                row(1L, 101L, "题一", "m1", 1, 1),
                row(2L, 102L, "题二", "m1", 2, 2)
        );

        ReferenceResolver.Outcome outcome = resolve("第2题", rows, null, null);

        assertThat(outcome.resolutions()).hasSize(1);
        assertThat(outcome.resolutions().get(0).resolver()).isEqualTo("conversation_ordinal");
        assertThat(outcome.resolutions().get(0).resolvedProblemId()).isEqualTo(102L);
    }

    @Test
    void noMatchingPatternReturnsEmpty() {
        List<AiConversationProblemEntity> rows = List.of(row(1L, 101L, "两数之和", "m1", 1, 1));

        assertThat(resolve("这道题怎么做", rows, "m1", null).resolutions()).isEmpty();
        assertThat(resolve("", rows, "m1", null).resolutions()).isEmpty();
        assertThat(resolver.resolve(null).resolutions()).isEmpty();
    }

    @Test
    void bareOrdinalDoesNotMatchTestPoints() {
        List<AiConversationProblemEntity> rows = List.of(
                row(1L, 101L, "题一", "m1", 1, 1),
                row(2L, 102L, "题二", "m1", 2, 2)
        );

        assertThat(resolve("第2个测试点过不了", rows, "m1", null).resolutions()).isEmpty();
        ReferenceResolver.Outcome bare = resolve("第2个怎么做", rows, "m1", null);
        assertThat(bare.resolutions()).hasSize(1);
        assertThat(bare.resolutions().get(0).resolvedProblemId()).isEqualTo(102L);
    }

    @Test
    void legacyNullOrdinalRowsAreNeverResolved() {
        List<AiConversationProblemEntity> legacy = List.of(
                row(1L, 101L, "题一", null, null, null),
                row(2L, 102L, "题二", null, null, null)
        );

        ReferenceResolver.Outcome outcome = resolve("讲一下第2题", legacy, null, null);

        assertThat(outcome.resolutions()).allSatisfy(resolution -> {
            assertThat(resolution.resolved()).isFalse();
            assertThat(resolution.clarificationIssued()).isFalse();
        });
    }

    @Test
    void clarificationAnswerBindsStoredCandidateBySelectedOption() {
        List<ReferenceResolver.StoredCandidate> candidates = List.of(
                stored("《题A》（上一批第 2 题）", 11L, 101L, "题A", "m1", 2, 2, row(11L, 101L, "题A", "m1", 2, 2)),
                stored("《题B》（这一批第 2 题）", 12L, 102L, "题B", "m2", 2, 4, row(12L, 102L, "题B", "m2", 2, 4))
        );
        AiChatRequest.ClarificationAnswer answer = new AiChatRequest.ClarificationAnswer(
                "ref_resolve_cm-1", "你指哪一道？", null, List.of("《题B》（这一批第 2 题）"), null);

        ReferenceResolver.Outcome outcome = resolver.resolve(new ReferenceResolver.Input(
                "选第二个", answer, null, List.of(), "m2", "m1", candidates));

        assertThat(outcome.resolutions()).hasSize(1);
        ReferenceResolver.Resolution resolution = outcome.resolutions().get(0);
        assertThat(resolution.resolver()).isEqualTo("clarification_answer");
        assertThat(resolution.resolvedProblemId()).isEqualTo(102L);
        assertThat(resolution.confidence()).isEqualTo(0.95);
    }

    @Test
    void clarificationAnswerFallsBackToFreeTextIndex() {
        List<ReferenceResolver.StoredCandidate> candidates = List.of(
                stored("《题A》（上一批第 2 题）", 11L, 101L, "题A", "m1", 2, 2, row(11L, 101L, "题A", "m1", 2, 2)),
                stored("《题B》（这一批第 2 题）", 12L, 102L, "题B", "m2", 2, 4, row(12L, 102L, "题B", "m2", 2, 4))
        );
        AiChatRequest.ClarificationAnswer answer = new AiChatRequest.ClarificationAnswer(
                "ref_resolve_cm-1", "你指哪一道？", "第一个", List.of(), null);

        ReferenceResolver.Outcome outcome = resolver.resolve(new ReferenceResolver.Input(
                "第一个", answer, null, List.of(), "m2", "m1", candidates));

        assertThat(outcome.resolutions()).hasSize(1);
        assertThat(outcome.resolutions().get(0).resolvedProblemId()).isEqualTo(101L);
    }

    @Test
    void selectionContextHasHighestPriority() {
        List<AiConversationProblemEntity> rows = List.of(
                row(1L, 101L, "题一", "m1", 1, 1),
                row(2L, 102L, "题二", "m1", 2, 2)
        );
        AiChatRequest.SelectedProblemContext selection = new AiChatRequest.SelectedProblemContext(
                "101", "题一", List.of(), List.of());

        ReferenceResolver.Outcome outcome = resolver.resolve(new ReferenceResolver.Input(
                "第2题和这道什么关系", null, selection, rows, "m1", null, List.of()));

        assertThat(outcome.resolutions()).hasSize(2);
        assertThat(outcome.resolutions().get(0).resolver()).isEqualTo("selection_context");
        assertThat(outcome.resolutions().get(0).resolvedProblemId()).isEqualTo(101L);
        assertThat(outcome.resolutions().get(0).confidence()).isEqualTo(1.0);
    }

    @Test
    void explicitTitleBeatsSetOrdinal() {
        List<AiConversationProblemEntity> rows = List.of(
                row(1L, 101L, "两数之和", "m1", 1, 1),
                row(2L, 102L, "星港建设", "m1", 2, 2)
        );

        ReferenceResolver.Outcome outcome = resolve("第2题先不说，先讲两数之和", rows, "m1", null);

        assertThat(outcome.resolutions()).hasSize(2);
        assertThat(outcome.resolutions().get(0).resolver()).isEqualTo("explicit_name_or_id");
        assertThat(outcome.resolutions().get(0).resolvedProblemId()).isEqualTo(101L);
    }

    private ReferenceResolver.Outcome resolve(String message, List<AiConversationProblemEntity> rows, String activeSetId, String lastSetId) {
        return resolver.resolve(new ReferenceResolver.Input(message, null, null, rows, activeSetId, lastSetId, List.of()));
    }

    private ReferenceResolver.StoredCandidate stored(String label, Long rowId, Long problemId, String title,
                                                     String setId, Integer setOrdinal, Integer conversationOrdinal,
                                                     AiConversationProblemEntity entity) {
        return new ReferenceResolver.StoredCandidate(label, rowId, problemId, title, setId, setOrdinal, conversationOrdinal, entity);
    }

    private AiConversationProblemEntity row(Long id, Long problemId, String title, String setId, Integer setOrdinal, Integer conversationOrdinal) {
        AiConversationProblemEntity row = new AiConversationProblemEntity();
        row.setId(id);
        row.setConversationId("c-ref");
        row.setUserId(7L);
        row.setProblemId(problemId);
        row.setTitle(title);
        row.setStatementSnapshot("题面：" + title);
        row.setSetId(setId);
        row.setSetOrdinal(setOrdinal);
        row.setConversationOrdinal(conversationOrdinal);
        return row;
    }
}
