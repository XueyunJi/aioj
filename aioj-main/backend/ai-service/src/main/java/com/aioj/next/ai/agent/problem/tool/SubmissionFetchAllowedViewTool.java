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
import com.aioj.next.contract.ai.AiProblemContextResponse;
import com.aioj.next.contract.ai.AiSubmissionCaseContext;
import com.aioj.next.contract.ai.AiSubmissionContextRequest;
import com.aioj.next.contract.ai.AiSubmissionContextResponse;
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
 * Built-in tool {@code submission.fetch_allowed_view} (P3-3, C5 design doc
 * §4.2/§4.3): fetch the caller's OWN submission view via the existing
 * problem-service AI submission context endpoint. The server-side identity
 * (execution-context userId) is the only owner reference — a submissionId
 * belonging to someone else is refused with the same unified text as a
 * missing problem (anti-enumeration §5.5) and audited as TOOL_ABAC.
 *
 * <p>Contest safety: the endpoint already strips code for contest-active
 * submissions (codeAllowedToModel=false). Additionally, when the caller is a
 * participant and the submission's problem is DENY-assistance in the current
 * contest policy view (PRIVATE or STRICT), the embedded problem statement is
 * withheld — a submission view must not become a back door to a private
 * contest statement the problem tool would refuse.</p>
 */
@Component
public class SubmissionFetchAllowedViewTool implements AgentTool {

    private final ProblemServiceClient problemServiceClient;
    private final GuardDecisionRecorder guardDecisionRecorder;
    private final ObjectMapper objectMapper;
    private final ToolDescriptor descriptor;

