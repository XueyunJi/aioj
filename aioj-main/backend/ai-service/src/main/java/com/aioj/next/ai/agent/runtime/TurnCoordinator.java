package com.aioj.next.ai.agent.runtime;

import com.aioj.next.ai.agent.AgentChatFacade.TurnHandle;
import com.aioj.next.ai.agent.AgentChatFacade.TurnResult;
import com.aioj.next.ai.agent.context.BootstrapContextBuilder;
import com.aioj.next.ai.agent.context.ContextSection;
import com.aioj.next.ai.agent.context.ContextSectionType;
import com.aioj.next.ai.agent.context.TrustLevel;
import com.aioj.next.ai.agent.digest.TurnDigestInput;
import com.aioj.next.ai.agent.digest.TurnDigestService;
import com.aioj.next.ai.agent.model.CallProfile;
import com.aioj.next.ai.agent.model.ModelUsage;
import com.aioj.next.ai.agent.policy.ContestParticipationService;
import com.aioj.next.ai.agent.policy.ContestPolicyView;
import com.aioj.next.ai.agent.policy.GuardDecisionRecorder;
import com.aioj.next.ai.agent.policy.GuardLayer;
import com.aioj.next.ai.agent.policy.ParticipantStatus;
import com.aioj.next.ai.agent.policy.PolicySnapshotService;
import com.aioj.next.ai.agent.guard.ContestOutputGuard;
import com.aioj.next.ai.agent.guard.GuardVerdict;
import com.aioj.next.ai.agent.guard.ProblemFingerprintMatcher;
import com.aioj.next.ai.agent.telemetry.ContestAiAssistanceTelemetryService;
import com.aioj.next.contract.contest.RunningContestProblemStatement;
import com.aioj.next.contract.contest.RunningContestParticipation;
import com.fasterxml.jackson.databind.node.JsonNodeFactory;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.aioj.next.ai.agent.understanding.TurnUnderstandingService;
import com.aioj.next.ai.config.AiProperties;
import com.aioj.next.ai.domain.AiChatContext;
import com.aioj.next.ai.domain.AiCompletion;
import com.aioj.next.ai.domain.AiConversationService;
import com.aioj.next.ai.domain.AiQuotaService;
import com.aioj.next.ai.domain.AiTurnService;
import com.aioj.next.ai.domain.response.AiAssistantResponseNormalizer;
import com.aioj.next.ai.persistence.entity.AiConversationEntity;
import com.aioj.next.ai.persistence.entity.AiTurnEntity;
import com.aioj.next.common.error.DomainException;
import com.aioj.next.common.error.ErrorCode;
import com.aioj.next.contract.ai.AiChatMessageResponse;
import com.aioj.next.contract.ai.AiChatRequest;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Executor;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Consumer;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

/**
 * Turn mechanics for the Agent Core V3 pipeline (design doc §2/§7): idempotent
 * begin/resume on ai_turns, quota accounting, message lifecycle, in-flight
 * dedup, and timeout finalization — the same outer contract the legacy
 * AiChatTurnService honored, but with all legacy hooks (context service,
 * contest guard, memory, response policy guard) removed. Those capabilities
 * return as V3 components in their own phases.
 */
@Service
public class TurnCoordinator {

    private static final Logger log = LoggerFactory.getLogger(TurnCoordinator.class);

    /**
     * P3-5: server-authored safe refusal persisted as the assistant content when the
     * one allowed regeneration is still intercepted. Polite, names no policy details.
     */
    static final String CONTEST_SAFE_REFUSAL_TEXT =
            "抱歉，该内容涉及正在进行中的比赛，我暂时不能提供解答或完整代码。"
                    + "我可以为你提供学习方向上的建议，例如相关算法的思路讲解或同类练习题的练习方法。";

    private final AgentRuntime agentRuntime;
    private final BootstrapContextBuilder bootstrapBuilder;
    private final PolicySnapshotService policySnapshotService;
    private final ContestParticipationService participationService;
    private final ProblemFingerprintMatcher fingerprintMatcher;
    private final GuardDecisionRecorder guardDecisionRecorder;
    private final ContestOutputGuard contestOutputGuard;
    private final AiQuotaService aiQuotaService;
    private final AiConversationService aiConversationService;
    private final AiAssistantResponseNormalizer responseNormalizer;
    private final AiTurnService aiTurnService;
    private final TurnDigestService turnDigestService;
    private final TurnUnderstandingService turnUnderstandingService;
    private final AiProperties properties;
    private final Executor executor;
    private final long turnTimeoutMs;
    /**
     * Optional observation-only contest assistance ledger. Setter injection keeps the
     * package-visible constructor stable for the focused coordinator tests while
     * ensuring no telemetry failure can affect the V3 Agent outcome.
     */
    private ContestAiAssistanceTelemetryService contestAssistanceTelemetryService;
    /**
     * In-process registry of in-flight turns so a duplicate submission or an SSE reconnect can
     * attach to the running turn instead of regenerating. The ai_turns row remains the
     * consistency source; a registry miss falls back to polling the turn row.
     */
    private final Map<String, CompletableFuture<TurnResult>> inFlightTurns = new ConcurrentHashMap<>();

