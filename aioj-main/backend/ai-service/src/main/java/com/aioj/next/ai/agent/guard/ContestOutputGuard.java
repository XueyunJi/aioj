package com.aioj.next.ai.agent.guard;

import com.aioj.next.ai.agent.policy.ContestPolicyView;
import com.aioj.next.ai.agent.policy.GuardDecision;
import com.aioj.next.ai.agent.policy.GuardDecisionRecorder;
import com.aioj.next.ai.agent.policy.GuardLayer;
import com.aioj.next.contract.contest.RunningContestProblemOccurrence;
import com.aioj.next.contract.contest.RunningContestProblemStatement;
import com.fasterxml.jackson.databind.node.JsonNodeFactory;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * L4 Output Guard (design doc §5.4, P3-5): the last server-side check between a
 * restricted turn's fully generated draft and the student. Checks, in order:
 *
 * <ol>
 *   <li><b>full_code_disclosure</b> — {@link FullCodeHeuristicDetector}. Always
 *       runs on restricted turns, even with no L3 hit: a deep paraphrase that
 *       slips both fingerprint layers still cannot smuggle complete code out.</li>
 *   <li><b>private_statement_leak</b> — only meaningful when the merged L3
 *       verdict matched PRIVATE problems: the draft is fingerprinted against
 *       exactly those problems' snapshot statements (same matcher as L3).</li>
 *   <li><b>deny_match_substantive</b> — P3-8 (E4-12 live-eval gap): when the
 *       merged L3 verdict matched a DENY-level problem (PRIVATE statement or
 *       STRICT run policy), the turn's correct outcome is a refusal, and model
 *       compliance alone proved unreliable (a STRICT-matched turn once received
 *       a full tutorial with a code skeleton). The draft may only be delivered
 *       when it is refusal-shaped; substantive tutoring is intercepted and —
 *       after the single safe regeneration — downgraded to the server-authored
 *       refusal. A flowery refusal misread as substantive costs nothing but the
 *       same safe text, so the heuristic errs on the intercept side.</li>
 *   <li><b>hidden_test_leak</b> — lightweight keyword patterns for hidden-test /
 *       judging-point disclosure. False positives cost one safe regeneration,
 *       never a wrong delivery.</li>
 * </ol>
 *
 * <p>Every evaluation — PASS included — is audited as {@code L4_OUTPUT} (§5.6).
 * Detail carries the checks run, hit feature families / reason, content length,
 * the regenerated flag and latency; the violating text itself is never stored.
 * The guard is fail-closed per the contest domain (Q5): any internal error is
 * treated as an interception ({@code guard_error}, {@code degraded=true}) so the
 * turn takes the refusal path instead of the error path.</p>
 */
@Service
public class ContestOutputGuard {

    private static final Logger log = LoggerFactory.getLogger(ContestOutputGuard.class);

    public static final String REASON_FULL_CODE = "full_code_disclosure";
    public static final String REASON_PRIVATE_STATEMENT = "private_statement_leak";
    public static final String REASON_DENY_MATCH_SUBSTANTIVE = "deny_match_substantive";
    public static final String REASON_HIDDEN_TEST = "hidden_test_leak";
    public static final String REASON_GUARD_ERROR = "guard_error";

    /** P3-8: on DENY-matched turns only refusal-shaped drafts may be delivered. */
    private static final int REFUSAL_SHAPE_MAX_CHARS = 800;
    private static final List<String> REFUSAL_MARKERS = List.of(
            "抱歉", "无法", "不能", "不可以", "没办法", "受保护", "比赛期间", "正在进行中的比赛", "暂时不能",
            "sorry", "cannot", "can't", "unable", "refuse", "not allowed", "ongoing contest");

    /** P3-6 detail marker: the evaluation was fired by the time-race recheck, not the initial L4 pass. */
    public static final String TRIGGER_RECHECK = "recheck";

    /** Minimal constant pattern set (P3-5 frozen scope: 轻量模式匹配，无配置面). */
    private static final List<String> HIDDEN_TEST_PATTERNS = List.of(
            "hidden test",
            "hidden testcase",
            "hidden test case",
            "隐藏测试",
            "隐藏评测点",
            "隐藏数据点",
            "评测点数据",
            "测试点数据",
            "评测数据"
    );

    private final FullCodeHeuristicDetector fullCodeDetector;
    private final ProblemFingerprintMatcher fingerprintMatcher;
    private final GuardDecisionRecorder recorder;

    public ContestOutputGuard(FullCodeHeuristicDetector fullCodeDetector,
                              ProblemFingerprintMatcher fingerprintMatcher,
                              GuardDecisionRecorder recorder) {
        this.fullCodeDetector = fullCodeDetector;
        this.fingerprintMatcher = fingerprintMatcher;
        this.recorder = recorder;
    }

