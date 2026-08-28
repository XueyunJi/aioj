package com.aioj.next.ai.agent.problem.tool;

import com.aioj.next.ai.agent.context.DataClassification;
import com.aioj.next.ai.agent.context.TrustLevel;
import com.aioj.next.ai.agent.policy.ContestPolicyView;
import com.aioj.next.ai.agent.policy.GuardDecisionRecorder;
import com.aioj.next.ai.agent.tool.AgentTool;
import com.aioj.next.ai.agent.tool.SourceRef;
import com.aioj.next.ai.agent.tool.ToolAuditLevel;
import com.aioj.next.ai.agent.tool.ToolDescriptor;
import com.aioj.next.ai.agent.tool.ToolExecutionContext;
import com.aioj.next.ai.agent.tool.ToolResult;
import com.aioj.next.ai.agent.tool.ToolRiskLevel;
import com.aioj.next.ai.agent.tool.ToolStatus;
import com.aioj.next.ai.domain.ProblemServiceClient;
import com.aioj.next.common.error.DomainException;
import com.aioj.next.common.error.ErrorCode;
import com.aioj.next.contract.ai.AiProblemContextRequest;
import com.aioj.next.contract.ai.AiProblemContextResponse;
import com.aioj.next.contract.ai.ProblemTitleInfo;
import com.aioj.next.contract.contest.RunningContestProblemOccurrence;
import com.aioj.next.contract.problem.ProblemVisibility;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.springframework.stereotype.Component;

import java.time.Duration;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Built-in tool {@code problem.fetch_allowed_view} (P3-3, C5 design doc §4.3):
 * fetch one problem's allowed view by id. Routing is server-side ABAC on the
 * execution context's contest policy view — the model only supplies the id:
 *
 * <ul>
 *   <li>Participant: only problems of the running-contest snapshot set can be
 *       fetched. DENY-assistance problems (PRIVATE, or STRICT run policy) are
 *       refused; PUBLIC ones return the snapshot statement marked HINT_ONLY
 *       with the run's aiPolicyMode/aiPolicyNotes constraint markers;
 *       DISABLED-mode problems are unrestricted.</li>
 *   <li>Non-participant: normal problem-bank view via the existing
 *       problem-service AI context endpoint, with visibility enforced
 *       (PRIVATE problems are refused; the visibility lookup failing closed is
 *       audited as degraded).</li>
 * </ul>
 *
 * <p>Anti-enumeration (§5.5): "does not exist", "not visible", and "denied by
 * contest policy" all return the identical unified text and status; the real
 * reason only lands in the TOOL_ABAC guard audit.</p>
 */
@Component
public class ProblemFetchAllowedViewTool implements AgentTool {

    private final ProblemServiceClient problemServiceClient;
    private final GuardDecisionRecorder guardDecisionRecorder;
    private final ObjectMapper objectMapper;
    private final ToolDescriptor descriptor;

    public ProblemFetchAllowedViewTool(ProblemServiceClient problemServiceClient,
                                       GuardDecisionRecorder guardDecisionRecorder,
                                       ObjectMapper objectMapper) {
        this.problemServiceClient = problemServiceClient;
        this.guardDecisionRecorder = guardDecisionRecorder;
        this.objectMapper = objectMapper;
        this.descriptor = new ToolDescriptor(
                "problem.fetch_allowed_view",
                "1.0.0",
                "Fetch the allowed view of one problem by its id. Use this for conversations tied to a "
                        + "problem page (fetch by that problemId directly, do not search first), or after "
                        + "problem.search identified a candidate. The server decides which view — if any — "
                        + "the current account may see; a miss always means 'no accessible matching problem'. "
                        + "When the result carries assistanceLevel=HINT_ONLY, give idea-level guidance only "
                        + "and never output complete submittable code.",
                buildSchema(),
                ToolRiskLevel.LOW,
                true,
                true,
                Set.of("AI_CHAT"),
                Set.of(DataClassification.PUBLIC, DataClassification.CONTEST_PUBLIC_ACTIVE,
                        DataClassification.CONTEST_PRIVATE),
                4000,
                Duration.ofSeconds(10),
                ToolAuditLevel.FULL
        );
    }