    @Autowired
    public TurnCoordinator(
            AgentRuntime agentRuntime,
            BootstrapContextBuilder bootstrapBuilder,
            PolicySnapshotService policySnapshotService,
            ContestParticipationService participationService,
            ProblemFingerprintMatcher fingerprintMatcher,
            GuardDecisionRecorder guardDecisionRecorder,
            ContestOutputGuard contestOutputGuard,
            AiQuotaService aiQuotaService,
            AiConversationService aiConversationService,
            AiAssistantResponseNormalizer responseNormalizer,
            AiTurnService aiTurnService,
            TurnDigestService turnDigestService,
            TurnUnderstandingService turnUnderstandingService,
            AiProperties properties,
            @Qualifier("aiChatTurnExecutor") Executor executor
    ) {
        this(agentRuntime, bootstrapBuilder, policySnapshotService, participationService,
                fingerprintMatcher, guardDecisionRecorder, contestOutputGuard, aiQuotaService, aiConversationService,
                responseNormalizer, aiTurnService, turnDigestService, turnUnderstandingService, properties, executor,
                properties.getAgentCore().getTurnTimeoutMs());
    }

    TurnCoordinator(
            AgentRuntime agentRuntime,
            BootstrapContextBuilder bootstrapBuilder,
            PolicySnapshotService policySnapshotService,
            ContestParticipationService participationService,
            ProblemFingerprintMatcher fingerprintMatcher,
            GuardDecisionRecorder guardDecisionRecorder,
            ContestOutputGuard contestOutputGuard,
            AiQuotaService aiQuotaService,
            AiConversationService aiConversationService,
            AiAssistantResponseNormalizer responseNormalizer,
            AiTurnService aiTurnService,
            TurnDigestService turnDigestService,
            TurnUnderstandingService turnUnderstandingService,
            AiProperties properties,
            Executor executor,
            long turnTimeoutMs
    ) {
        this.agentRuntime = agentRuntime;
        this.bootstrapBuilder = bootstrapBuilder;
        this.policySnapshotService = policySnapshotService;
        this.participationService = participationService;
        this.fingerprintMatcher = fingerprintMatcher;
        this.guardDecisionRecorder = guardDecisionRecorder;
        this.contestOutputGuard = contestOutputGuard;
        this.aiQuotaService = aiQuotaService;
        this.aiConversationService = aiConversationService;
        this.responseNormalizer = responseNormalizer;
        this.aiTurnService = aiTurnService;
        this.turnDigestService = turnDigestService;
        this.turnUnderstandingService = turnUnderstandingService;
        this.properties = properties;
        this.executor = executor;
        this.turnTimeoutMs = Math.max(1L, turnTimeoutMs);
    }

    @Autowired(required = false)
    public void setContestAssistanceTelemetryService(
            ContestAiAssistanceTelemetryService contestAssistanceTelemetryService
    ) {
        this.contestAssistanceTelemetryService = contestAssistanceTelemetryService;
    }

    /**
     * L3 first layer (design doc §5.3): fingerprint the raw user message against
     * the turn's constrained running-contest problems. Participants only;
     * every evaluation (including PASS) is audited.
     */
    private GuardVerdict matchUserMessage(long userId, String turnId, String conversationId,
                                          String message, PolicySnapshotService.PolicySnapshot snapshot) {
        if (snapshot == null || snapshot.participantStatus() == ParticipantStatus.NON_PARTICIPANT) {
            return null;
        }
        List<RunningContestProblemStatement> candidates = snapshot.constrainedProblems();
        if (candidates.isEmpty()) {
            return null;
        }
        long startedNanos = System.nanoTime();
        GuardVerdict verdict = fingerprintMatcher.match(message, candidates);
        int latencyMs = (int) ((System.nanoTime() - startedNanos) / 1_000_000L);
        ObjectNode detail = JsonNodeFactory.instance.objectNode()
                .put("candidateCount", candidates.size())
                .put("maxScore", verdict.maxScore());
        guardDecisionRecorder.record(turnId, userId, conversationId,
                GuardLayer.L3_FINGERPRINT_MSG, verdict.decision(),
                verdict.matchedProblems(), verdict.reasonCode(), detail, false, latencyMs);
        return verdict;
    }

    /**
     * P3-5 (§5.4): the one safe-regeneration run gets the original sections plus
     * one trailing server system section naming only the intercepted reason
     * category — the violating draft itself is never shown back to the model.
     * CONTEST_OUTPUT_GUARD_RETRY rides inside the combined system text, right
     * after any L3 match block (priority 26 vs 25).
     */
    private List<ContextSection> sectionsWithOutputGuardRetry(List<ContextSection> sections, String reasonCode) {
        List<ContextSection> out = new ArrayList<>(sections);
        out.add(ContextSection.text(ContextSectionType.CONTEST_OUTPUT_GUARD_RETRY, 26, true,
                TrustLevel.SERVER_AUTHORITATIVE, renderOutputGuardRetryDirective(reasonCode)));
        return out;
    }

