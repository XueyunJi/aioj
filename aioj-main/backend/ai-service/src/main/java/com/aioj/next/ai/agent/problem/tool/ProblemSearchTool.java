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
import com.aioj.next.ai.config.AiProperties;
import com.aioj.next.contract.contest.RunningContestProblemOccurrence;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

import java.time.Duration;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Built-in tool {@code problem.search} (P3-3, C5 design doc §4.3): keyword
 * search over the caller's deduplicated running-contest problem snapshot set —
 * never the whole problem bank. The tool is not even offered to
 * non-participants (AgentRuntime filters it); a missing/empty contest policy
 * view simply yields an empty search space.
 *
 * <p>Anti-enumeration (§5.5): problems whose assistance verdict is DENY
 * (PRIVATE, or STRICT run policy) never appear in hits, so the search cannot
 * be used as a "does the private statement contain X" oracle. A per-user
 * in-memory sliding-window rate limit
 * ({@code aioj.ai.agent-core.contest-search.rate-limit.*}) blocks bursts of
 * probing calls; every rate-limit refusal is audited as TOOL_ABAC and answered
 * with the same unified text as any other miss.</p>
 */
@Component
public class ProblemSearchTool implements AgentTool {

    private static final int MAX_QUERY_CHARS = 100;
    private static final int DEFAULT_TOP_K = 5;
    private static final int MAX_TOP_K = 10;
    private static final int EXCERPT_RADIUS = 120;
    private static final int MAX_TRACKED_USERS = 10_000;

    private final GuardDecisionRecorder guardDecisionRecorder;
    private final ObjectMapper objectMapper;
    private final long windowMillis;
    private final int maxCallsPerWindow;
    private final Map<Long, Deque<Long>> callTimestamps = new ConcurrentHashMap<>();
    private final ToolDescriptor descriptor;

    @Autowired
    public ProblemSearchTool(GuardDecisionRecorder guardDecisionRecorder, AiProperties properties,
                             ObjectMapper objectMapper) {
        this(guardDecisionRecorder, objectMapper,
                properties.getAgentCore().getContestSearch().getRateLimit().getWindowSeconds() * 1000L,
                properties.getAgentCore().getContestSearch().getRateLimit().getMaxCallsPerWindow());
    }

    ProblemSearchTool(GuardDecisionRecorder guardDecisionRecorder, ObjectMapper objectMapper,
                      long windowMillis, int maxCallsPerWindow) {
        this.guardDecisionRecorder = guardDecisionRecorder;
        this.objectMapper = objectMapper;
        this.windowMillis = Math.max(1000L, windowMillis);
        this.maxCallsPerWindow = Math.max(1, maxCallsPerWindow);
        this.descriptor = new ToolDescriptor(
                "problem.search",
                "1.0.0",
                "Search the problems of your currently running contest(s) by keyword, matched "
                        + "case-insensitively against title and statement. Only call this while helping "
                        + "with an ongoing contest problem; for a conversation already tied to a problem "
                        + "use problem.fetch_allowed_view with that problemId instead. Returns a minimal "
                        + "projection (id, policy markers, excerpt) — AI-restricted problems never appear "
                        + "in the result at all.",
                buildSchema(),
                ToolRiskLevel.LOW,
                true,
                true,
                Set.of("AI_CHAT"),
                Set.of(DataClassification.CONTEST_PUBLIC_ACTIVE),
                2000,
                Duration.ofSeconds(5),
                ToolAuditLevel.FULL
        );
    }

    @Override
    public ToolDescriptor descriptor() {
        return descriptor;
    }