    /**
     * Outcome of one draft evaluation. {@code reasonCode} is one of the
     * {@code REASON_*} constants when {@code intercepted} is true.
     */
    public record Verdict(boolean intercepted, String reasonCode) {

        public static Verdict pass() {
            return new Verdict(false, null);
        }

        public static Verdict intercept(String reasonCode) {
            return new Verdict(true, reasonCode);
        }
    }

    /**
     * Evaluates one draft of a restricted turn. Callers gate on the restricted-turn
     * predicate themselves (non-restricted turns skip L4 entirely, zero audit rows).
     *
     * @param l3Verdict merged L3 message+context verdict carried by the run result
     *                  (may be null or PASS); its PRIVATE refs select the leak-check candidates
     * @param regenerated true when evaluating the one allowed safe-regeneration draft
     */
    public Verdict evaluate(String turnId, long userId, String conversationId,
                            String draftContent, ContestPolicyView contestPolicy, GuardVerdict l3Verdict,
                            boolean regenerated) {
        return evaluate(turnId, userId, conversationId, draftContent, contestPolicy, l3Verdict, regenerated, null);
    }

    /**
     * @param trigger P3-6: non-null marks the audit detail with the external trigger
     *                that fired this evaluation ({@link #TRIGGER_RECHECK})
     */
    public Verdict evaluate(String turnId, long userId, String conversationId,
                            String draftContent, ContestPolicyView contestPolicy, GuardVerdict l3Verdict,
                            boolean regenerated, String trigger) {
        long startedNanos = System.nanoTime();
        try {
            return doEvaluate(turnId, userId, conversationId, draftContent, contestPolicy, l3Verdict,
                    regenerated, trigger, startedNanos);
        } catch (RuntimeException ex) {
            // Contest domain is fail-closed (Q5): a broken guard must look like an
            // interception, not like a pass and not like a provider error.
            log.error("L4 output guard failed closed turn={} error={}", turnId, ex.toString());
            int latencyMs = (int) ((System.nanoTime() - startedNanos) / 1_000_000L);
            ObjectNode detail = JsonNodeFactory.instance.objectNode()
                    .put("checksRun", "guard_error")
                    .put("contentChars", draftContent == null ? 0 : draftContent.length())
                    .put("regenerated", regenerated)
                    .put("error", ex.getClass().getSimpleName());
            if (trigger != null) {
                detail.put("trigger", trigger);
            }
            recorder.record(turnId, userId, conversationId, GuardLayer.L4_OUTPUT, GuardDecision.REFUSE,
                    List.of(), REASON_GUARD_ERROR, detail, true, latencyMs);
            return Verdict.intercept(REASON_GUARD_ERROR);
        }
    }

    private Verdict doEvaluate(String turnId, long userId, String conversationId,
                               String draftContent, ContestPolicyView contestPolicy, GuardVerdict l3Verdict,
                               boolean regenerated, String trigger, long startedNanos) {
        String content = draftContent == null ? "" : draftContent;
        List<String> checksRun = new ArrayList<>();
        List<GuardDecisionRecorder.MatchedProblemRef> matchedRefs = List.of();
        GuardVerdict leakVerdict = null;

        checksRun.add("full_code");
        FullCodeHeuristicDetector.Detection detection = fullCodeDetector.detect(content);
        String reasonCode = detection.fullCode() ? REASON_FULL_CODE : null;

        Map<Long, ContestPolicyView.ContestProblemPolicy> privateMatched = privateMatchedProblems(l3Verdict, contestPolicy);
        if (reasonCode == null && !privateMatched.isEmpty()) {
            checksRun.add("private_statement");
            leakVerdict = fingerprintMatcher.match(content, toStatements(privateMatched));
            if (leakVerdict.hasMatches()) {
                reasonCode = REASON_PRIVATE_STATEMENT;
                matchedRefs = leakVerdict.matchedProblems();
            }
        }

        // P3-8: deterministic backstop for DENY-level L3 matches (PRIVATE or
        // STRICT) — substantive tutoring is never deliverable on these turns.
        List<GuardDecisionRecorder.MatchedProblemRef> denyMatched = denyMatchedRefs(l3Verdict);
        if (reasonCode == null && !denyMatched.isEmpty()) {
            checksRun.add("deny_match");
            if (!isRefusalShaped(content)) {
                reasonCode = REASON_DENY_MATCH_SUBSTANTIVE;
                matchedRefs = denyMatched;
            }
        }

        if (reasonCode == null) {
            checksRun.add("hidden_test");
            if (mentionsHiddenTest(content)) {
                reasonCode = REASON_HIDDEN_TEST;
            }
        }

        int latencyMs = (int) ((System.nanoTime() - startedNanos) / 1_000_000L);
        ObjectNode detail = JsonNodeFactory.instance.objectNode()
                .put("checksRun", String.join(",", checksRun))
                .put("contentChars", content.length())
                .put("regenerated", regenerated);
        if (trigger != null) {
            detail.put("trigger", trigger);
        }
        if (!detection.features().isEmpty()) {
            detail.put("fullCodeFeatures", detection.features().stream()
                    .map(Enum::name).reduce((a, b) -> a + "," + b).orElse(""));
        }
        if (leakVerdict != null) {
            detail.put("leakMaxScore", leakVerdict.maxScore());
        }
        GuardDecision decision = reasonCode == null ? GuardDecision.PASS : GuardDecision.REFUSE;
        recorder.record(turnId, userId, conversationId, GuardLayer.L4_OUTPUT, decision,
                matchedRefs, reasonCode == null ? "no_violation" : reasonCode, detail, false, latencyMs);
        return reasonCode == null ? Verdict.pass() : Verdict.intercept(reasonCode);
    }