    private String renderOutputGuardRetryDirective(String reasonCode) {
        return "[Contest Output Guard — server safety check intercepted the previous draft; regenerate]\n"
                + "Your previous draft reply for this turn was intercepted by a server-side contest output "
                + "safety check (reason category: " + outputGuardReasonCategory(reasonCode) + "). "
                + "The intercepted draft is withheld and must not be restated or quoted.\n"
                + "Regenerate the reply under these rules:\n"
                + "- Private contest problems: refuse to discuss their content, solution, or code; decline politely.\n"
                + "- Public contest problems of a running contest: hints and idea-level guidance only; "
                + "never output complete submittable code.\n"
                + "- Never reveal hidden test data, judging points, or evaluation details.\n"
                + "[/Contest Output Guard]";
    }

    private String outputGuardReasonCategory(String reasonCode) {
        return switch (reasonCode == null ? "" : reasonCode) {
            case ContestOutputGuard.REASON_FULL_CODE -> "complete submittable code disclosure";
            case ContestOutputGuard.REASON_PRIVATE_STATEMENT -> "private contest statement leakage";
            case ContestOutputGuard.REASON_HIDDEN_TEST -> "hidden test information leakage";
            case ContestOutputGuard.REASON_GUARD_ERROR -> "output safety check failure (fail-closed)";
            default -> "contest output policy violation";
        };
    }

    public TurnHandle start(Long userId, AiChatRequest request) {
        AiTurnEntity turn = null;
        boolean ownsTurn = false;
        try {
            aiQuotaService.assertAvailable(userId);
            AiConversationEntity conversation = aiConversationService.resolveForWrite(userId, request);
            AiTurnService.BeginTurnOutcome beginOutcome = aiTurnService.beginTurn(conversation.getId(), clientMessageId(request));
            turn = beginOutcome.turn();
            if (!beginOutcome.created()) {
                // Duplicate submission (retry/double-click/SSE reconnect with the same
                // clientTurnId): never regenerate; replay or attach to the existing turn.
                return resumeTurn(userId, conversation, turn);
            }
            ownsTurn = true;
            aiTurnService.markBuildingContext(turn.getId());
            // L1 (fail-closed, audited) then L2 snapshot + injection (design doc §5.1/§5.2).
            ContestParticipationService.ParticipationView participation =
                    participationService.evaluate(userId, turn.getId(), conversation.getId());
            ContestAiAssistanceTelemetryService telemetryService = contestAssistanceTelemetryService;
            ContestAiAssistanceTelemetryService.TrackingContext telemetryContext =
                    ContestAiAssistanceTelemetryService.TrackingContext.inactive();
            if (telemetryService != null) {
                try {
                    telemetryContext = telemetryService.begin(turn, conversation, participation.attributed());
                } catch (RuntimeException ex) {
                    log.warn("contest assistance start observation failed turn={} error={}", turn.getId(), ex.toString());
                }
            }
            PolicySnapshotService.PolicySnapshot snapshot =
                    policySnapshotService.createForTurn(userId, turn.getId(), conversation.getId(), participation);
            aiTurnService.attachPolicySnapshot(turn.getId(), snapshot.id());
            // L3 first layer (message fingerprint, participants only): annotate the
            // bootstrap with matched restricted problems; PASS is audited too.
            GuardVerdict msgVerdict = matchUserMessage(userId, turn.getId(), conversation.getId(),
                    request.message(), snapshot);
            // Build BEFORE appending the user message so the current request does not
            // appear twice (RECENT_TURNS + CURRENT_USER_REQUEST).
            // F1/F2 entry metadata: the request's problemId falls back to the
            // conversation-bound problem so the entry context survives follow-up
            // turns that no longer carry it.
            BootstrapContextBuilder.EntryContext entryContext = new BootstrapContextBuilder.EntryContext(
                    request.problemId() != null ? request.problemId() : conversation.getProblemId(),
                    request.contestContext(),
                    request.submissionContext(),
                    request.selectionContext());
            BootstrapContextBuilder.BootstrapContext bootstrap = bootstrapBuilder.build(
                    userId,
                    conversation.getId(),
                    request.message(),
                    snapshot,
                    msgVerdict,
                    entryContext,
                    properties.getAgentCore().getRecentTurnsLimit(),
                    properties.getAgentCore().getBootstrapBudgetTokens());
            AiChatMessageResponse user = aiConversationService.appendMessage(
                    conversation.getId(),
                    userId,
                    request.problemId(),
                    "user",
                    request.message(),
                    null,
                    clientMessageId(request),
                    null
            );
            AiChatMessageResponse assistant = aiConversationService.appendMessageWithStatus(
                    conversation.getId(),
                    userId,
                    request.problemId(),
                    "assistant",
                    "",
                    null,
                    assistantClientMessageId(request),
                    null,
                    AiConversationService.MESSAGE_STATUS_RUNNING,
                    null
            );
            aiTurnService.attachMessages(turn.getId(), user.id(), assistant.id());
            AiTurnEntity scheduledTurn = turn;
            ContestAiAssistanceTelemetryService.TrackingContext scheduledTelemetryContext = telemetryContext;
            CompletableFuture<TurnResult> rawResult = CompletableFuture.supplyAsync(
                    () -> complete(userId, conversation, request, bootstrap, snapshot, msgVerdict,
                            user, assistant, scheduledTurn, scheduledTelemetryContext, participation.attributed()),
                    executor
            );
            CompletableFuture<TurnResult> result = rawResult
                    .orTimeout(turnTimeoutMs, TimeUnit.MILLISECONDS)
                    .handle((value, ex) -> {
                        if (ex == null) {
                            return value;
                        }
                        if (isTimeout(ex)) {
                            failTimedOutTurn(userId, scheduledTurn.getId(), assistant.id());
                            throw new CompletionException(turnTimeoutFailure());
                        }
                        throw asCompletionException(ex);
                    });
            inFlightTurns.put(scheduledTurn.getId(), result);
            result.whenComplete((value, ex) -> inFlightTurns.remove(scheduledTurn.getId()));
            return new TurnHandle(conversation, new AiChatContext("", "", "", "", ""), user, assistant, result, scheduledTurn.getId());
        } catch (RuntimeException ex) {
            // Only a turn created by this request may be failed here; a duplicate request
            // attaching to someone else's in-flight turn must never mutate its state.
            if (ownsTurn && turn != null) {
                if (aiTurnService.failTurn(turn.getId(), AiTurnService.STATUS_FAILED_RETRYABLE, "TURN_SETUP_FAILURE")
                        && contestAssistanceTelemetryService != null) {
                    contestAssistanceTelemetryService.finishFailedByTurnId(
                            turn.getId(), AiTurnService.STATUS_FAILED_RETRYABLE);
                }
            }
            throw ex;
        }
    }

