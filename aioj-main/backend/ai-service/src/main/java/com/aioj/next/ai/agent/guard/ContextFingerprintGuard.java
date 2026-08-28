package com.aioj.next.ai.agent.guard;

import com.aioj.next.ai.agent.model.GatewayMessage;
import com.aioj.next.ai.agent.policy.ContestPolicyView;
import com.aioj.next.ai.agent.policy.GuardDecisionRecorder;
import com.aioj.next.ai.agent.policy.GuardLayer;
import com.aioj.next.contract.contest.RunningContestProblemOccurrence;
import com.aioj.next.contract.contest.RunningContestProblemStatement;
import com.fasterxml.jackson.databind.node.JsonNodeFactory;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * L3 second layer (design doc §5.3, P3-4): before every model call the fully
 * assembled context is fingerprinted once more against the turn's constrained
 * running-contest problems, so a statement smuggled in through history messages
 * or tool results (fetched originals) cannot bypass the message-layer match.
 * Same candidate source as the first layer: the deduplicated snapshot set of
 * the user's running runs (DISABLED-mode excluded), never the whole problem bank.
 *
 * <p>Self-match exclusion: only substantive content is matched. Every system
 * message in the agent loop is server-authored — the stable prompt, the L2
 * policy snapshot, the layer-1 CONTEST_GUARD_MATCH block, tool-call nudges and
 * this layer's own injected constraint message — so system messages are never
 * matched. Assistant messages appended during the current run are the model's
 * own working output and belong to L4 (§5.4); assistant messages from previous
 * turns (stored history) ARE matched. User and tool-result messages are always
 * matched.</p>
 *
 * <p>A hit is CONSTRAIN, never REFUSE (refusing is L4's and the tool layer's
 * job): newly matched problems' rule lines are injected as one trailing system
 * message which then stays in the message list for every later agent step.
 * Problems already constrained by the message layer or an earlier step are not
 * re-injected. Every evaluation, PASS included, is audited as
 * L3_FINGERPRINT_CTX (§5.6). Non-participants and empty candidate sets skip
 * entirely — zero cost, zero audit rows.</p>
 */
@Service
public class ContextFingerprintGuard {

    private final ProblemFingerprintMatcher matcher;
    private final GuardDecisionRecorder recorder;

    public ContextFingerprintGuard(ProblemFingerprintMatcher matcher, GuardDecisionRecorder recorder) {
        this.matcher = matcher;
        this.recorder = recorder;
    }

    /**
     * Outcome of one pre-call evaluation. {@code injectionText} is non-null only
     * when at least one newly matched problem needs its rules injected.
     */
    public record Evaluation(GuardVerdict verdict, List<Long> newlyMatchedProblemIds, String injectionText) {
    }

    /**
     * @param alreadyConstrainedProblemIds problems the message layer or an earlier
     *        step of this layer already constrained; their rule lines are not re-injected
     * @param bootstrapMessageCount messages below this index come from the bootstrap
     *        (history + current request); messages at/above it were appended by this run
     * @param agentStep 1-based agent step whose model call is being prepared
     * @return null when the layer does not apply (non-participant or no constrained problems)
     */
    public Evaluation evaluate(String turnId, long userId, String conversationId,
                               ContestPolicyView contestPolicy, Set<Long> alreadyConstrainedProblemIds,
                               List<GatewayMessage> messages, int bootstrapMessageCount, int agentStep) {
        if (contestPolicy == null || !contestPolicy.isParticipant()) {
            return null;
        }
        List<ContestPolicyView.ContestProblemPolicy> constrained = contestPolicy.constrainedProblems();
        if (constrained.isEmpty()) {
            return null;
        }
        long startedNanos = System.nanoTime();
        StringBuilder text = new StringBuilder();
        int userMessages = 0;
        int toolMessages = 0;
        int historyAssistantMessages = 0;
        for (int i = 0; i < messages.size(); i++) {
            GatewayMessage message = messages.get(i);
            if (message.content() == null || message.content().isBlank()) {
                continue;
            }
            switch (message.role()) {
                case "user" -> {
                    text.append('\n').append(message.content());
                    userMessages++;
                }
                case "tool" -> {
                    text.append('\n').append(message.content());
                    toolMessages++;
                }
                case "assistant" -> {
                    if (i < bootstrapMessageCount) {
                        text.append('\n').append(message.content());
                        historyAssistantMessages++;
                    }
                    // else: this run's own model output — L4's jurisdiction (§5.4)
                }
                default -> {
                    // system: server-authored policy/guard text — never matched
                }
            }
        }
        Map<Long, ContestPolicyView.ContestProblemPolicy> policiesById = new LinkedHashMap<>();
        List<RunningContestProblemStatement> candidates = new ArrayList<>();
        for (ContestPolicyView.ContestProblemPolicy policy : constrained) {
            policiesById.put(policy.problemId(), policy);
            RunningContestProblemOccurrence first = policy.firstOccurrence();
            candidates.add(new RunningContestProblemStatement(
                    policy.problemId(), policy.statement(),
                    first == null ? null : first.contestId(),
                    first == null ? null : first.contestRunId(),
                    first == null ? null : first.contestProblemId(),
                    policy.visibility(), policy.aiPolicyMode(), policy.aiPolicyNotes(), policy.occurrences()));
        }
        GuardVerdict verdict = matcher.match(text.toString(), candidates);
        int latencyMs = (int) ((System.nanoTime() - startedNanos) / 1_000_000L);
        List<Long> newlyMatched = new ArrayList<>();
        if (verdict.hasMatches()) {
            for (GuardDecisionRecorder.MatchedProblemRef ref : verdict.matchedProblems()) {
                if (ref.problemId() != null && !alreadyConstrainedProblemIds.contains(ref.problemId())
                        && !newlyMatched.contains(ref.problemId())) {
                    newlyMatched.add(ref.problemId());
                }
            }
        }
        ObjectNode detail = JsonNodeFactory.instance.objectNode()
                .put("candidateCount", candidates.size())
                .put("maxScore", verdict.maxScore())
                .put("agentStep", agentStep)
                .put("matchedChars", text.length())
                .put("userMessages", userMessages)
                .put("toolMessages", toolMessages)
                .put("historyAssistantMessages", historyAssistantMessages)
                .put("newMatchCount", newlyMatched.size());
        recorder.record(turnId, userId, conversationId,
                GuardLayer.L3_FINGERPRINT_CTX, verdict.decision(),
                verdict.matchedProblems(), verdict.reasonCode(), detail, false, latencyMs);
        String injectionText = null;
        if (!newlyMatched.isEmpty()) {
            List<ContestGuardMatchText.RuleLine> lines = newlyMatched.stream()
                    .map(problemId -> {
                        ContestPolicyView.ContestProblemPolicy policy = policiesById.get(problemId);
                        return new ContestGuardMatchText.RuleLine(problemId,
                                policy.visibility() == null ? null : policy.visibility().name(),
                                policy.aiPolicyMode() == null ? null : policy.aiPolicyMode().name(),
                                policy.aiPolicyNotes());
                    })
                    .toList();
            injectionText = ContestGuardMatchText.renderBlock(
                    "server fingerprint result for the assembled context",
                    "The assembled conversation context (history or tool results) matches "
                            + "restricted running-contest problem(s):",
                    lines);
        }
        return new Evaluation(verdict, List.copyOf(newlyMatched), injectionText);
    }
}