    /**
     * Problems the merged L3 verdict matched with PRIVATE visibility, resolved
     * against the policy view for their snapshot statements. Empty when the
     * verdict is null/PASS — the leak check is skipped entirely then.
     */
    private Map<Long, ContestPolicyView.ContestProblemPolicy> privateMatchedProblems(
            GuardVerdict l3Verdict, ContestPolicyView contestPolicy) {
        Map<Long, ContestPolicyView.ContestProblemPolicy> matched = new LinkedHashMap<>();
        if (l3Verdict == null || !l3Verdict.hasMatches() || contestPolicy == null) {
            return matched;
        }
        for (GuardDecisionRecorder.MatchedProblemRef ref : l3Verdict.matchedProblems()) {
            if (ref.problemId() == null || !"PRIVATE".equals(ref.visibility())) {
                continue;
            }
            ContestPolicyView.ContestProblemPolicy policy = contestPolicy.problem(ref.problemId());
            if (policy != null && policy.statement() != null && !policy.statement().isBlank()) {
                matched.putIfAbsent(ref.problemId(), policy);
            }
        }
        return matched;
    }

    private List<RunningContestProblemStatement> toStatements(
            Map<Long, ContestPolicyView.ContestProblemPolicy> problems) {
        List<RunningContestProblemStatement> statements = new ArrayList<>();
        for (ContestPolicyView.ContestProblemPolicy policy : problems.values()) {
            RunningContestProblemOccurrence first = policy.firstOccurrence();
            statements.add(new RunningContestProblemStatement(
                    policy.problemId(), policy.statement(),
                    first == null ? null : first.contestId(),
                    first == null ? null : first.contestRunId(),
                    first == null ? null : first.contestProblemId(),
                    policy.visibility(), policy.aiPolicyMode(), policy.aiPolicyNotes(), policy.occurrences()));
        }
        return statements;
    }

    /**
     * P3-8: refs of the merged L3 verdict whose assistance level is DENY —
     * PRIVATE statement, or STRICT run policy (mirrors the {@code assistanceOf}
     * rule shared by the policy view and the contest tools).
     */
    private List<GuardDecisionRecorder.MatchedProblemRef> denyMatchedRefs(GuardVerdict l3Verdict) {
        if (l3Verdict == null || !l3Verdict.hasMatches()) {
            return List.of();
        }
        List<GuardDecisionRecorder.MatchedProblemRef> deny = new ArrayList<>();
        for (GuardDecisionRecorder.MatchedProblemRef ref : l3Verdict.matchedProblems()) {
            if ("PRIVATE".equals(ref.visibility()) || "STRICT".equals(ref.aiPolicyMode())) {
                deny.add(ref);
            }
        }
        return deny;
    }

    /**
     * Conservative refusal shape: short, code-free, and carrying an explicit
     * inability/apology marker. Anything else on a DENY-matched turn is treated
     * as substantive — a misjudged flowery refusal only costs a downgrade to the
     * same safe server text, never a violation.
     */
    private boolean isRefusalShaped(String content) {
        if (content.length() > REFUSAL_SHAPE_MAX_CHARS
                || content.contains("```") || content.contains("~~~")) {
            return false;
        }
        String lowered = content.toLowerCase(java.util.Locale.ROOT);
        for (String marker : REFUSAL_MARKERS) {
            if (lowered.contains(marker.toLowerCase(java.util.Locale.ROOT))) {
                return true;
            }
        }
        return false;
    }

    private boolean mentionsHiddenTest(String content) {
        String lowered = content.toLowerCase(java.util.Locale.ROOT);
        for (String pattern : HIDDEN_TEST_PATTERNS) {
            if (lowered.contains(pattern.toLowerCase(java.util.Locale.ROOT))) {
                return true;
            }
        }
        return false;
    }
}
