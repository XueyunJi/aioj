package com.aioj.next.ai.agent.guard;

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

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * P3-5: L4 check grading, L3-gated private-leak check, fail-closed behaviour and
 * audit hygiene (no violating text in detail, PASS audited too) — design doc §5.4/§5.6.
 */
class ContestOutputGuardTest {

    private static final String TURN_ID = "t-l4";
    private static final long USER_ID = 7L;
    private static final String CONVERSATION_ID = "c-l4";
    private static final String PRIVATE_STATEMENT =
            "给定一个长度为n的正整数数组，求所有连续子数组中和最大的那个子数组，输出这个最大和的值，数据范围n不超过十万。";

    private final FullCodeHeuristicDetector detector = new FullCodeHeuristicDetector(4);
    private final ProblemFingerprintMatcher matcher = new ProblemFingerprintMatcher(0.45);
    private final GuardDecisionRecorder recorder = mock(GuardDecisionRecorder.class);
    private final ContestOutputGuard guard = new ContestOutputGuard(detector, matcher, recorder);

    @Test
    void completeCodeDraftIsInterceptedAsFullCodeDisclosure() {
        String draft = """
                完整代码如下：
                ```cpp
                #include <bits/stdc++.h>
                using namespace std;
                int main() {
                    int n;
                    cin >> n;
                    for (int i = 0; i < n; i++) {
                        if (i % 2 == 0) n--;
                    }
                    cout << n;
                }
                ```
                """;

        ContestOutputGuard.Verdict verdict = guard.evaluate(TURN_ID, USER_ID, CONVERSATION_ID,
                draft, participantView(), GuardVerdict.pass(), false);

        assertThat(verdict.intercepted()).isTrue();
        assertThat(verdict.reasonCode()).isEqualTo(ContestOutputGuard.REASON_FULL_CODE);
        AuditRecord audit = captureAudit();
        assertThat(audit.decision()).isEqualTo(GuardDecision.REFUSE);
        assertThat(audit.reasonCode()).isEqualTo(ContestOutputGuard.REASON_FULL_CODE);
        assertThat(audit.degraded()).isFalse();
        // First hit wins: later checks are not run.
        assertThat(audit.detail().get("checksRun").asText()).isEqualTo("full_code");
        assertThat(audit.detail().get("fullCodeFeatures").asText()).contains("ENTRY_POINT");
        assertThat(audit.detail().get("contentChars").asInt()).isEqualTo(draft.length());
        assertThat(audit.detail().get("regenerated").asBoolean()).isFalse();
        // The violating text itself is never stored.
        assertThat(audit.detail().toString()).doesNotContain("bits/stdc");
    }

    @Test
    void cleanHintPassesAndIsAudited() {
        String draft = "思路提示：考虑贪心，按结束时间排序后依次选取。复杂度 O(n log n)。";

        ContestOutputGuard.Verdict verdict = guard.evaluate(TURN_ID, USER_ID, CONVERSATION_ID,
                draft, participantView(), GuardVerdict.pass(), false);

        assertThat(verdict.intercepted()).isFalse();
        AuditRecord audit = captureAudit();
        assertThat(audit.decision()).isEqualTo(GuardDecision.PASS);
        assertThat(audit.reasonCode()).isEqualTo("no_violation");
        // No L3 PRIVATE hit: the private-statement check is skipped entirely.
        assertThat(audit.detail().get("checksRun").asText()).isEqualTo("full_code,hidden_test");
    }