    @Override
    public ToolDescriptor descriptor() {
        return descriptor;
    }

    @Override
    public ToolResult<Object> execute(ToolExecutionContext context, JsonNode input) {
        long startedNanos = System.nanoTime();
        if (!input.path("problemId").isIntegralNumber()) {
            return ToolResult.failure(null, ToolStatus.SCHEMA_ERROR, "PROBLEM_ID_REQUIRED",
                    "problemId must be an integer");
        }
        long problemId = input.path("problemId").asLong();
        ContestPolicyView policy = context.contestPolicy();
        if (policy != null && policy.isParticipant()) {
            return contestView(context, problemId, policy, startedNanos);
        }
        return practiceView(context, problemId, startedNanos);
    }

    /** Participant path (C5): the contest snapshot set is the only data source. */
    private ToolResult<Object> contestView(ToolExecutionContext context, long problemId,
                                           ContestPolicyView policy, long startedNanos) {
        ContestPolicyView.ContestProblemPolicy problem = policy.problem(problemId);
        if (problem == null) {
            ObjectNode detail = objectMapper.createObjectNode().put("problemId", problemId);
            return ProblemToolSupport.deny(guardDecisionRecorder, objectMapper, context,
                    descriptor.name(), ProblemToolSupport.REASON_PROBLEM_OUTSIDE_CONTEST_SCOPE, detail, false);
        }
        String assistance = ProblemToolSupport.assistanceOf(problem);
        if (ProblemToolSupport.ASSISTANCE_DENY.equals(assistance)) {
            return ProblemToolSupport.deny(guardDecisionRecorder, objectMapper, context,
                    descriptor.name(), ProblemToolSupport.REASON_CONTEST_PROBLEM_VIEW_DENIED, problem,
                    false, elapsedMs(startedNanos));
        }
        Map<String, Object> data = new LinkedHashMap<>();
        data.put("problemId", String.valueOf(problem.problemId()));
        data.put("source", "CONTEST_SNAPSHOT");
        data.put("visibility", problem.visibility() == null ? null : problem.visibility().name());
        data.put("aiPolicyMode", problem.aiPolicyMode() == null ? "DEFAULT" : problem.aiPolicyMode().name());
        data.put("assistanceLevel", assistance);
        if (problem.aiPolicyNotes() != null && !problem.aiPolicyNotes().isBlank()) {
            data.put("aiPolicyNotes", problem.aiPolicyNotes());
        }
        List<Map<String, Object>> occurrences = new ArrayList<>();
        for (RunningContestProblemOccurrence occurrence : problem.occurrences()) {
            Map<String, Object> node = new LinkedHashMap<>();
            node.put("contestId", occurrence.contestId() == null ? null : String.valueOf(occurrence.contestId()));
            node.put("contestRunId", occurrence.contestRunId() == null ? null : String.valueOf(occurrence.contestRunId()));
            node.put("contestProblemId", occurrence.contestProblemId() == null ? null : String.valueOf(occurrence.contestProblemId()));
            occurrences.add(node);
        }
        data.put("occurrences", occurrences);
        String statement = problem.statement() == null ? "" : problem.statement();
        data.put("statement", statement);
        data.put("statementChars", statement.length());
        DataClassification classification = problem.visibility() == ProblemVisibility.PRIVATE
                ? DataClassification.CONTEST_PRIVATE
                : DataClassification.CONTEST_PUBLIC_ACTIVE;
        return ToolResult.success(null, data,
                List.of(new SourceRef("CONTEST_PROBLEM", String.valueOf(problem.problemId()))),
                classification, TrustLevel.USER_PROVIDED);
    }