    @Override
    public ToolResult<Object> execute(ToolExecutionContext context, JsonNode input) {
        if (!rateLimitAllowed(context.userId())) {
            return ProblemToolSupport.deny(guardDecisionRecorder, objectMapper, context,
                    descriptor.name(), ProblemToolSupport.REASON_CONTEST_SEARCH_RATE_LIMITED, null, false);
        }
        String query = input.path("query").isTextual() ? input.path("query").asText().trim() : "";
        if (query.length() > MAX_QUERY_CHARS) {
            query = query.substring(0, MAX_QUERY_CHARS);
        }
        int topK = input.path("topK").isIntegralNumber()
                ? Math.min(MAX_TOP_K, Math.max(1, input.path("topK").asInt()))
                : DEFAULT_TOP_K;
        ContestPolicyView policy = context.contestPolicy();
        List<ContestPolicyView.ContestProblemPolicy> searchable = policy == null
                ? List.of()
                : policy.contestProblems().values().stream()
                        .filter(problem -> !ProblemToolSupport.ASSISTANCE_DENY.equals(
                                ProblemToolSupport.assistanceOf(problem)))
                        .toList();
        String loweredQuery = query.toLowerCase(Locale.ROOT);
        List<Map<String, Object>> hits = new ArrayList<>();
        for (ContestPolicyView.ContestProblemPolicy problem : searchable) {
            String statement = problem.statement() == null ? "" : problem.statement();
            if (!loweredQuery.isEmpty() && !statement.toLowerCase(Locale.ROOT).contains(loweredQuery)) {
                continue;
            }
            Map<String, Object> hit = new LinkedHashMap<>();
            hit.put("problemId", String.valueOf(problem.problemId()));
            hit.put("visibility", problem.visibility() == null ? null : problem.visibility().name());
            hit.put("aiPolicyMode", problem.aiPolicyMode() == null ? "DEFAULT" : problem.aiPolicyMode().name());
            hit.put("assistanceLevel", ProblemToolSupport.assistanceOf(problem));
            if (problem.aiPolicyNotes() != null && !problem.aiPolicyNotes().isBlank()) {
                hit.put("aiPolicyNotes", problem.aiPolicyNotes());
            }
            RunningContestProblemOccurrence occurrence = problem.firstOccurrence();
            if (occurrence != null) {
                hit.put("contestId", occurrence.contestId() == null ? null : String.valueOf(occurrence.contestId()));
                hit.put("contestRunId", occurrence.contestRunId() == null ? null : String.valueOf(occurrence.contestRunId()));
                hit.put("contestProblemId", occurrence.contestProblemId() == null ? null : String.valueOf(occurrence.contestProblemId()));
            }
            hit.put("excerpt", loweredQuery.isEmpty() ? "" : excerpt(statement, query));
            hits.add(hit);
            if (hits.size() >= topK) {
                break;
            }
        }
        Map<String, Object> data = new LinkedHashMap<>();
        data.put("hits", hits);
        data.put("hitCount", hits.size());
        data.put("query", query);
        data.put("topK", topK);
        data.put("scope", "RUNNING_CONTEST_SNAPSHOT");
        List<SourceRef> sources = hits.stream()
                .map(hit -> new SourceRef("CONTEST_PROBLEM", String.valueOf(hit.get("problemId"))))
                .toList();
        return ToolResult.success(null, data, sources,
                DataClassification.CONTEST_PUBLIC_ACTIVE, TrustLevel.USER_PROVIDED);
    }

    /** Per-user sliding window; returns false when the call exceeds the configured burst. */
    private boolean rateLimitAllowed(long userId) {
        if (callTimestamps.size() >= MAX_TRACKED_USERS && !callTimestamps.containsKey(userId)) {
            callTimestamps.clear();
        }
        Deque<Long> timestamps = callTimestamps.computeIfAbsent(userId, key -> new ArrayDeque<>());
        synchronized (timestamps) {
            long now = System.currentTimeMillis();
            while (!timestamps.isEmpty() && now - timestamps.peekFirst() >= windowMillis) {
                timestamps.pollFirst();
            }
            if (timestamps.size() >= maxCallsPerWindow) {
                return false;
            }
            timestamps.addLast(now);
            return true;
        }
    }

    private String excerpt(String statement, String query) {
        String lowered = statement.toLowerCase(Locale.ROOT);
        int index = lowered.indexOf(query.toLowerCase(Locale.ROOT));
        if (index < 0) {
            return statement.length() <= EXCERPT_RADIUS * 2 ? statement : statement.substring(0, EXCERPT_RADIUS * 2) + "…";
        }
        int start = Math.max(0, index - EXCERPT_RADIUS);
        int end = Math.min(statement.length(), index + query.length() + EXCERPT_RADIUS);
        StringBuilder excerpt = new StringBuilder();
        if (start > 0) {
            excerpt.append('…');
        }
        excerpt.append(statement, start, end);
        if (end < statement.length()) {
            excerpt.append('…');
        }
        return excerpt.toString();
    }

    private JsonNode buildSchema() {
        ObjectNode schema = objectMapper.createObjectNode();
        schema.put("type", "object");
        schema.put("additionalProperties", false);
        schema.putArray("required").add("query");
        ObjectNode properties = schema.putObject("properties");
        ObjectNode query = properties.putObject("query");
        query.put("type", "string");
        query.put("description", "Keyword matched case-insensitively against contest problem statements.");
        query.put("minLength", 1);
        query.put("maxLength", MAX_QUERY_CHARS);
        ObjectNode topK = properties.putObject("topK");
        topK.put("type", "integer");
        topK.put("description", "Maximum number of hits to return (default 5, max 10).");
        topK.put("minimum", 1);
        topK.put("maximum", MAX_TOP_K);
        return schema;
    }
}