    @Test
    void privateStatementLeakIsCaughtOnlyWhenL3MatchedPrivateProblem() {
        String draft = "这道题是这样的：" + PRIVATE_STATEMENT + "我来帮你分析。";
        GuardVerdict l3 = GuardVerdict.constrain(List.of(
                new GuardDecisionRecorder.MatchedProblemRef(1001L, 5501L, 7701L, 99001L, "PRIVATE", "DEFAULT")), 1.0);

        ContestOutputGuard.Verdict verdict = guard.evaluate(TURN_ID, USER_ID, CONVERSATION_ID,
                draft, participantView(), l3, true);

        assertThat(verdict.intercepted()).isTrue();
        assertThat(verdict.reasonCode()).isEqualTo(ContestOutputGuard.REASON_PRIVATE_STATEMENT);
        AuditRecord audit = captureAudit();
        assertThat(audit.detail().get("checksRun").asText()).isEqualTo("full_code,private_statement");
        assertThat(audit.detail().get("regenerated").asBoolean()).isTrue();
        assertThat(audit.matchedProblems()).hasSize(1);
        assertThat(audit.matchedProblems().get(0).problemId()).isEqualTo(1001L);
        assertThat(audit.detail().toString()).doesNotContain("子数组");
    }

    @Test
    void privateStatementInDraftIsAllowedWhenL3DidNotMatchIt() {
        // Without an L3 PRIVATE hit the leak check is skipped: the model cannot know
        // the private statement, and re-fingerprinting every output against every
        // problem would false-positive on legit guidance.
        String draft = "题目大意：" + PRIVATE_STATEMENT;

        ContestOutputGuard.Verdict verdict = guard.evaluate(TURN_ID, USER_ID, CONVERSATION_ID,
                draft, participantView(), GuardVerdict.pass(), false);

        assertThat(verdict.intercepted()).isFalse();
        AuditRecord audit = captureAudit();
        assertThat(audit.detail().get("checksRun").asText()).isEqualTo("full_code,hidden_test");
    }

    @Test
    void hiddenTestDisclosureIsIntercepted() {
        String draft = "根据我看到的评测点数据，第3个测试点的答案是42，直接特判即可。";

        ContestOutputGuard.Verdict verdict = guard.evaluate(TURN_ID, USER_ID, CONVERSATION_ID,
                draft, participantView(), GuardVerdict.pass(), false);

        assertThat(verdict.intercepted()).isTrue();
        assertThat(verdict.reasonCode()).isEqualTo(ContestOutputGuard.REASON_HIDDEN_TEST);
        AuditRecord audit = captureAudit();
        assertThat(audit.detail().get("checksRun").asText()).isEqualTo("full_code,hidden_test");
    }

    @Test
    void substantiveTutorialOnStrictMatchIsIntercepted() {
        // E4-12 live-eval regression (P3-8): a STRICT-matched turn must not ship
        // substantive tutoring even when it contains no full code.
        String draft = "这道题就是01背包。状态设计：dp[j] 表示容量为 j 时能装下的最大价值。"
                + "转移方程 dp[j]=max(dp[j], dp[j-w]+v)，j 必须倒序遍历，否则就变成完全背包。"
                + "初始化全 0，答案 dp[M]。时间复杂度 O(N×M)，空间 O(M)。";
        GuardVerdict l3 = GuardVerdict.constrain(List.of(
                new GuardDecisionRecorder.MatchedProblemRef(2002L, 5502L, 8802L, 99002L, "PUBLIC", "STRICT")), 1.0);

        ContestOutputGuard.Verdict verdict = guard.evaluate(TURN_ID, USER_ID, CONVERSATION_ID,
                draft, participantView(), l3, false);

        assertThat(verdict.intercepted()).isTrue();
        assertThat(verdict.reasonCode()).isEqualTo(ContestOutputGuard.REASON_DENY_MATCH_SUBSTANTIVE);
        AuditRecord audit = captureAudit();
        assertThat(audit.decision()).isEqualTo(GuardDecision.REFUSE);
        assertThat(audit.detail().get("checksRun").asText()).isEqualTo("full_code,deny_match");
        assertThat(audit.matchedProblems()).hasSize(1);
        assertThat(audit.matchedProblems().get(0).problemId()).isEqualTo(2002L);
    }