    /**
     * Reattach to an existing turn (SSE reconnect by turnId). Read-only: no quota
     * assertion, no new messages, and crucially no second provider call.
     */
    public TurnHandle resume(Long userId, String turnId, AiChatRequest request) {
        AiTurnEntity turn = aiTurnService.findById(turnId);
        if (turn == null) {
            throw new DomainException(ErrorCode.NOT_FOUND, "AI turn not found");
        }
        AiConversationEntity conversation = aiConversationService.getOwnedConversation(userId, turn.getConversationId());
        return resumeTurn(userId, conversation, turn);
    }

    private TurnHandle resumeTurn(Long userId, AiConversationEntity conversation, AiTurnEntity turn) {
        AiTurnEntity current = awaitTurnMessageIds(turn);
        String assistantMessageId = current.getAssistantMessageId();
        if (assistantMessageId == null) {
            throw new DomainException(ErrorCode.CONFLICT, "AI turn failed before any message was stored; retry with a new message");
        }
        Long userMessageId = parseMessageId(current.getUserMessageId());
        AiChatMessageResponse user = aiConversationService.getMessage(userId, userMessageId);
        AiChatMessageResponse assistant = aiConversationService.getMessage(userId, parseMessageId(assistantMessageId));
        CompletableFuture<TurnResult> result;
        if (AiTurnService.STATUS_COMPLETED.equals(current.getStatus())) {
            result = CompletableFuture.completedFuture(replayCompletedTurn(userId, assistant));
        } else if (AiTurnService.isTerminal(current.getStatus())) {
            result = CompletableFuture.failedFuture(terminalTurnFailure(assistant));
        } else {
            result = inFlightTurns.get(current.getId());
            if (result == null) {
                // The generating instance is gone (restart) or this is a second instance:
                // poll the turn row until it reaches a terminal state.
                result = pollTurnCompletion(userId, current.getId());
            }
        }
        return new TurnHandle(conversation, new AiChatContext("", "", "", "", ""), user, assistant, result, current.getId());
    }

    private AiTurnEntity awaitTurnMessageIds(AiTurnEntity turn) {
        AiTurnEntity current = turn;
        long deadline = System.currentTimeMillis() + 3_000L;
        while (current.getAssistantMessageId() == null && !AiTurnService.isTerminal(current.getStatus())
                && System.currentTimeMillis() < deadline) {
            try {
                Thread.sleep(100L);
            } catch (InterruptedException ex) {
                Thread.currentThread().interrupt();
                break;
            }
            AiTurnEntity reloaded = aiTurnService.findById(turn.getId());
            if (reloaded == null) {
                break;
            }
            current = reloaded;
        }
        return current;
    }

    private CompletableFuture<TurnResult> pollTurnCompletion(Long userId, String turnId) {
        return CompletableFuture.supplyAsync(() -> {
            long deadline = System.currentTimeMillis() + turnTimeoutMs;
            while (System.currentTimeMillis() < deadline) {
                AiTurnEntity current = aiTurnService.findById(turnId);
                if (current == null) {
                    throw new CompletionException(new DomainException(ErrorCode.NOT_FOUND, "AI turn not found"));
                }
                if (AiTurnService.isTerminal(current.getStatus()) && current.getAssistantMessageId() != null) {
                    AiChatMessageResponse assistant = aiConversationService.getMessage(userId, parseMessageId(current.getAssistantMessageId()));
                    if (AiTurnService.STATUS_COMPLETED.equals(current.getStatus())) {
                        return replayCompletedTurn(userId, assistant);
                    }
                    throw new CompletionException(terminalTurnFailure(assistant));
                }
                try {
                    Thread.sleep(500L);
                } catch (InterruptedException ex) {
                    Thread.currentThread().interrupt();
                    throw new CompletionException(turnTimeoutFailure());
                }
            }
            throw new CompletionException(turnTimeoutFailure());
        }, executor);
    }

