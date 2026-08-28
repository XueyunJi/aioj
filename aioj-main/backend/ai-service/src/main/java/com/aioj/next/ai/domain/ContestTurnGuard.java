package com.aioj.next.ai.domain;

import com.aioj.next.ai.config.AiProperties;
import com.aioj.next.contract.ai.AiChatRequest;
import com.aioj.next.contract.ai.ProblemTitleInfo;
import com.aioj.next.contract.contest.ContestAiPolicyMode;
import com.aioj.next.contract.contest.RunningContestParticipation;
import com.aioj.next.contract.contest.RunningContestProblemStatement;
import com.aioj.next.contract.problem.ProblemVisibility;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;

/**
 * Server-side per-turn guard for ACTIVE participants of RUNNING contest runs. Detection
 * input is only the content the user produced this turn (message + clarification answer +
 * code context + selected text; never injected memory or retrieved history). Similarity at
 * or above the match threshold is a direct hit; the gray zone between recall and match
 * thresholds goes to a judge call that only decides whether the turn materially references
 * the problem. Disposition is deterministic: any PRIVATE problem or STRICT run policy
 * refuses the turn, a PUBLIC + DEFAULT hit constrains the turn (code context stripped,
 * policy block injected, response guard armed), and no hit passes. When overlapping guard
 * windows match, audit/usage attribution always prefers a run still in progress; a grace
 * tail participates in matching but only takes attribution when nothing is in progress,
 * and inGrace describes that attributed run's own state. Every evaluated
 * participant turn writes an AI_CONTEST_GUARD_EVALUATED audit row.
 */
@Service
public class ContestTurnGuard {
    private static final Logger log = LoggerFactory.getLogger(ContestTurnGuard.class);
    private static final int STATEMENT_CACHE_LIMIT = 256;
    private static final int DETECTION_INPUT_CHARS = 4000;
    private static final int JUDGE_STATEMENT_CHARS = 2000;
    private static final int JUDGE_INPUT_CHARS = 3000;
    private static final int JUDGE_MAX_TOKENS = 200;
    private static final int POLICY_BLOCK_MAX_CHARS = 1600;
    private static final int POLICY_BLOCK_PROBLEM_LIMIT = 5;

    /** Fixed safe refusal for REFUSE turns; never echoes problem statement content. */
    public static final String REFUSAL_MESSAGE =
            "比赛进行中，AI 助教不能讨论或讲解这场比赛的题目。请专注于自己的解题思路，比赛结束后再来提问。";

    private static final String POLICY_RULES =
            "比赛进行中：仅可提供思路与概念讲解，禁止给出完整或可提交的代码，禁止逐行调试学生代码。";

    private final ProblemServiceClient problemServiceClient;
    private final AiProvider aiProvider;
    private final AiProperties properties;
    private final AiModelCompletionClient completionClient;
    private final AiModelConfigResolver configResolver;
    private final OperationAuditWriter auditWriter;
    private final ObjectMapper objectMapper;
    private final ConcurrentMap<String, List<Double>> statementEmbeddings = new ConcurrentHashMap<>();

    public enum Decision {
        PASS,
        CONSTRAIN,
        REFUSE
    }

    public record MatchedProblem(
            Long problemId,
            ProblemVisibility visibility,
            ContestAiPolicyMode aiPolicyMode,
            String aiPolicyNotes,
            double similarity,
            Long contestId,
            Long contestRunId,
            Long contestProblemId
    ) {
    }

    public record GuardDecision(
            Decision decision,
            AiChatRequest request,
            boolean participant,
            Long contestId,
            Long contestRunId,
            List<MatchedProblem> matchedProblems,
            double maxSimilarity,
            boolean judgeUsed,
            String judgeError,
            boolean inGrace,
            String policyBlock,
            String refusal
    ) {
        public static GuardDecision pass(AiChatRequest request) {
            return new GuardDecision(Decision.PASS, request, false, null, null, List.of(),
                    0.0, false, null, false, null, null);
        }

        public boolean constrained() {
            return decision == Decision.CONSTRAIN;
        }

        public boolean refused() {
            return decision == Decision.REFUSE;
        }

        public Long firstMatchedProblemId() {
            return matchedProblems == null || matchedProblems.isEmpty() ? null : matchedProblems.get(0).problemId();
        }

        public Long firstMatchedContestProblemId() {
            return matchedProblems == null || matchedProblems.isEmpty() ? null : matchedProblems.get(0).contestProblemId();
        }
    }