    @Test
    void substantiveAnswerOnPrivateMatchIsInterceptedEvenWithoutStatementLeak() {
        // PRIVATE match but the draft does not restate the statement: the leak
        // check passes, the deny-match backstop still intercepts the tutoring.
        String draft = "给你分析一下这类题：核心是异或运算，把所有数字异或一遍，出现偶数次的会相互抵消。";
        GuardVerdict l3 = GuardVerdict.constrain(List.of(
                new GuardDecisionRecorder.MatchedProblemRef(1001L, 5501L, 7701L, 99001L, "PRIVATE", "DEFAULT")), 0.9);

        ContestOutputGuard.Verdict verdict = guard.evaluate(TURN_ID, USER_ID, CONVERSATION_ID,
                draft, participantView(), l3, false);

        assertThat(verdict.intercepted()).isTrue();
        assertThat(verdict.reasonCode()).isEqualTo(ContestOutputGuard.REASON_DENY_MATCH_SUBSTANTIVE);
        AuditRecord audit = captureAudit();
        assertThat(audit.detail().get("checksRun").asText()).isEqualTo("full_code,private_statement,deny_match");
    }

    @Test
    void refusalShapedAnswerOnDenyMatchPasses() {
        String draft = "抱歉，该内容涉及正在进行中的比赛，我暂时不能提供解答或完整代码。我们可以赛后再讨论。";
        GuardVerdict l3 = GuardVerdict.constrain(List.of(
                new GuardDecisionRecorder.MatchedProblemRef(2002L, 5502L, 8802L, 99002L, "PUBLIC", "STRICT")), 1.0);

        ContestOutputGuard.Verdict verdict = guard.evaluate(TURN_ID, USER_ID, CONVERSATION_ID,
                draft, participantView(), l3, false);

        assertThat(verdict.intercepted()).isFalse();
        AuditRecord audit = captureAudit();
        assertThat(audit.decision()).isEqualTo(GuardDecision.PASS);
        assertThat(audit.detail().get("checksRun").asText()).isEqualTo("full_code,deny_match,hidden_test");
    }

    @Test
    void substantiveHintOnPublicDefaultMatchIsUnaffectedByDenyBackstop() {
        // PUBLIC + DEFAULT is HINT_ONLY, not DENY: the backstop never runs, the
        // hint-level guidance the model is supposed to give keeps flowing.
        String draft = "思路提示：考虑贪心，按结束时间排序后依次选取。复杂度 O(n log n)。";
        GuardVerdict l3 = GuardVerdict.constrain(List.of(
                new GuardDecisionRecorder.MatchedProblemRef(3003L, 5503L, 7703L, 99003L, "PUBLIC", "DEFAULT")), 0.9);

        ContestOutputGuard.Verdict verdict = guard.evaluate(TURN_ID, USER_ID, CONVERSATION_ID,
                draft, participantView(), l3, false);

        assertThat(verdict.intercepted()).isFalse();
        AuditRecord audit = captureAudit();
        assertThat(audit.detail().get("checksRun").asText()).isEqualTo("full_code,hidden_test");
    }

    @Test
    void guardFailureFailsClosedWithDegradedAudit() {
        ProblemFingerprintMatcher broken = mock(ProblemFingerprintMatcher.class);
        when(broken.match(anyString(), anyList())).thenThrow(new RuntimeException("matcher boom"));
        ContestOutputGuard failClosed = new ContestOutputGuard(detector, broken, recorder);
        GuardVerdict l3 = GuardVerdict.constrain(List.of(
                new GuardDecisionRecorder.MatchedProblemRef(1001L, 5501L, 7701L, 99001L, "PRIVATE", "DEFAULT")), 1.0);

        ContestOutputGuard.Verdict verdict = failClosed.evaluate(TURN_ID, USER_ID, CONVERSATION_ID,
                " harmless draft ", participantView(), l3, false);

        assertThat(verdict.intercepted()).isTrue();
        assertThat(verdict.reasonCode()).isEqualTo(ContestOutputGuard.REASON_GUARD_ERROR);
        AuditRecord audit = captureAudit();
        assertThat(audit.decision()).isEqualTo(GuardDecision.REFUSE);
        assertThat(audit.degraded()).isTrue();
        assertThat(audit.reasonCode()).isEqualTo(ContestOutputGuard.REASON_GUARD_ERROR);
    }