    private TurnResult replayCompletedTurn(Long userId, AiChatMessageResponse assistant) {
        AiCompletion completion = new AiCompletion(assistant.content(), null, assistant.model(), 0, 0);
        AiAssistantResponseNormalizer.NormalizedResponse normalized = responseNormalizer.normalize(completion);
        return new TurnResult(completion, normalized, assistant, null, 0);
    }

    private DomainException terminalTurnFailure(AiChatMessageResponse assistant) {
        String message = assistant != null && assistant.errorMessage() != null && !assistant.errorMessage().isBlank()
                ? assistant.errorMessage()
                : "AI provider call failed";
        return new DomainException(ErrorCode.INTERNAL_ERROR, message);
    }

    private Long parseMessageId(String messageId) {
        try {
            return Long.parseLong(messageId);
        } catch (NumberFormatException ex) {
            throw new DomainException(ErrorCode.INTERNAL_ERROR, "AI turn message reference is invalid");
        }
    }

    private TurnResult complete(
            Long userId,
            AiConversationEntity conversation,
            AiChatRequest request,
            BootstrapContextBuilder.BootstrapContext bootstrap,
            PolicySnapshotService.PolicySnapshot snapshot,
            GuardVerdict msgVerdict,
            AiChatMessageResponse user,
            AiChatMessageResponse assistant,
            AiTurnEntity turn,
            ContestAiAssistanceTelemetryService.TrackingContext telemetryContext,
            RunningContestParticipation attributedParticipation
    ) {
        try {
            // CAS non-terminal -> GENERATING. Losing this CAS means the timeout cleanup
            // already finalized the turn: the provider must not be called for a dead turn.
            if (!aiTurnService.advanceToGenerating(turn.getId(), turn.getId())) {
                throw turnTimeoutFailure();
            }
            // §6.2 (D1=B2): pre-turn understanding sets the tool-call floor only.
            // The judgement is a small model call on this worker thread; fail-open inside.
            boolean hasPriorTurns = bootstrap.sections().stream()
                    .anyMatch(section -> section.type() == ContextSectionType.RECENT_TURNS);
            TurnUnderstandingService.TurnUnderstanding understanding =
                    turnUnderstandingService.assess(request.message(), hasPriorTurns);
            recordContestAssistanceUsage(telemetryContext, "UNDERSTANDING",
                    ContestAiAssistanceTelemetryService.SOURCE_UNDERSTANDING, understanding.usage());
            if (hasPriorTurns && understanding.usage() == null) {
                markContestAssistanceTokensPartial(telemetryContext);
            }
            boolean requireToolCall = !understanding.requiresTools().isEmpty();
            // P3-3 (C5 wiring): the contest policy projection travels with the run so the
            // runtime can hide contest-only tools and every tool call can ABAC internally.
            // P3-4: the message-layer verdict travels too, so the context layer does not
            // re-inject rules for problems the message layer already constrained.
            ContestPolicyView contestPolicy = ContestPolicyView.from(snapshot);
            // P3-5 restricted-turn predicate (design doc §5.4 实施补充): participant and
            // at least one constrained problem in the snapshot. The context-layer match
            // result is only knowable after the run finishes, but the output protocol
            // must be chosen up front, so the predicate is deliberately conservative.
            // Non-restricted turns skip L4 entirely — zero cost, zero audit rows (same
            // contract as L3).
            boolean restrictedTurn = contestPolicy.isParticipant() && !contestPolicy.constrainedProblems().isEmpty();
            AgentRuntime.AgentRunResult runResult = agentRuntime.run(new AgentRuntime.AgentRunRequest(
                    turn.getId(),
                    userId,
                    conversation.getId(),
                    snapshot.id(),
                    Set.of("AI_CHAT"),
                    bootstrap.sections(),
                    requireToolCall,
                    restrictedTurn ? "BUFFERED" : "STREAM",
                    restrictedTurn ? CallProfile.CHAT_BUFFERED : CallProfile.CHAT_STREAM,
                    LoopBudget.from(properties.getAgentCore()),
                    contestPolicy,
                    msgVerdict,
                    contestAssistanceUsageObserver(telemetryContext,
                            ContestAiAssistanceTelemetryService.SOURCE_AGENT_PRIMARY)
            ));
            // P3-5 L4 (§5.4): restricted turns deliver only guard-passed content. An
            // intercepted draft is never persisted to ai_messages; it triggers exactly
            // one safe regeneration, and a second interception downgrades the reply to
            // the server-authored safe refusal text.
            boolean finalRefusal = false;
            // P3-6 (§5.5): the safe-regeneration budget is exactly one per turn, shared
            // between the initial L4 interception and a race-triggered re-interception.
            boolean regenerationUsed = false;
            if (restrictedTurn) {
                ContestOutputGuard.Verdict verdict = contestOutputGuard.evaluate(
                        turn.getId(), userId, conversation.getId(), runResult.content(),
                        contestPolicy, runResult.contestGuardVerdict(), false);
                if (verdict.intercepted()) {
                    regenerationUsed = true;
                    AgentRuntime.AgentRunResult regenerated = agentRuntime.run(new AgentRuntime.AgentRunRequest(
                            turn.getId(),
                            userId,
                            conversation.getId(),
                            snapshot.id(),
                            Set.of("AI_CHAT"),
                            sectionsWithOutputGuardRetry(bootstrap.sections(), verdict.reasonCode()),
                            requireToolCall,
                            "BUFFERED",
                            CallProfile.CHAT_BUFFERED,
                            LoopBudget.from(properties.getAgentCore()),
                            contestPolicy,
                            msgVerdict,
                            contestAssistanceUsageObserver(telemetryContext,
                                    ContestAiAssistanceTelemetryService.SOURCE_AGENT_SAFE_REGENERATION)
                    ));
                    ContestOutputGuard.Verdict regenVerdict = contestOutputGuard.evaluate(
                            turn.getId(), userId, conversation.getId(), regenerated.content(),
                            contestPolicy, regenerated.contestGuardVerdict(), true);
                    finalRefusal = regenVerdict.intercepted();
                    runResult = regenerated;
                }
            }
            // P3-6 (§5.5 时间竞争双检): re-read participation + running-contest statements
            // right before the answer is finalized and compare with the turn-start
            // snapshot. Lookup failures are fail-closed inside (audited degraded +
            // SERVICE_UNAVAILABLE), landing in this method's normal failure path.
            PolicySnapshotService.PolicyRecheck recheck =
                    policySnapshotService.recheckBeforeReturn(userId, turn.getId(), conversation.getId(), snapshot);
            boolean pseudoStream = restrictedTurn;
            if (recheck.changed()) {
                ContestPolicyView recheckedPolicy = ContestPolicyView.from(
                        recheck.participation().status(), recheck.statements());
                boolean nowRestricted = recheckedPolicy.isParticipant()
                        && !recheckedPolicy.constrainedProblems().isEmpty();
                if (nowRestricted) {
                    // Became restricted mid-generation (contest started / user joined /
                    // newly constrained problem): the pending content must pass L4 under
                    // the NEW policy before delivery. The L3 verdict is null — the
                    // full-code check does not depend on it. An already-decided refusal
                    // stands: the server text is safe under any policy.
                    pseudoStream = true;
                    if (!finalRefusal) {
                        ContestOutputGuard.Verdict raceVerdict = contestOutputGuard.evaluate(
                                turn.getId(), userId, conversation.getId(), runResult.content(),
                                recheckedPolicy, null, false, ContestOutputGuard.TRIGGER_RECHECK);
                        if (raceVerdict.intercepted()) {
                            if (!regenerationUsed) {
                                regenerationUsed = true;
                                AgentRuntime.AgentRunResult regenerated = agentRuntime.run(new AgentRuntime.AgentRunRequest(
                                        turn.getId(),
                                        userId,
                                        conversation.getId(),
                                        snapshot.id(),
                                        Set.of("AI_CHAT"),
                                        sectionsWithOutputGuardRetry(bootstrap.sections(), raceVerdict.reasonCode()),
                                        requireToolCall,
                                        "BUFFERED",
                                        CallProfile.CHAT_BUFFERED,
                                        LoopBudget.from(properties.getAgentCore()),
                                        recheckedPolicy,
                                        null,
                                        contestAssistanceUsageObserver(telemetryContext,
                                                ContestAiAssistanceTelemetryService.SOURCE_AGENT_RECHECK_REGENERATION)
                                ));
                                ContestOutputGuard.Verdict regenVerdict = contestOutputGuard.evaluate(
                                        turn.getId(), userId, conversation.getId(), regenerated.content(),
                                        recheckedPolicy, null, true, ContestOutputGuard.TRIGGER_RECHECK);
                                finalRefusal = regenVerdict.intercepted();
                                runResult = regenerated;
                            } else {
                                // Shared budget already spent by the initial interception:
                                // no second regeneration — downgrade straight to refusal.
                                finalRefusal = true;
                            }
                        }
                    }
                } else {
                    // Became unrestricted mid-generation (e.g., the run ended or the user
                    // left the participant set): the generated content no longer has any
                    // violation basis — deliver it even when L4 had downgraded to refusal.
                    finalRefusal = false;
                }
            }
            // Quota semantics (P3-5): exactly one usage record per turn, attributed to
            // the run that determined the delivered content (first run on pass, the
            // regeneration run otherwise — including a race-triggered regeneration).
            // A refused turn is a completed turn — the record stays success=true and
            // is never duplicated.
            AiCompletion completion = new AiCompletion(
                    finalRefusal ? CONTEST_SAFE_REFUSAL_TEXT : runResult.content(),
                    runResult.provider(), runResult.model(),
                    runResult.promptTokens(), runResult.completionTokens());
            AiAssistantResponseNormalizer.NormalizedResponse normalized = responseNormalizer.normalize(completion);
            completion = normalized.completion();
            // CAS GENERATING -> COMPLETED: only the winner may record usage and complete the
            // assistant message. A lost CAS means the timeout cleanup already finalized both.
            if (!aiTurnService.completeTurn(turn.getId())) {
                throw turnTimeoutFailure();
            }
            // The turn state CAS is the authoritative completion point. Finalize the
            // observer immediately after it wins so later best-effort quota/message/
            // digest side effects cannot leave a completed ai_turn as telemetry PENDING.
            finishContestAssistanceTelemetry(telemetryContext, request, conversation, snapshot,
                    attributedParticipation, msgVerdict, runResult.contestGuardVerdict());
            aiQuotaService.record(userId, completion.provider(), completion.model(),
                    completion.promptTokens(), completion.completionTokens(), true);
            AiChatMessageResponse completedAssistant = aiConversationService.completeMessage(
                    userId,
                    assistant.id(),
                    completion.content(),
                    completion.model()
            );
            String conversationMode = aiConversationService.updateAutomaticMode(userId, conversation.getId(), request, completion);
            // §6.3: synchronous stub digest + async curate enqueue; never throws, never
            // blocks the chat path. Message ids come from the freshly created message
            // objects: the in-memory turn entity predates attachMessages and still
            // carries nulls.
            turnDigestService.recordCompletedTurn(buildDigestInput(userId, turn, request, completion,
                    user.id(), assistant.id()));
            return new TurnResult(completion, normalized, completedAssistant, conversationMode, 0, pseudoStream);
        } catch (RuntimeException ex) {
            // CAS any non-terminal state -> terminal failure: exactly one of this failure
            // path and the timeout cleanup records the failed usage and fails the message.
            if (aiTurnService.failTurn(turn.getId(), failureStatus(ex), failureErrorCode(ex))) {
                aiQuotaService.record(userId, null, null, 0, 0, false);
                aiConversationService.failMessage(userId, assistant.id(), failureMessage(ex));
                finishContestAssistanceFailure(telemetryContext, failureStatus(ex));
            }
            throw new CompletionException(providerFailure(ex));
        }
    }