    private record Candidate(RunningContestProblemStatement statement, double similarity) {
    }

    private record JudgeOutcome(boolean related, String error) {
    }

    public ContestTurnGuard(ProblemServiceClient problemServiceClient,
                            AiProvider aiProvider,
                            AiProperties properties,
                            AiModelCompletionClient completionClient,
                            AiModelConfigResolver configResolver,
                            OperationAuditWriter auditWriter,
                            ObjectMapper objectMapper) {
        this.problemServiceClient = problemServiceClient;
        this.aiProvider = aiProvider;
        this.properties = properties;
        this.completionClient = completionClient;
        this.configResolver = configResolver;
        this.auditWriter = auditWriter;
        this.objectMapper = objectMapper;
    }

    /**
     * Evaluates one chat turn. Non-participants and a disabled guard pass through with zero
     * overhead and no audit; participant turns are always audited. Never throws for a REFUSE
     * decision: the caller persists the blocked turn and raises the client-facing exception.
     */
    public GuardDecision evaluateAndApply(Long userId, AiChatRequest request) {
        AiProperties.ContestLeakGuard config = properties.getContestLeakGuard();
        if (config == null || !config.isEnabled() || userId == null || request == null) {
            return GuardDecision.pass(request);
        }
        List<RunningContestParticipation> participations = problemServiceClient.runningParticipations(userId);
        if (participations.isEmpty()) {
            return GuardDecision.pass(request);
        }
        Instant now = Instant.now();
        RunningContestParticipation attributedParticipation = attributedParticipation(participations, now);
        boolean inGrace = attributedParticipation.endAt() != null && now.isAfter(attributedParticipation.endAt());

        List<RunningContestProblemStatement> statements = problemServiceClient.runningContestProblemStatements(userId)
                .stream()
                .filter(statement -> statement.aiPolicyMode() != ContestAiPolicyMode.DISABLED)
                .toList();
        String detectionInput = statements.isEmpty() ? "" : detectionInput(request);
        if (statements.isEmpty() || detectionInput.isBlank()) {
            recordEvaluated(userId, attributedParticipation, null, Decision.PASS, List.of(), 0.0, false, null, inGrace);
            return participantDecision(Decision.PASS, request, attributedParticipation, null,
                    List.of(), 0.0, false, null, inGrace, null, null);
        }
        Optional<List<Double>> inputVector = embedSafe(truncate(detectionInput, DETECTION_INPUT_CHARS));
        if (inputVector.isEmpty()) {
            recordDegraded(userId, attributedParticipation);
            recordEvaluated(userId, attributedParticipation, null, Decision.PASS, List.of(), 0.0, false, null, inGrace);
            return participantDecision(Decision.PASS, request, attributedParticipation, null,
                    List.of(), 0.0, false, null, inGrace, null, null);
        }

        double maxSimilarity = 0.0;
        List<Candidate> directHits = new ArrayList<>();
        List<Candidate> grayZone = new ArrayList<>();
        for (RunningContestProblemStatement statement : statements) {
            List<Double> statementVector = cachedStatementEmbedding(statement.statement());
            if (statementVector == null) {
                continue;
            }
            double similarity = cosine(inputVector.get(), statementVector);
            maxSimilarity = Math.max(maxSimilarity, similarity);
            if (similarity >= config.getMatchThreshold()) {
                directHits.add(new Candidate(statement, similarity));
            } else if (similarity >= config.getRecallThreshold()) {
                grayZone.add(new Candidate(statement, similarity));
            }
        }
        grayZone.sort(Comparator.comparingDouble(Candidate::similarity).reversed());

        boolean judgeUsed = false;
        String judgeError = null;
        List<Candidate> matched = new ArrayList<>(directHits);
        for (Candidate candidate : grayZone) {
            judgeUsed = true;
            JudgeOutcome outcome = judge(userId, detectionInput, candidate.statement(), candidate.similarity());
            if (outcome.error() != null && judgeError == null) {
                judgeError = outcome.error();
            }
            if (outcome.related()) {
                matched.add(candidate);
            }
        }
        matched.sort(Comparator.comparingDouble(Candidate::similarity).reversed());

        if (matched.isEmpty()) {
            recordEvaluated(userId, attributedParticipation, null, Decision.PASS, List.of(), maxSimilarity, judgeUsed, judgeError, inGrace);
            return participantDecision(Decision.PASS, request, attributedParticipation, null,
                    List.of(), maxSimilarity, judgeUsed, judgeError, inGrace, null, null);
        }
        List<MatchedProblem> matchedProblems = matched.stream().map(this::toMatchedProblem).toList();
        boolean refuse = matchedProblems.stream().anyMatch(problem ->
                problem.visibility() == ProblemVisibility.PRIVATE || problem.aiPolicyMode() == ContestAiPolicyMode.STRICT);
        if (refuse) {
            recordParticipantBlocked(userId, matchedProblems.get(0), judgeUsed, judgeError);
            recordEvaluated(userId, attributedParticipation, matchedProblems.get(0), Decision.REFUSE,
                    matchedProblems, maxSimilarity, judgeUsed, judgeError, inGrace);
            return participantDecision(Decision.REFUSE, request, attributedParticipation, matchedProblems.get(0),
                    matchedProblems, maxSimilarity, judgeUsed, judgeError, inGrace, null, REFUSAL_MESSAGE);
        }
        String policyBlock = renderPolicyBlock(matchedProblems);
        recordEvaluated(userId, attributedParticipation, matchedProblems.get(0), Decision.CONSTRAIN,
                matchedProblems, maxSimilarity, judgeUsed, judgeError, inGrace);
        return participantDecision(Decision.CONSTRAIN, stripCodeContext(request), attributedParticipation, matchedProblems.get(0),
                matchedProblems, maxSimilarity, judgeUsed, judgeError, inGrace, policyBlock, null);
    }

