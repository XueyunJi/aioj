package com.aioj.next.ai.agent.problem.tool;

import com.aioj.next.ai.agent.policy.ContestPolicyView;
import com.aioj.next.ai.agent.policy.GuardDecision;
import com.aioj.next.ai.agent.policy.GuardDecisionRecorder;
import com.aioj.next.ai.agent.policy.GuardLayer;
import com.aioj.next.ai.agent.tool.ToolExecutionContext;
import com.aioj.next.ai.agent.tool.ToolResult;
import com.aioj.next.ai.agent.tool.ToolStatus;
import com.aioj.next.contract.contest.ContestAiPolicyMode;
import com.aioj.next.contract.contest.RunningContestProblemOccurrence;
import com.aioj.next.contract.problem.ProblemVisibility;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;

import java.util.ArrayList;
import java.util.List;

/**
 * Shared rules for the P3-3 problem tools (C5, design doc §4.3/§5.5): the
 * anti-enumeration unified denial payload, the per-problem assistance verdict
 * (mirrors {@code PolicySnapshotService.assistanceOf}), and TOOL_ABAC audit
 * writes for every authorization denial.
 */
final class ProblemToolSupport {

    /** Frozen Q4: "not found" and "not allowed" are indistinguishable (design doc §5.5). */
    static final String UNIFIED_NOT_FOUND = "没有找到当前账户可访问的匹配题目";
    /**
     * Every unified denial carries this single error code so probing cannot
     * tell "missing" apart from "forbidden" or "rate limited"; the specific
     * reason code is only written to the guard audit.
     */
    static final String UNIFIED_ERROR_CODE = "NOT_ACCESSIBLE";

    static final String ASSISTANCE_FULL_TUTORING = "FULL_TUTORING";
    static final String ASSISTANCE_HINT_ONLY = "HINT_ONLY";
    static final String ASSISTANCE_DENY = "DENY";

    static final String REASON_CONTEST_PROBLEM_VIEW_DENIED = "contest_problem_view_denied";
    static final String REASON_PROBLEM_OUTSIDE_CONTEST_SCOPE = "problem_outside_contest_scope";
    static final String REASON_PROBLEM_NOT_VISIBLE = "problem_not_visible";
    static final String REASON_PROBLEM_VISIBILITY_CHECK_DEGRADED = "problem_visibility_check_degraded";
    static final String REASON_CONTEST_SEARCH_RATE_LIMITED = "contest_search_rate_limited";
    static final String REASON_FOREIGN_SUBMISSION_VIEW_DENIED = "foreign_submission_view_denied";

    private ProblemToolSupport() {
    }

    /** Assistance verdict for one running-contest problem; same precedence as PolicySnapshotService. */
    static String assistanceOf(ContestPolicyView.ContestProblemPolicy problem) {
        if (problem.aiPolicyMode() == ContestAiPolicyMode.STRICT) {
            return ASSISTANCE_DENY;
        }
        if (problem.aiPolicyMode() == ContestAiPolicyMode.DISABLED) {
            return ASSISTANCE_FULL_TUTORING;
        }
        return problem.visibility() == ProblemVisibility.PRIVATE ? ASSISTANCE_DENY : ASSISTANCE_HINT_ONLY;
    }

    /** A denial the model sees as the unified payload; the audit record keeps the real reason. */
    static ToolResult<Object> deny(GuardDecisionRecorder recorder, ObjectMapper objectMapper,
                                   ToolExecutionContext context, String toolName, String reasonCode,
                                   ContestPolicyView.ContestProblemPolicy problem, boolean degraded,
                                   Integer latencyMs) {
        ObjectNode detail = objectMapper.createObjectNode();
        detail.put("tool", toolName);
        if (problem != null && problem.problemId() != null) {
            detail.put("problemId", problem.problemId());
            detail.put("visibility", problem.visibility() == null ? null : problem.visibility().name());
            detail.put("aiPolicyMode", problem.aiPolicyMode() == null ? null : problem.aiPolicyMode().name());
        }
        recorder.record(context.turnId(), context.userId(), context.conversationId(),
                GuardLayer.TOOL_ABAC, GuardDecision.REFUSE, matchedRefs(problem), reasonCode, detail,
                degraded, latencyMs);
        return unifiedDenial();
    }

    /** Same unified denial without a contest problem reference (rate limit, foreign submission, scope miss). */
    static ToolResult<Object> deny(GuardDecisionRecorder recorder, ObjectMapper objectMapper,
                                   ToolExecutionContext context, String toolName, String reasonCode,
                                   ObjectNode extraDetail, boolean degraded) {
        ObjectNode detail = objectMapper.createObjectNode();
        detail.put("tool", toolName);
        if (extraDetail != null) {
            detail.setAll(extraDetail);
        }
        recorder.record(context.turnId(), context.userId(), context.conversationId(),
                GuardLayer.TOOL_ABAC, GuardDecision.REFUSE, List.of(), reasonCode, detail, degraded, null);
        return unifiedDenial();
    }

    static ToolResult<Object> unifiedDenial() {
        return ToolResult.failure(null, ToolStatus.POLICY_DENIED, UNIFIED_ERROR_CODE, UNIFIED_NOT_FOUND);
    }

    private static List<GuardDecisionRecorder.MatchedProblemRef> matchedRefs(
            ContestPolicyView.ContestProblemPolicy problem) {
        if (problem == null) {
            return List.of();
        }
        String visibility = problem.visibility() == null ? null : problem.visibility().name();
        String mode = problem.aiPolicyMode() == null ? null : problem.aiPolicyMode().name();
        List<GuardDecisionRecorder.MatchedProblemRef> refs = new ArrayList<>();
        if (problem.occurrences().isEmpty()) {
            refs.add(new GuardDecisionRecorder.MatchedProblemRef(
                    problem.problemId(), null, null, null, visibility, mode));
            return refs;
        }
        for (RunningContestProblemOccurrence occurrence : problem.occurrences()) {
            refs.add(new GuardDecisionRecorder.MatchedProblemRef(
                    problem.problemId(), occurrence.contestId(), occurrence.contestRunId(),
                    occurrence.contestProblemId(), visibility, mode));
        }
        return refs;
    }
}