    /**
     * Non-participant path: normal problem-bank view. Visibility is verified
     * first (the AI context endpoint serves staff-grade data and does not
     * filter PRIVATE itself); an unverifiable visibility fails closed (Q5).
     */
    private ToolResult<Object> practiceView(ToolExecutionContext context, long problemId, long startedNanos) {
        ProblemTitleInfo title = null;
        List<ProblemTitleInfo> titles = problemServiceClient.problemTitles(List.of(problemId));
        if (titles != null) {
            for (ProblemTitleInfo candidate : titles) {
                if (candidate != null && candidate.id() != null && candidate.id() == problemId) {
                    title = candidate;
                    break;
                }
            }
        }
        if (title == null) {
            // Empty title lookup: either the problem does not exist, or the lookup
            // degraded. The context call distinguishes the two — NOT_FOUND means a
            // plain miss (no guard audit; ai_tool_calls already records the probe),
            // a successful fetch means the visibility check is unverifiable.
            try {
                problemServiceClient.aiProblemContext(problemContextRequest(context.userId(), problemId));
            } catch (DomainException ex) {
                if (ex.errorCode() == ErrorCode.NOT_FOUND) {
                    return unifiedMiss();
                }
                return ToolResult.failure(null, ToolStatus.EXECUTION_ERROR, "PROBLEM_CONTEXT_FAILED",
                        "problem context lookup failed");
            }
            return ProblemToolSupport.deny(guardDecisionRecorder, objectMapper, context,
                    descriptor.name(), ProblemToolSupport.REASON_PROBLEM_VISIBILITY_CHECK_DEGRADED,
                    objectMapper.createObjectNode().put("problemId", problemId), true);
        }
        if (title.visibility() == ProblemVisibility.PRIVATE) {
            return ProblemToolSupport.deny(guardDecisionRecorder, objectMapper, context,
                    descriptor.name(), ProblemToolSupport.REASON_PROBLEM_NOT_VISIBLE,
                    objectMapper.createObjectNode().put("problemId", problemId), false);
        }
        AiProblemContextResponse problem;
        try {
            problem = problemServiceClient.aiProblemContext(problemContextRequest(context.userId(), problemId));
        } catch (DomainException ex) {
            if (ex.errorCode() == ErrorCode.NOT_FOUND) {
                return unifiedMiss();
            }
            return ToolResult.failure(null, ToolStatus.EXECUTION_ERROR, "PROBLEM_CONTEXT_FAILED",
                    "problem context lookup failed");
        }
        if (problem == null) {
            return unifiedMiss();
        }
        Map<String, Object> data = new LinkedHashMap<>();
        data.put("problemId", String.valueOf(problemId));
        data.put("source", "PROBLEM");
        data.put("assistanceLevel", ProblemToolSupport.ASSISTANCE_FULL_TUTORING);
        data.put("title", problem.title());
        data.put("difficulty", problem.difficulty());
        data.put("tags", problem.tags() == null ? List.of() : problem.tags());
        data.put("statement", problem.statement());
        data.put("statementSummary", problem.statementSummary());
        List<Map<String, Object>> samples = new ArrayList<>();
        if (problem.samples() != null) {
            for (var sample : problem.samples()) {
                Map<String, Object> node = new LinkedHashMap<>();
                node.put("input", sample.input());
                node.put("expectedOutput", sample.expectedOutput());
                samples.add(node);
            }
        }
        data.put("samples", samples);
        data.put("timeLimitMillis", problem.timeLimitMillis());
        data.put("memoryLimitKb", problem.memoryLimitKb());
        return ToolResult.success(null, data,
                List.of(new SourceRef("PROBLEM", String.valueOf(problemId))),
                DataClassification.PUBLIC, TrustLevel.USER_PROVIDED);
    }

    private AiProblemContextRequest problemContextRequest(long userId, long problemId) {
        return new AiProblemContextRequest(userId, problemId, null, null, null, "AI_CHAT");
    }

    /** Plain miss: same payload as any denial, but not a guard decision. */
    private ToolResult<Object> unifiedMiss() {
        return ProblemToolSupport.unifiedDenial();
    }

    private int elapsedMs(long startedNanos) {
        return (int) ((System.nanoTime() - startedNanos) / 1_000_000L);
    }

    private JsonNode buildSchema() {
        ObjectNode schema = objectMapper.createObjectNode();
        schema.put("type", "object");
        schema.put("additionalProperties", false);
        schema.putArray("required").add("problemId");
        ObjectNode properties = schema.putObject("properties");
        ObjectNode problemId = properties.putObject("problemId");
        problemId.put("type", "integer");
        problemId.put("description", "The problem id to fetch the allowed view for.");
        return schema;
    }
}