    /**
     * Attribution target for audit/usage when several guard windows overlap: the first
     * run still in progress ({@code now < endAt}) wins; only when no run is in progress
     * does the first listed participation (a grace tail) take the attribution. The
     * problem-service list is already ordered in-progress first, so this is defensive.
     */
    private RunningContestParticipation attributedParticipation(List<RunningContestParticipation> participations, Instant now) {
        return participations.stream()
                .filter(participation -> participation.endAt() == null || now.isBefore(participation.endAt()))
                .findFirst()
                .orElse(participations.get(0));
    }

    private GuardDecision participantDecision(Decision decision, AiChatRequest request,
                                              RunningContestParticipation participation, MatchedProblem firstMatched,
                                              List<MatchedProblem> matchedProblems, double maxSimilarity,
                                              boolean judgeUsed, String judgeError, boolean inGrace,
                                              String policyBlock, String refusal) {
        Long contestId = firstMatched != null ? firstMatched.contestId()
                : participation == null ? null : participation.contestId();
        Long contestRunId = firstMatched != null ? firstMatched.contestRunId()
                : participation == null ? null : participation.contestRunId();
        return new GuardDecision(decision, request, true, contestId, contestRunId, matchedProblems,
                maxSimilarity, judgeUsed, judgeError, inGrace, policyBlock, refusal);
    }

    private MatchedProblem toMatchedProblem(Candidate candidate) {
        RunningContestProblemStatement statement = candidate.statement();
        return new MatchedProblem(
                statement.problemId(),
                statement.visibility() == null ? ProblemVisibility.PUBLIC : statement.visibility(),
                statement.aiPolicyMode() == null ? ContestAiPolicyMode.DEFAULT : statement.aiPolicyMode(),
                statement.aiPolicyNotes(),
                candidate.similarity(),
                statement.contestId(),
                statement.contestRunId(),
                statement.contestProblemId()
        );
    }