    private TurnDigestInput buildDigestInput(Long userId, AiTurnEntity turn, AiChatRequest request, AiCompletion completion,
            Long userMessageId, Long assistantMessageId) {
        java.util.List<Long> referencedProblemIds = new java.util.ArrayList<>();
        Long selectionProblemId = parseProblemId(request.selectionContext() != null
                && request.selectionContext().problemContext() != null
                ? request.selectionContext().problemContext().problemId() : null);
        if (selectionProblemId != null) {
            referencedProblemIds.add(selectionProblemId);
        }
        Long contextProblemId = parseProblemId(request.problemContext() != null ? request.problemContext().id() : null);
        if (contextProblemId != null) {
            referencedProblemIds.add(contextProblemId);
        }
        String entryPoint = "CHAT";
        if (request.contestContext() != null && request.contestContext().contestRunId() != null) {
            entryPoint = "CONTEST";
        } else if (request.problemId() != null) {
            entryPoint = "PROBLEM_PAGE";
        }
        return new TurnDigestInput(
                turn.getId(),
                turn.getConversationId(),
                userId,
                userMessageId == null ? null : String.valueOf(userMessageId),
                assistantMessageId == null ? null : String.valueOf(assistantMessageId),
                request.message(),
                completion.content(),
                completion.model(),
                request.problemId(),
                referencedProblemIds,
                request.selectionContext() != null ? request.selectionContext().selectedText() : null,
                request.selectionContext() != null ? request.selectionContext().sourceMessageId() : null,
                request.submissionContext() != null ? request.submissionContext().submissionId() : null,
                entryPoint
        );
    }