    @Test
    void recheckTriggerIsMarkedInAuditDetail() {
        String draft = "思路提示：考虑贪心，按结束时间排序后依次选取。";

        ContestOutputGuard.Verdict verdict = guard.evaluate(TURN_ID, USER_ID, CONVERSATION_ID,
                draft, participantView(), null, false, ContestOutputGuard.TRIGGER_RECHECK);

        // P3-6: race-triggered re-evaluations are ordinary L4 verdicts whose audit
        // detail names the trigger.
        assertThat(verdict.intercepted()).isFalse();
        assertThat(captureAudit().detail().get("trigger").asText()).isEqualTo("recheck");
    }

    @Test
    void plainEvaluationLeavesNoTriggerMarker() {
        String draft = "思路提示：考虑贪心，按结束时间排序后依次选取。";

        guard.evaluate(TURN_ID, USER_ID, CONVERSATION_ID, draft, participantView(), null, false);

        assertThat(captureAudit().detail().has("trigger")).isFalse();
    }

    @Test
    void guardFailureWithRecheckTriggerKeepsTriggerInDegradedDetail() {
        ProblemFingerprintMatcher broken = mock(ProblemFingerprintMatcher.class);
        when(broken.match(anyString(), anyList())).thenThrow(new RuntimeException("matcher boom"));
        ContestOutputGuard failClosed = new ContestOutputGuard(detector, broken, recorder);
        GuardVerdict l3 = GuardVerdict.constrain(List.of(
                new GuardDecisionRecorder.MatchedProblemRef(1001L, 5501L, 7701L, 99001L, "PRIVATE", "DEFAULT")), 1.0);

        ContestOutputGuard.Verdict verdict = failClosed.evaluate(TURN_ID, USER_ID, CONVERSATION_ID,
                " harmless draft ", participantView(), l3, false, ContestOutputGuard.TRIGGER_RECHECK);

        assertThat(verdict.intercepted()).isTrue();
        AuditRecord audit = captureAudit();
        assertThat(audit.degraded()).isTrue();
        assertThat(audit.detail().get("trigger").asText()).isEqualTo("recheck");
    }

    private ContestPolicyView participantView() {
        return new ContestPolicyView(ParticipantStatus.PARTICIPANT_ACTIVE, Map.of(1001L,
                new ContestPolicyView.ContestProblemPolicy(1001L, ProblemVisibility.PRIVATE,
                        ContestAiPolicyMode.DEFAULT, null, PRIVATE_STATEMENT,
                        List.of(new RunningContestProblemOccurrence(5501L, 7701L, 99001L)))));
    }

    private record AuditRecord(GuardDecision decision, String reasonCode,
                               List<GuardDecisionRecorder.MatchedProblemRef> matchedProblems,
                               JsonNode detail, boolean degraded) {
    }

    @SuppressWarnings("unchecked")
    private AuditRecord captureAudit() {
        ArgumentCaptor<GuardDecision> decision = ArgumentCaptor.forClass(GuardDecision.class);
        ArgumentCaptor<String> reason = ArgumentCaptor.forClass(String.class);
        ArgumentCaptor<List<GuardDecisionRecorder.MatchedProblemRef>> refs = ArgumentCaptor.forClass(List.class);
        ArgumentCaptor<JsonNode> detail = ArgumentCaptor.forClass(JsonNode.class);
        ArgumentCaptor<Boolean> degraded = ArgumentCaptor.forClass(Boolean.class);
        verify(recorder).record(eq(TURN_ID), eq(USER_ID), eq(CONVERSATION_ID), eq(GuardLayer.L4_OUTPUT),
                decision.capture(), refs.capture(), reason.capture(), detail.capture(),
                degraded.capture(), org.mockito.ArgumentMatchers.any());
        return new AuditRecord(decision.getValue(), reason.getValue(), refs.getValue(), detail.getValue(),
                degraded.getValue());
    }
}