    /** Only the content the user produced this turn; injected memory/history never participates. */
    private String detectionInput(AiChatRequest request) {
        StringBuilder input = new StringBuilder();
        appendPart(input, request.message());
        if (request.clarificationAnswer() != null) {
            appendPart(input, request.clarificationAnswer().answerText());
            appendPart(input, request.clarificationAnswer().customText());
        }
        if (request.codeContext() != null) {
            appendPart(input, request.codeContext().code());
        }
        if (request.selectionContext() != null) {
            appendPart(input, request.selectionContext().selectedText());
            appendPart(input, request.selectionContext().selectedMarkdown());
        }
        return input.toString();
    }

    private void appendPart(StringBuilder input, String part) {
        if (part == null || part.isBlank()) {
            return;
        }
        if (input.length() > 0) {
            input.append("\n\n");
        }
        input.append(part.trim());
    }

    /** CONSTRAIN turns lose direct code context; the model may discuss ideas only. */
    private AiChatRequest stripCodeContext(AiChatRequest request) {
        return new AiChatRequest(
                request.conversationId(),
                request.problemId(),
                request.message(),
                request.mode(),
                request.problemContext(),
                null,
                request.clarificationAnswer(),
                request.clientMessageId(),
                stripSelectionCode(request.selectionContext()),
                request.contestContext(),
                request.submissionContext()
        );
    }

    private AiChatRequest.SelectionContext stripSelectionCode(AiChatRequest.SelectionContext selection) {
        if (selection == null || selection.codeContext() == null) {
            return selection;
        }
        return new AiChatRequest.SelectionContext(
                selection.selectionId(),
                selection.conversationId(),
                selection.sourceType(),
                selection.sourceMessageId(),
                selection.sourceRole(),
                selection.selectedText(),
                selection.selectedMarkdown(),
                selection.selectionRange(),
                selection.surroundingContext(),
                null,
                selection.problemContext(),
                selection.uiIntent()
        );
    }

    /**
     * Gray-zone judge: decides only whether the turn materially references the matched
     * problem. Any failure (config missing, call error, unparseable output) conservatively
     * counts as related so the deterministic disposition still applies.
     */
    private JudgeOutcome judge(Long userId, String detectionInput,
                               RunningContestProblemStatement statement, double similarity) {
        AiModelEffectiveConfig judgeConfig = configResolver.effectiveConfig(AiModelScope.TEXT_GENERATION);
        if (judgeConfig == null || !judgeConfig.enabled() || !judgeConfig.hasApiKey()) {
            return new JudgeOutcome(true, "JUDGE_CONFIG_UNAVAILABLE");
        }
        String notes = statement.aiPolicyNotes() == null || statement.aiPolicyNotes().isBlank()
                ? "无"
                : truncate(statement.aiPolicyNotes(), 500);
        String userSection = """
                <policy>
                你是在线评测平台比赛期间的防泄漏审查员。<matched_statement> 是参赛学生正在参加的比赛中一道题目的题面节选（visibility=%s），与学生本轮自行发送给 AI 助教的内容（<turn_input>）向量相似度为 %s。
                该题所属比赛的 AI 策略备注：%s
                只判断一件事：本轮内容是否实质指向这道题（复述题意、索要该题思路/代码/样例解释、针对该题的调试）。
                通用编程问题、讨论其他题目、或与题面巧合相似的措辞都必须判 related=false。
                仅输出严格 JSON：{"related":true/false,"reason":"简短理由"}。
                </policy>
                <matched_statement>
                %s
                </matched_statement>
                <turn_input>
                %s
                </turn_input>
                """.formatted(
                statement.visibility() == null ? ProblemVisibility.PUBLIC : statement.visibility(),
                String.format(java.util.Locale.ROOT, "%.3f", similarity),
                notes,
                truncate(statement.statement(), JUDGE_STATEMENT_CHARS),
                truncate(detectionInput, JUDGE_INPUT_CHARS));
        try {
            AiModelCompletionClient.CompletionResult result = completionClient.complete(
                    judgeConfig,
                    List.of(
                            Map.of("role", "system", "content", "You are a strict content moderator. Respond with a single strict JSON object only."),
                            Map.of("role", "user", "content", userSection)
                    ),
                    0.1,
                    JUDGE_MAX_TOKENS,
                    true
            );
            JsonNode node = objectMapper.readTree(extractJson(result.content()));
            return new JudgeOutcome(node.path("related").asBoolean(true), null);
        } catch (Exception ex) {
            log.warn("Contest turn guard judge failed user={} problem={} error={}",
                    userId, statement.problemId(), ex.toString());
            return new JudgeOutcome(true, "JUDGE_CALL_FAILED");
        }
    }