    private Long parseProblemId(String raw) {
        if (raw == null || raw.isBlank()) {
            return null;
        }
        try {
            return Long.parseLong(raw.trim());
        } catch (NumberFormatException ex) {
            return null;
        }
    }

    private void recordContestAssistanceUsage(
            ContestAiAssistanceTelemetryService.TrackingContext telemetryContext,
            String usageKey,
            String usageSource,
            ModelUsage usage
    ) {
        if (contestAssistanceTelemetryService != null && usage != null) {
            try {
                contestAssistanceTelemetryService.recordUsage(telemetryContext, usageKey, usageSource, usage);
            } catch (RuntimeException ex) {
                log.warn("contest assistance token observation failed turn={} source={} error={}",
                        telemetryContext == null ? null : telemetryContext.turnId(), usageSource, ex.toString());
            }
        }
    }

    private void markContestAssistanceTokensPartial(
            ContestAiAssistanceTelemetryService.TrackingContext telemetryContext
    ) {
        if (contestAssistanceTelemetryService != null) {
            try {
                contestAssistanceTelemetryService.markTokenAccountingPartial(telemetryContext);
            } catch (RuntimeException ex) {
                log.warn("contest assistance partial-token observation failed turn={} error={}",
                        telemetryContext == null ? null : telemetryContext.turnId(), ex.toString());
            }
        }
    }