    public SubmissionFetchAllowedViewTool(ProblemServiceClient problemServiceClient,
                                          GuardDecisionRecorder guardDecisionRecorder,
                                          ObjectMapper objectMapper) {
        this.problemServiceClient = problemServiceClient;
        this.guardDecisionRecorder = guardDecisionRecorder;
        this.objectMapper = objectMapper;
        this.descriptor = new ToolDescriptor(
                "submission.fetch_allowed_view",
                "1.0.0",
                "Fetch the allowed view of one of your own submissions by its id: verdict, judge message, "
                        + "score, per-case results, and your code when policy allows it. Use when the user "
                        + "asks why a submission failed or how to improve it. Only the caller's own "
                        + "submissions are accessible; anything else returns 'no accessible matching problem'. "
                        + "Never reveal hidden test data — the view never contains it.",
                buildSchema(),
                ToolRiskLevel.LOW,
                true,
                true,
                Set.of("AI_CHAT"),
                Set.of(DataClassification.USER_PRIVATE),
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
        if (!input.path("submissionId").isIntegralNumber()) {
            return ToolResult.failure(null, ToolStatus.SCHEMA_ERROR, "SUBMISSION_ID_REQUIRED",
                    "submissionId must be an integer");
        }
        long submissionId = input.path("submissionId").asLong();
        AiSubmissionContextResponse submission;
        try {
            submission = problemServiceClient.aiSubmissionContext(new AiSubmissionContextRequest(
                    context.userId(), submissionId, null, null, null, null, "AI_CHAT"));
        } catch (DomainException ex) {
            if (ex.errorCode() == ErrorCode.FORBIDDEN) {
                return ProblemToolSupport.deny(guardDecisionRecorder, objectMapper, context,
                        descriptor.name(), ProblemToolSupport.REASON_FOREIGN_SUBMISSION_VIEW_DENIED,
                        objectMapper.createObjectNode().put("submissionId", submissionId), false);
            }
            if (ex.errorCode() == ErrorCode.NOT_FOUND) {
                // Same unified payload as a denial, but a plain miss is not a guard
                // decision — the ai_tool_calls row already records the probe.
                return ProblemToolSupport.unifiedDenial();
            }
            if (ex.errorCode() == ErrorCode.BAD_REQUEST) {
                return ToolResult.failure(null, ToolStatus.EXECUTION_ERROR, "SUBMISSION_NOT_READY",
                        ex.getMessage() == null ? "submission is not available yet" : ex.getMessage());
            }
            return ToolResult.failure(null, ToolStatus.EXECUTION_ERROR, "SUBMISSION_CONTEXT_FAILED",
                    "submission context lookup failed");
        }
        if (submission == null) {
            return ProblemToolSupport.unifiedDenial();
        }

        boolean withholdStatement = withholdContestStatement(context, submission);
        Map<String, Object> data = new LinkedHashMap<>();
        data.put("submissionId", String.valueOf(submissionId));
        data.put("problemId", submission.problemId() == null ? null : String.valueOf(submission.problemId()));
        data.put("scope", submission.scope());
        data.put("contestActive", submission.contestActive());
        data.put("language", submission.language());
        data.put("status", submission.status());
        data.put("judgeMessage", submission.judgeMessage());
        data.put("stdoutExcerpt", submission.stdoutExcerpt());
        data.put("stderrExcerpt", submission.stderrExcerpt());
        data.put("exitStatus", submission.exitStatus());
        data.put("runTimeMillis", submission.runTimeMillis());
        data.put("memoryKb", submission.memoryKb());
        data.put("score", submission.score());
        data.put("maxScore", submission.maxScore());
        data.put("codeAllowedToModel", submission.codeAllowedToModel());
        if (submission.codeAllowedToModel() && submission.codeText() != null) {
            data.put("codeText", submission.codeText());
        }
        data.put("codeHash", submission.codeHash());
        List<Map<String, Object>> cases = new ArrayList<>();
        if (submission.caseResults() != null) {
            for (AiSubmissionCaseContext caseResult : submission.caseResults()) {
                Map<String, Object> node = new LinkedHashMap<>();
                node.put("caseIndex", caseResult.caseIndex());
                node.put("caseName", caseResult.caseName());
                node.put("status", caseResult.status());
                node.put("score", caseResult.score());
                node.put("maxScore", caseResult.maxScore());
                node.put("timeMillis", caseResult.timeMillis());
                node.put("memoryKb", caseResult.memoryKb());
                node.put("message", caseResult.message());
                cases.add(node);
            }
        }
        data.put("caseResults", cases);
        data.put("problem", problemView(submission.problemContext(), withholdStatement));
        data.put("submittedAt", submission.submittedAt() == null ? null : submission.submittedAt().toString());
        data.put("judgedAt", submission.judgedAt() == null ? null : submission.judgedAt().toString());
        if (submission.policyMessage() != null && !submission.policyMessage().isBlank()) {
            data.put("policyMessage", submission.policyMessage());
        }
        return ToolResult.success(null, data,
                List.of(new SourceRef("SUBMISSION", String.valueOf(submissionId))),
                DataClassification.USER_PRIVATE, TrustLevel.USER_PROVIDED);
    }

    /** Withhold the embedded statement when the problem is DENY-assistance in the caller's contest policy view. */
    private boolean withholdContestStatement(ToolExecutionContext context, AiSubmissionContextResponse submission) {
        ContestPolicyView policy = context.contestPolicy();
        if (policy == null || !policy.isParticipant() || submission.problemId() == null) {
            return false;
        }
        ContestPolicyView.ContestProblemPolicy problem = policy.problem(submission.problemId());
        return problem != null
                && ProblemToolSupport.ASSISTANCE_DENY.equals(ProblemToolSupport.assistanceOf(problem));
    }

    private Map<String, Object> problemView(AiProblemContextResponse problem, boolean withholdStatement) {
        Map<String, Object> data = new LinkedHashMap<>();
        if (problem == null) {
            return data;
        }
        data.put("problemId", problem.problemId() == null ? null : String.valueOf(problem.problemId()));
        data.put("title", problem.title());
        data.put("difficulty", problem.difficulty());
        data.put("tags", problem.tags() == null ? List.of() : problem.tags());
        data.put("source", problem.source());
        if (withholdStatement) {
            data.put("statementWithheld", true);
        } else {
            data.put("statement", problem.statement());
            data.put("statementSummary", problem.statementSummary());
        }
        return data;
    }

    private JsonNode buildSchema() {
        ObjectNode schema = objectMapper.createObjectNode();
        schema.put("type", "object");
        schema.put("additionalProperties", false);
        schema.putArray("required").add("submissionId");
        ObjectNode properties = schema.putObject("properties");
        ObjectNode submissionId = properties.putObject("submissionId");
        submissionId.put("type", "integer");
        submissionId.put("description", "The id of your own submission to fetch the allowed view for.");
        return schema;
    }
}