    private String extractJson(String content) {
        if (content == null) {
            throw new IllegalArgumentException("Empty judge response");
        }
        int start = content.indexOf('{');
        int end = content.lastIndexOf('}');
        if (start < 0 || end <= start) {
            throw new IllegalArgumentException("Judge response has no JSON object");
        }
        return content.substring(start, end + 1);
    }

    /** Renders the [Contest Policy] block injected into the main prompt of a CONSTRAIN turn. */
    private String renderPolicyBlock(List<MatchedProblem> matchedProblems) {
        Map<Long, String> titles = problemTitles(matchedProblems);
        StringBuilder block = new StringBuilder("[Contest Policy]\n");
        block.append("你正在辅导一名比赛进行中的参赛者，本轮内容涉及其参加的比赛题目：\n");
        for (MatchedProblem problem : matchedProblems.stream().limit(POLICY_BLOCK_PROBLEM_LIMIT).toList()) {
            block.append("- 题目 #").append(problem.problemId());
            String title = titles.get(problem.problemId());
            if (title != null && !title.isBlank()) {
                block.append("「").append(title).append("」");
            }
            block.append("（visibility: ").append(problem.visibility())
                    .append("，所属比赛 #").append(problem.contestId()).append("）\n");
            if (problem.aiPolicyNotes() != null && !problem.aiPolicyNotes().isBlank()) {
                block.append("  该题比赛策略备注：").append(problem.aiPolicyNotes().trim()).append('\n');
            }
        }
        block.append("必须严格遵守：").append(POLICY_RULES).append('\n');
        return truncate(block.toString(), POLICY_BLOCK_MAX_CHARS);
    }

    private Map<Long, String> problemTitles(List<MatchedProblem> matchedProblems) {
        try {
            List<Long> ids = matchedProblems.stream().map(MatchedProblem::problemId).distinct().toList();
            Map<Long, String> titles = new HashMap<>();
            for (ProblemTitleInfo info : problemServiceClient.problemTitles(ids)) {
                if (info.id() != null && info.title() != null) {
                    titles.putIfAbsent(info.id(), info.title());
                }
            }
            return titles;
        } catch (RuntimeException ex) {
            log.warn("Contest turn guard problem title lookup failed: {}", ex.toString());
            return Map.of();
        }
    }

    private Optional<List<Double>> embedSafe(String text) {
        try {
            return aiProvider.embed(text);
        } catch (RuntimeException ex) {
            log.warn("Contest turn guard embedding failed: {}", ex.toString());
            return Optional.empty();
        }
    }

    private List<Double> cachedStatementEmbedding(String statement) {
        if (statement == null || statement.isBlank()) {
            return null;
        }
        String key = sha256(statement);
        List<Double> cached = statementEmbeddings.get(key);
        if (cached != null) {
            return cached;
        }
        Optional<List<Double>> vector = embedSafe(statement);
        if (vector.isEmpty()) {
            return null;
        }
        if (statementEmbeddings.size() >= STATEMENT_CACHE_LIMIT) {
            statementEmbeddings.clear();
        }
        statementEmbeddings.put(key, vector.get());
        return vector.get();
    }