    private Consumer<ModelUsage> contestAssistanceUsageObserver(
            ContestAiAssistanceTelemetryService.TrackingContext telemetryContext,
            String usageSource
    ) {
        if (contestAssistanceTelemetryService == null || telemetryContext == null || !telemetryContext.active()) {
            return null;
        }
        AtomicInteger sequence = new AtomicInteger();
        return usage -> {
            if (usage == null) {
                markContestAssistanceTokensPartial(telemetryContext);
                return;
            }
            recordContestAssistanceUsage(telemetryContext,
                    usageSource + "_" + sequence.incrementAndGet(), usageSource, usage);
        };
    }

    private void finishContestAssistanceTelemetry(
            ContestAiAssistanceTelemetryService.TrackingContext telemetryContext,
            AiChatRequest request,
            AiConversationEntity conversation,
            PolicySnapshotService.PolicySnapshot snapshot,
            RunningContestParticipation attributedParticipation,
            GuardVerdict messageVerdict,
            GuardVerdict runtimeVerdict
    ) {
        if (contestAssistanceTelemetryService != null) {
            try {
                contestAssistanceTelemetryService.finishSuccessful(
                        telemetryContext,
                        AiTurnService.STATUS_COMPLETED,
                        request.message(),
                        contestAssistanceTelemetryService.candidates(
                                request, conversation, snapshot, attributedParticipation,
                                messageVerdict, runtimeVerdict));
            } catch (RuntimeException ex) {
                log.warn("contest assistance completion observation failed turn={} error={}",
                        telemetryContext == null ? null : telemetryContext.turnId(), ex.toString());
            }
        }
    }

    private void finishContestAssistanceFailure(
            ContestAiAssistanceTelemetryService.TrackingContext telemetryContext,
            String terminalStatus
    ) {
        if (contestAssistanceTelemetryService != null) {
            try {
                contestAssistanceTelemetryService.finishFailed(telemetryContext, terminalStatus);
            } catch (RuntimeException ex) {
                log.warn("contest assistance failure observation failed turn={} error={}",
                        telemetryContext == null ? null : telemetryContext.turnId(), ex.toString());
            }
        }
    }

    private void failTimedOutTurn(Long userId, String turnId, Long assistantMessageId) {        try {
            if (aiTurnService.failTurn(turnId, AiTurnService.STATUS_FAILED_RETRYABLE, "TURN_TIMEOUT")) {
                aiQuotaService.record(userId, null, null, 0, 0, false);
                aiConversationService.failMessage(userId, assistantMessageId, "AI provider call timed out");
                if (contestAssistanceTelemetryService != null) {
                    contestAssistanceTelemetryService.finishFailedByTurnId(
                            turnId, AiTurnService.STATUS_FAILED_RETRYABLE);
                }
            }
        } catch (RuntimeException ex) {
            log.warn("AI turn timeout cleanup failed user={} turn={} assistantMessage={} error={}",
                    userId, turnId, assistantMessageId, ex.toString());
        }
    }

    private String failureStatus(RuntimeException ex) {
        if (ex instanceof DomainException domainException
                && domainException.errorCode() != ErrorCode.TOO_MANY_REQUESTS
                && domainException.errorCode() != ErrorCode.SERVICE_UNAVAILABLE) {
            return AiTurnService.STATUS_FAILED_FINAL;
        }
        return AiTurnService.STATUS_FAILED_RETRYABLE;
    }

    private String failureErrorCode(RuntimeException ex) {
        if (ex instanceof DomainException domainException) {
            return domainException.errorCode().name();
        }
        return "PROVIDER_FAILURE";
    }

    private String clientMessageId(AiChatRequest request) {
        if (request.clientMessageId() == null || request.clientMessageId().isBlank()) {
            return null;
        }
        String normalized = request.clientMessageId().trim();
        return normalized.length() <= 80 ? normalized : normalized.substring(0, 80);
    }

    private String assistantClientMessageId(AiChatRequest request) {
        String clientMessageId = clientMessageId(request);
        if (clientMessageId == null) {
            return null;
        }
        String assistantId = clientMessageId + ":assistant";
        return assistantId.length() <= 80 ? assistantId : assistantId.substring(0, 80);
    }

    private RuntimeException providerFailure(RuntimeException ex) {
        if (ex instanceof DomainException) {
            return ex;
        }
        return new DomainException(ErrorCode.INTERNAL_ERROR, "AI provider call failed");
    }

    private DomainException turnTimeoutFailure() {
        return new DomainException(ErrorCode.INTERNAL_ERROR, "AI provider call timed out");
    }

    private CompletionException asCompletionException(Throwable ex) {
        if (ex instanceof CompletionException completionException) {
            return completionException;
        }
        return new CompletionException(ex);
    }

    private boolean isTimeout(Throwable ex) {
        Throwable current = ex;
        while (current != null) {
            if (current instanceof TimeoutException) {
                return true;
            }
            current = current.getCause();
        }
        return false;
    }

    private String failureMessage(RuntimeException ex) {
        if (ex instanceof DomainException domainException && domainException.getMessage() != null && !domainException.getMessage().isBlank()) {
            return domainException.getMessage();
        }
        return "AI provider call failed";
    }
}
