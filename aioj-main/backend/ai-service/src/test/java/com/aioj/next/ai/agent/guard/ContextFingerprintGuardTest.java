package com.aioj.next.ai.agent.guard;

import com.aioj.next.ai.agent.model.GatewayMessage;
import com.aioj.next.ai.agent.policy.ContestPolicyView;
import com.aioj.next.ai.agent.policy.GuardDecision;
import com.aioj.next.ai.agent.policy.GuardDecisionRecorder;
import com.aioj.next.ai.agent.policy.GuardLayer;
import com.aioj.next.ai.agent.policy.ParticipantStatus;
import com.aioj.next.contract.contest.ContestAiPolicyMode;
import com.aioj.next.contract.contest.RunningContestProblemOccurrence;
import com.aioj.next.contract.problem.ProblemVisibility;
import com.fasterxml.jackson.databind.JsonNode;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;

/**
 * L3 second layer (design doc §5.3, P3-4): the assembled context is re-fingerprinted
 * before every model call. Covers the history/tool-result bypass paths, the
 * self-match exclusion rules, the layer-1 dedup and the audit contract (§5.6).
 */
class ContextFingerprintGuardTest {

    private static final String STATEMENT = """
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

    private final GuardDecisionRecorder recorder = mock(GuardDecisionRecorder.class);
    private final ContextFingerprintGuard guard =
            new ContextFingerprintGuard(new ProblemFingerprintMatcher(0.45), recorder);

    @Test
    void historyStatementPasteHitsInjectsAndAudits() {
        List<GatewayMessage> messages = List.of(
                GatewayMessage.system("stable system prompt"),
                GatewayMessage.user("上次那题：" + STATEMENT),
                GatewayMessage.user("继续讲讲"));

        ContextFingerprintGuard.Evaluation evaluation = guard.evaluate("t-1", 7L, "c1",
                participantView(policy(1002L, ProblemVisibility.PRIVATE, ContestAiPolicyMode.DEFAULT, null)),
                Set.of(), messages, 3, 1);

        assertThat(evaluation.verdict().decision()).isEqualTo(GuardDecision.CONSTRAIN);
        assertThat(evaluation.newlyMatchedProblemIds()).containsExactly(1002L);
        assertThat(evaluation.injectionText())
                .contains("[Contest Guard Match — server fingerprint result for the assembled context; enforce]")
                .contains("Problem #1002 (PRIVATE, policy DEFAULT): private contest problem: refuse to discuss");
        JsonNode detail = auditDetail(GuardDecision.CONSTRAIN, "fingerprint_match");
        assertThat(detail.get("agentStep").asInt()).isEqualTo(1);
        assertThat(detail.get("candidateCount").asInt()).isEqualTo(1);
        assertThat(detail.get("userMessages").asInt()).isEqualTo(2);
        assertThat(detail.get("toolMessages").asInt()).isEqualTo(0);
        assertThat(detail.get("newMatchCount").asInt()).isEqualTo(1);
        assertThat(detail.get("maxScore").asDouble()).isGreaterThanOrEqualTo(0.45);
    }

    @Test
    void toolResultStatementHits() {
        // A fetched original entering the context via a tool result (bootstrap=2:
        // the tool message was appended by the current run, after the bootstrap).
        List<GatewayMessage> messages = List.of(
                GatewayMessage.system("stable system prompt"),
                GatewayMessage.user("帮我分析这个"),
                GatewayMessage.toolResult("call-1", "{\"status\":\"OK\",\"statement\":\"" + STATEMENT + "\"}"));

        ContextFingerprintGuard.Evaluation evaluation = guard.evaluate("t-1", 7L, "c1",
                participantView(policy(1002L, ProblemVisibility.PRIVATE, ContestAiPolicyMode.DEFAULT, null)),
                Set.of(), messages, 2, 2);

        assertThat(evaluation.verdict().decision()).isEqualTo(GuardDecision.CONSTRAIN);
        assertThat(evaluation.newlyMatchedProblemIds()).containsExactly(1002L);
        JsonNode detail = auditDetail(GuardDecision.CONSTRAIN, "fingerprint_match");
        assertThat(detail.get("agentStep").asInt()).isEqualTo(2);
        assertThat(detail.get("toolMessages").asInt()).isEqualTo(1);
    }

    @Test
    void serverAuthoredGuardAndPolicyTextNeverSelfMatches() {
        // Even when server-authored text literally contains the statement (worst
        // case), system messages are never matched: stable prompt, L2 policy
        // snapshot, the layer-1 match block and this layer's own injections.
        String layerOneBlock = ContestGuardMatchText.renderBlock(
                "server fingerprint result for the current message",
                "The user's current message matches restricted running-contest problem(s):",
                List.of(new ContestGuardMatchText.RuleLine(1002L, "PRIVATE", "DEFAULT", null)));
        List<GatewayMessage> messages = List.of(
                GatewayMessage.system("stable prompt\n\n[Contest Participation Policy]\n" + STATEMENT),
                GatewayMessage.system(layerOneBlock),
                GatewayMessage.user("二分查找的边界怎么确定"));

        ContextFingerprintGuard.Evaluation evaluation = guard.evaluate("t-1", 7L, "c1",
                participantView(policy(1002L, ProblemVisibility.PRIVATE, ContestAiPolicyMode.DEFAULT, null)),
                Set.of(), messages, 3, 1);

        assertThat(evaluation.verdict().decision()).isEqualTo(GuardDecision.PASS);
        assertThat(evaluation.injectionText()).isNull();
        auditDetail(GuardDecision.PASS, "no_match");
    }

    @Test
    void inTurnAssistantOutputIsExcludedButHistoryAssistantIsMatched() {
        ContestPolicyView view = participantView(
                policy(1002L, ProblemVisibility.PRIVATE, ContestAiPolicyMode.DEFAULT, null));
        // Assistant message appended by THIS run (index >= bootstrap): L4's job, not matched.
        List<GatewayMessage> inTurn = List.of(
                GatewayMessage.system("sys"),
                GatewayMessage.user("继续"),
                GatewayMessage.assistant("题面是 " + STATEMENT, List.of()));
        ContextFingerprintGuard.Evaluation passEvaluation =
                guard.evaluate("t-1", 7L, "c1", view, Set.of(), inTurn, 2, 2);
        assertThat(passEvaluation.verdict().decision()).isEqualTo(GuardDecision.PASS);

        // Stored history assistant message (index < bootstrap) IS matched.
        List<GatewayMessage> history = List.of(
                GatewayMessage.system("sys"),
                GatewayMessage.user("这题什么意思"),
                GatewayMessage.assistant("题面是 " + STATEMENT, List.of()),
                GatewayMessage.user("继续"));
        ContextFingerprintGuard.Evaluation hitEvaluation =
                guard.evaluate("t-1", 7L, "c1", view, Set.of(), history, 4, 1);
        assertThat(hitEvaluation.verdict().decision()).isEqualTo(GuardDecision.CONSTRAIN);
        assertThat(hitEvaluation.newlyMatchedProblemIds()).containsExactly(1002L);
        JsonNode detail = auditDetail(GuardDecision.CONSTRAIN, "fingerprint_match");
        assertThat(detail.get("historyAssistantMessages").asInt()).isEqualTo(1);
    }

    @Test
    void nonParticipantAndDisabledOnlyViewsSkipWithoutAudit() {
        List<GatewayMessage> messages = List.of(GatewayMessage.user("上次那题：" + STATEMENT));
        assertThat(guard.evaluate("t-1", 7L, "c1", null, Set.of(), messages, 1, 1)).isNull();
        assertThat(guard.evaluate("t-1", 7L, "c1", ContestPolicyView.nonParticipant(),
                Set.of(), messages, 1, 1)).isNull();
        ContestPolicyView disabledOnly = participantView(
                policy(1002L, ProblemVisibility.PRIVATE, ContestAiPolicyMode.DISABLED, null));
        assertThat(guard.evaluate("t-1", 7L, "c1", disabledOnly, Set.of(), messages, 1, 1)).isNull();
        verifyNoInteractions(recorder);
    }

    @Test
    void passIsAuditedWithSourceProfile() {
        List<GatewayMessage> messages = List.of(
                GatewayMessage.system("sys"),
                GatewayMessage.user("帮我讲讲快速排序的时间复杂度"));

        ContextFingerprintGuard.Evaluation evaluation = guard.evaluate("t-1", 7L, "c1",
                participantView(policy(1002L, ProblemVisibility.PRIVATE, ContestAiPolicyMode.DEFAULT, null)),
                Set.of(), messages, 2, 1);

        assertThat(evaluation.verdict().decision()).isEqualTo(GuardDecision.PASS);
        assertThat(evaluation.newlyMatchedProblemIds()).isEmpty();
        assertThat(evaluation.injectionText()).isNull();
        JsonNode detail = auditDetail(GuardDecision.PASS, "no_match");
        assertThat(detail.get("newMatchCount").asInt()).isEqualTo(0);
        assertThat(detail.get("matchedChars").asInt()).isGreaterThan(0);
    }

    @Test
    void alreadyConstrainedProblemsAreAuditedButNotReinjected() {
        // Layer 1 (or an earlier step) already constrained #1002: the fingerprint
        // still matches, but no second rule block is injected.
        List<GatewayMessage> messages = List.of(
                GatewayMessage.system("sys"),
                GatewayMessage.user("上次那题：" + STATEMENT));

        ContextFingerprintGuard.Evaluation evaluation = guard.evaluate("t-1", 7L, "c1",
                participantView(policy(1002L, ProblemVisibility.PRIVATE, ContestAiPolicyMode.DEFAULT, null)),
                Set.of(1002L), messages, 2, 3);

        assertThat(evaluation.verdict().decision()).isEqualTo(GuardDecision.CONSTRAIN);
        assertThat(evaluation.newlyMatchedProblemIds()).isEmpty();
        assertThat(evaluation.injectionText()).isNull();
        JsonNode detail = auditDetail(GuardDecision.CONSTRAIN, "fingerprint_match");
        assertThat(detail.get("newMatchCount").asInt()).isEqualTo(0);
    }

    @Test
    void publicProblemInjectsHintOnlyRuleWithRunNotes() {
        List<GatewayMessage> messages = List.of(GatewayMessage.user("题面：" + STATEMENT));

        ContextFingerprintGuard.Evaluation evaluation = guard.evaluate("t-1", 7L, "c1",
                participantView(policy(1003L, ProblemVisibility.PUBLIC, ContestAiPolicyMode.DEFAULT, "只给思路")),
                Set.of(), messages, 1, 1);

        assertThat(evaluation.injectionText())
                .contains("Problem #1003 (PUBLIC, policy DEFAULT): public contest problem: "
                        + "hints and idea-level guidance only; never output complete submittable code")
                .contains("Run notes: 只给思路");
    }

    @Test
    void strictModeInjectsRefuseRule() {
        List<GatewayMessage> messages = List.of(GatewayMessage.user("题面：" + STATEMENT));

        ContextFingerprintGuard.Evaluation evaluation = guard.evaluate("t-1", 7L, "c1",
                participantView(policy(1004L, ProblemVisibility.PUBLIC, ContestAiPolicyMode.STRICT, null)),
                Set.of(), messages, 1, 1);

        assertThat(evaluation.injectionText())
                .contains("Problem #1004 (PUBLIC, policy STRICT): "
                        + "refuse any question materially about this problem (STRICT run policy)");
    }

    @Test
    void mergedVerdictUnionsLayersAndDeduplicatesRefs() {
        GuardVerdict layerOne = GuardVerdict.constrain(List.of(
                new GuardDecisionRecorder.MatchedProblemRef(1002L, 5501L, 7701L, 99001L, "PRIVATE", "DEFAULT")), 0.9);
        GuardVerdict layerTwo = GuardVerdict.constrain(List.of(
                new GuardDecisionRecorder.MatchedProblemRef(1002L, 5501L, 7701L, 99001L, "PRIVATE", "DEFAULT"),
                new GuardDecisionRecorder.MatchedProblemRef(1003L, 5501L, 7701L, 99002L, "PUBLIC", "DEFAULT")), 0.95);

        GuardVerdict merged = layerOne.mergedWith(layerTwo);

        assertThat(merged.decision()).isEqualTo(GuardDecision.CONSTRAIN);
        assertThat(merged.matchedProblems())
                .extracting(GuardDecisionRecorder.MatchedProblemRef::problemId)
                .containsExactly(1002L, 1003L);
        assertThat(merged.maxScore()).isEqualTo(0.95);
        assertThat(GuardVerdict.pass().mergedWith(layerTwo).matchedProblems()).hasSize(2);
        assertThat(layerOne.mergedWith(null)).isSameAs(layerOne);
        assertThat(layerOne.mergedWith(GuardVerdict.pass())).isSameAs(layerOne);
    }

    private JsonNode auditDetail(GuardDecision decision, String reasonCode) {
        ArgumentCaptor<JsonNode> detail = ArgumentCaptor.forClass(JsonNode.class);
        verify(recorder).record(eq("t-1"), eq(7L), eq("c1"), eq(GuardLayer.L3_FINGERPRINT_CTX), eq(decision),
                anyList(), eq(reasonCode), detail.capture(), eq(false), any());
        return detail.getValue();
    }

    private ContestPolicyView participantView(ContestPolicyView.ContestProblemPolicy... policies) {
        Map<Long, ContestPolicyView.ContestProblemPolicy> byId = new LinkedHashMap<>();
        for (ContestPolicyView.ContestProblemPolicy policy : policies) {
            byId.put(policy.problemId(), policy);
        }
        return new ContestPolicyView(ParticipantStatus.PARTICIPANT_ACTIVE, byId);
    }

    private ContestPolicyView.ContestProblemPolicy policy(long problemId, ProblemVisibility visibility,
                                                          ContestAiPolicyMode mode, String notes) {
        return new ContestPolicyView.ContestProblemPolicy(problemId, visibility, mode, notes, STATEMENT,
                List.of(new RunningContestProblemOccurrence(5501L, 7701L, 99001L)));
    }
}