    private double cosine(List<Double> left, List<Double> right) {
        int size = Math.min(left.size(), right.size());
        double dot = 0.0;
        double leftNorm = 0.0;
        double rightNorm = 0.0;
        for (int i = 0; i < size; i++) {
            dot += left.get(i) * right.get(i);
            leftNorm += left.get(i) * left.get(i);
            rightNorm += right.get(i) * right.get(i);
        }
        if (leftNorm == 0.0 || rightNorm == 0.0) {
            return 0.0;
        }
        return dot / (Math.sqrt(leftNorm) * Math.sqrt(rightNorm));
    }

    private String truncate(String value, int maxChars) {
        if (value == null) {
            return "";
        }
        return value.length() <= maxChars ? value : value.substring(0, maxChars);
    }

    private String sha256(String value) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            return HexFormat.of().formatHex(digest.digest(value.getBytes(StandardCharsets.UTF_8)));
        } catch (Exception ex) {
            return Integer.toHexString(value.hashCode());
        }
    }

    private void recordEvaluated(Long userId, RunningContestParticipation participation, MatchedProblem firstMatched,
                                 Decision decision, List<MatchedProblem> matchedProblems, double maxSimilarity,
                                 boolean judgeUsed, String judgeError, boolean inGrace) {
        if (auditWriter == null) {
            return;
        }
        try {
            Map<String, Object> summary = new LinkedHashMap<>();
            summary.put("decision", decision.name());
            summary.put("matchedProblemIds", matchedProblems.stream().map(MatchedProblem::problemId).toList());
            summary.put("maxSimilarity", maxSimilarity);
            summary.put("judgeUsed", judgeUsed);
            if (judgeError != null) {
                summary.put("judgeError", judgeError);
            }
            summary.put("inGrace", inGrace);
            Long contestId = firstMatched != null ? firstMatched.contestId()
                    : participation == null ? null : participation.contestId();
            Long contestRunId = firstMatched != null ? firstMatched.contestRunId()
                    : participation == null ? null : participation.contestRunId();
            auditWriter.record(
                    "AI_CONTEST_GUARD_EVALUATED",
                    "CONTEST_AI_POLICY",
                    firstMatched == null ? null : firstMatched.problemId(),
                    decision.name(),
                    summary,
                    userId,
                    contestId,
                    contestRunId,
                    userId
            );
        } catch (RuntimeException ex) {
            log.warn("Contest turn guard evaluation audit failed user={} error={}", userId, ex.toString());
        }
    }

    private void recordParticipantBlocked(Long userId, MatchedProblem matched, boolean judgeUsed, String judgeError) {
        if (auditWriter == null) {
            return;
        }
        try {
            Map<String, Object> summary = new LinkedHashMap<>();
            summary.put("reason", "CONTEST_GUARD_REFUSE");
            summary.put("problemId", matched.problemId());
            summary.put("visibility", matched.visibility().name());
            summary.put("similarity", matched.similarity());
            summary.put("judgeUsed", judgeUsed);
            if (judgeError != null) {
                summary.put("judgeError", judgeError);
            }
            auditWriter.record(
                    "AI_CONTEST_LEAK_PARTICIPANT_BLOCKED",
                    "CONTEST_AI_POLICY",
                    matched.problemId(),
                    "BLOCKED",
                    summary,
                    userId,
                    matched.contestId(),
                    matched.contestRunId(),
                    userId
            );
        } catch (RuntimeException ex) {
            log.warn("Contest turn guard block audit failed user={} problem={} error={}",
                    userId, matched.problemId(), ex.toString());
        }
    }

    private void recordDegraded(Long userId, RunningContestParticipation participation) {
        if (auditWriter == null) {
            return;
        }
        try {
            Map<String, Object> summary = new LinkedHashMap<>();
            summary.put("component", "embedding");
            summary.put("error", "embedding model unavailable");
            auditWriter.record(
                    "AI_CONTEST_GUARD_DEGRADED",
                    "CONTEST_AI_POLICY",
                    null,
                    "DEGRADED",
                    summary,
                    userId,
                    participation == null ? null : participation.contestId(),
                    participation == null ? null : participation.contestRunId(),
                    userId
            );
        } catch (RuntimeException ex) {
            log.warn("Contest turn guard degraded audit failed user={} error={}", userId, ex.toString());
        }
    }
}
