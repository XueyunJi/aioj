package com.aioj.next.ai.domain;

import com.aioj.next.ai.domain.memory.AiAfterTurnMemoryProfileEventService;
import com.aioj.next.ai.domain.memory.AiMemoryClarificationService;
import com.aioj.next.ai.domain.response.AiAssistantResponseNormalizer;
import com.aioj.next.ai.persistence.entity.AiConversationEntity;
import com.aioj.next.ai.persistence.entity.AiTurnEntity;
import com.aioj.next.common.error.DomainException;
import com.aioj.next.common.error.ErrorCode;
import com.aioj.next.contract.ai.AiChatMessageResponse;
import com.aioj.next.contract.ai.AiChatRequest;
import com.aioj.next.contract.contest.ContestAiPolicyResponse;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.stereotype.Service;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Executor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

@Service
public class AiChatTurnService {
    private static final Logger log = LoggerFactory.getLogger(AiChatTurnService.class);
    private static final long DEFAULT_TURN_TIMEOUT_MS = 210_000L;

    private final AiProvider aiProvider;
    private final AiQuotaService aiQuotaService;
    private final AiConversationService aiConversationService;
    private final AiContextService aiContextService;
    private final AiAfterTurnMemoryProfileEventService afterTurnMemoryProfileEventService;
    private final AiMemoryClarificationService memoryClarificationService;
    private final AiCapacityService aiCapacityService;
    private final AiAssistantResponseNormalizer responseNormalizer;
    private final ContestTurnGuard contestTurnGuard;
    private final AiResponsePolicyGuard aiResponsePolicyGuard;
    private final AiTurnService aiTurnService;
    private final OperationAuditWriter auditWriter;
    private final Executor executor;
    private final long turnTimeoutMs;
    /**
     * In-process registry of in-flight turns so a duplicate submission or an SSE reconnect can
     * attach to the running turn instead of regenerating. This is only an optimization:
     * the ai_turns row remains the consistency source, and a registry miss falls back to
     * polling the turn row (also covers a restart or a second service instance).
     */
    private final Map<String, CompletableFuture<TurnResult>> inFlightTurns = new ConcurrentHashMap<>();

    public AiChatTurnService(
            AiProvider aiProvider,
            AiQuotaService aiQuotaService,
            AiConversationService aiConversationService,
            AiContextService aiContextService,
            AiAfterTurnMemoryProfileEventService afterTurnMemoryProfileEventService,
            AiCapacityService aiCapacityService,
            AiAssistantResponseNormalizer responseNormalizer,
            ContestTurnGuard contestTurnGuard,
            AiResponsePolicyGuard aiResponsePolicyGuard,
            AiTurnService aiTurnService,
            @Qualifier("aiChatTurnExecutor") Executor executor
    ) {
        this(aiProvider, aiQuotaService, aiConversationService, aiContextService,
                afterTurnMemoryProfileEventService, null, aiCapacityService, responseNormalizer,
                contestTurnGuard, aiResponsePolicyGuard, aiTurnService, null, executor, DEFAULT_TURN_TIMEOUT_MS);
    }

    @Autowired
    public AiChatTurnService(
            AiProvider aiProvider,
            AiQuotaService aiQuotaService,
            AiConversationService aiConversationService,
            AiContextService aiContextService,
            AiAfterTurnMemoryProfileEventService afterTurnMemoryProfileEventService,
            AiMemoryClarificationService memoryClarificationService,
            AiCapacityService aiCapacityService,
            AiAssistantResponseNormalizer responseNormalizer,
            ContestTurnGuard contestTurnGuard,
            AiResponsePolicyGuard aiResponsePolicyGuard,
            AiTurnService aiTurnService,
            OperationAuditWriter auditWriter,
            @Qualifier("aiChatTurnExecutor") Executor executor
    ) {
        this(aiProvider, aiQuotaService, aiConversationService, aiContextService,
                afterTurnMemoryProfileEventService, memoryClarificationService, aiCapacityService, responseNormalizer,
                contestTurnGuard, aiResponsePolicyGuard, aiTurnService, auditWriter, executor, DEFAULT_TURN_TIMEOUT_MS);
    }

    AiChatTurnService(
            AiProvider aiProvider,
            AiQuotaService aiQuotaService,
            AiConversationService aiConversationService,
            AiContextService aiContextService,
            AiAfterTurnMemoryProfileEventService afterTurnMemoryProfileEventService,
            AiMemoryClarificationService memoryClarificationService,
            AiCapacityService aiCapacityService,
            AiAssistantResponseNormalizer responseNormalizer,
            ContestTurnGuard contestTurnGuard,
            AiResponsePolicyGuard aiResponsePolicyGuard,
            AiTurnService aiTurnService,
            OperationAuditWriter auditWriter,
            @Qualifier("aiChatTurnExecutor") Executor executor,
            long turnTimeoutMs
    ) {
        this.aiProvider = aiProvider;
        this.aiQuotaService = aiQuotaService;
        this.aiConversationService = aiConversationService;
        this.aiContextService = aiContextService;
        this.afterTurnMemoryProfileEventService = afterTurnMemoryProfileEventService;
        this.memoryClarificationService = memoryClarificationService;
        this.aiCapacityService = aiCapacityService;
        this.responseNormalizer = responseNormalizer;
        this.contestTurnGuard = contestTurnGuard;
        this.aiResponsePolicyGuard = aiResponsePolicyGuard;
        this.aiTurnService = aiTurnService;
        this.auditWriter = auditWriter;
        this.executor = executor;
        this.turnTimeoutMs = Math.max(1L, turnTimeoutMs);
    }

    public TurnHandle start(Long userId, AiChatRequest request) {
        // Single server-side guard pass: detection input comes from the request itself, so
        // it can run before any context assembly. REFUSE is returned as a decision and is
        // persisted (blocked messages plus an ai_turns row marked REFUSED) and raised
        // below once the conversation exists.
        ContestTurnGuard.GuardDecision guardDecision = contestTurnGuard.evaluateAndApply(userId, request);
        AiChatRequest effectiveRequest = guardDecision.request();
        AiCapacityService.Lease capacityLease = aiCapacityService.acquire(AiCapacityService.AiWorkload.STUDENT_ASSISTANT);
        boolean scheduled = false;
        AiTurnEntity turn = null;
        boolean ownsTurn = false;
        try {
            aiQuotaService.assertAvailable(userId);
            AiConversationEntity conversation = aiConversationService.resolveForWrite(userId, effectiveRequest);
            if (guardDecision.participant()) {
                // Every participant turn binds the conversation to the running contest so
                // admin contest AI usage records cover passing turns too, not just blocks.
                aiConversationService.bindContestContext(conversation, guardDecision.contestId(),
                        guardDecision.contestRunId(), guardDecision.firstMatchedProblemId());
            }
            if (guardDecision.refused()) {
                RuntimeException blocked = new ContestProblemLeakBlockedException(
                        guardDecision.contestId(), guardDecision.contestRunId(),
                        guardDecision.firstMatchedProblemId(), guardDecision.firstMatchedContestProblemId());
                // A refused turn still owns an ai_turns row (marked REFUSED below) so
                // turn-level records cover guard interceptions. A same-clientTurnId retry
                // finds the existing row and only re-throws: no duplicate blocked
                // messages and no second usage record.
                AiTurnService.BeginTurnOutcome refusedOutcome = aiTurnService.beginTurn(conversation.getId(), clientMessageId(effectiveRequest));
                if (refusedOutcome.created()) {
                    AiChatRequest.ContestContext blockContext = new AiChatRequest.ContestContext(
                            guardDecision.contestId(), guardDecision.contestRunId(), guardDecision.firstMatchedContestProblemId());
                    persistBlockedTurn(userId, effectiveRequest, conversation, blocked, true,
                            blockContext, guardDecision.firstMatchedProblemId(), guardDecision.refusal());
                    aiTurnService.refuseTurn(refusedOutcome.turn().getId());
                }
                throw blocked;
            }
            AiTurnService.BeginTurnOutcome beginOutcome = aiTurnService.beginTurn(conversation.getId(), clientMessageId(effectiveRequest));
            turn = beginOutcome.turn();
            if (!beginOutcome.created()) {
                // Duplicate submission (retry/double-click/SSE reconnect with the same
                // clientTurnId): never regenerate; replay or attach to the existing turn.
                return resumeTurn(userId, conversation, turn);
            }
            ownsTurn = true;
            aiTurnService.markBuildingContext(turn.getId());
            aiContextService.beforeTurn(userId, conversation, effectiveRequest);
            applyMemoryClarificationAnswer(userId, conversation, effectiveRequest);
            AiChatContext builtContext = aiContextService.build(userId, conversation, effectiveRequest);
            AiChatContext context = guardDecision.constrained()
                    ? builtContext.withContestPolicyBlock(guardDecision.policyBlock())
                    : builtContext;
            // A turn is constrained when the contest guard decided CONSTRAIN, or when the
            // server-resolved submission context belongs to an active contest problem.
            boolean constrained = guardDecision.constrained()
                    || (context.contestPolicy() != null && context.contestPolicy().activeContestProblem());
            AiChatRequest.ContestContext contestContext = effectiveRequest.contestContext();
            if (contestContext == null && guardDecision.participant()) {
                contestContext = new AiChatRequest.ContestContext(
                        guardDecision.contestId(), guardDecision.contestRunId(), null);
            }
            AiChatRequest.ContestContext turnContestContext = contestContext;
            String contextSnapshot = aiContextService.snapshot(context);
            AiChatMessageResponse user = appendUserMessage(userId, effectiveRequest, conversation, contextSnapshot, turnContestContext);
            AiChatMessageResponse assistant = appendRunningAssistant(userId, effectiveRequest, conversation, turnContestContext);
            aiTurnService.attachMessages(turn.getId(), user.id(), assistant.id());
            AiTurnEntity scheduledTurn = turn;
            CompletableFuture<TurnResult> rawResult = CompletableFuture.supplyAsync(
                    () -> complete(userId, conversation, effectiveRequest, context, user, assistant, capacityLease,
                            turnContestContext, guardDecision, constrained, scheduledTurn),
                    executor
            );
            CompletableFuture<TurnResult> result = rawResult
                    .orTimeout(turnTimeoutMs, TimeUnit.MILLISECONDS)
                    .handle((value, ex) -> {
                        if (ex == null) {
                            return value;
                        }
                        if (isTimeout(ex)) {
                            failTimedOutTurn(userId, scheduledTurn.getId(), assistant.id(), turnContestContext, capacityLease);
                            throw new CompletionException(turnTimeoutFailure());
                        }
                        throw asCompletionException(ex);
                    });
            inFlightTurns.put(scheduledTurn.getId(), result);
            result.whenComplete((value, ex) -> inFlightTurns.remove(scheduledTurn.getId()));
            scheduled = true;
            return new TurnHandle(conversation, context, user, assistant, result, scheduledTurn.getId());
        } catch (RuntimeException ex) {
            // Only a turn created by this request may be failed here; a duplicate request
            // attaching to someone else's in-flight turn must never mutate its state.
            if (ownsTurn && turn != null) {
                // The turn row exists but no async work was scheduled; do not leave a zombie
                // non-terminal turn that a later retry would attach to forever.
                aiTurnService.failTurn(turn.getId(), AiTurnService.STATUS_FAILED_RETRYABLE, "TURN_SETUP_FAILURE");
            }
            throw ex;
        } finally {
            if (!scheduled) {
                capacityLease.close();
            }
        }
    }

    /**
     * Reattach to an existing turn (SSE reconnect by turnId). Read-only: no guard pass, no
     * quota assertion, no new messages, and crucially no second provider call.
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
            // Terminal setup failure before any message was stored: this clientTurnId is burned.
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
        // The context pack of the original request is not rebuilt on resume; the stream
        // endpoint simply skips the context event for an empty context.
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

    /**
     * Best-effort persistence of a blocked turn: the student's question plus an assistant
     * refusal message, plus a failed usage record, so admin usage records include failed
     * attempts (e.g. asking about unpublished contest problems) and show what was asked.
     * The caller owns the matching ai_turns row and marks it REFUSED after this returns.
     */
    private void persistBlockedTurn(Long userId, AiChatRequest request, AiConversationEntity existingConversation,
                                    RuntimeException blocked, boolean appendUserMessage,
                                    AiChatRequest.ContestContext overrideContext, Long overrideProblemId,
                                    String overrideRefusal) {
        try {
            AiChatRequest.ContestContext contestContext = request == null ? null : request.contestContext();
            AiConversationEntity conversation = existingConversation != null
                    ? existingConversation
                    : aiConversationService.resolveForWrite(userId, request);
            if (contestContext == null) {
                contestContext = overrideContext;
            }
            if (contestContext == null) {
                contestContext = new AiChatRequest.ContestContext(
                        conversation.getContestId(), conversation.getContestRunId(), conversation.getContestProblemId());
            }
            Long effectiveProblemId = request != null && request.problemId() != null ? request.problemId() : overrideProblemId;
            if (appendUserMessage && request != null) {
                appendUserMessage(userId, request, conversation, null, contestContext, effectiveProblemId);
            }
            String refusal = overrideRefusal != null && !overrideRefusal.isBlank()
                    ? overrideRefusal
                    : com.aioj.next.common.error.UserErrorFeedback
                            .forDomain(blocked instanceof DomainException domainException
                                    ? domainException.errorCode()
                                    : ErrorCode.FORBIDDEN,
                                    blocked.getMessage(), "ai-oj-ai-service")
                            .message();
            // The refusal is a terminal assistant reply, not a transport failure: store it as
            // COMPLETED so students and staff see the actual message content.
            aiConversationService.appendMessageWithStatus(
                    conversation.getId(),
                    userId,
                    effectiveProblemId,
                    "assistant",
                    refusal,
                    null,
                    assistantClientMessageId(request),
                    null,
                    AiConversationService.MESSAGE_STATUS_COMPLETED,
                    null,
                    contestContext
            );
            aiQuotaService.record(userId, null, null, 0, 0, false, contestContext);
        } catch (RuntimeException ex) {
            log.warn("Failed to persist blocked AI turn user={} error={}", userId, ex.toString());
        }
    }

    private AiChatMessageResponse appendUserMessage(
            Long userId,
            AiChatRequest request,
            AiConversationEntity conversation,
            String contextSnapshot,
            AiChatRequest.ContestContext contestContext
    ) {
        return appendUserMessage(userId, request, conversation, contextSnapshot, contestContext, null);
    }

    private AiChatMessageResponse appendUserMessage(
            Long userId,
            AiChatRequest request,
            AiConversationEntity conversation,
            String contextSnapshot,
            AiChatRequest.ContestContext contestContext,
            Long problemIdOverride
    ) {
        Long problemId = request.problemId() != null ? request.problemId() : problemIdOverride;
        if (contestContext == null) {
            return aiConversationService.appendMessage(
                    conversation.getId(),
                    userId,
                    problemId,
                    "user",
                    request.message(),
                    null,
                    clientMessageId(request),
                    contextSnapshot
            );
        }
        return aiConversationService.appendMessage(
                conversation.getId(),
                userId,
                problemId,
                "user",
                request.message(),
                null,
                clientMessageId(request),
                contextSnapshot,
                contestContext
        );
    }

    private AiChatMessageResponse appendRunningAssistant(
            Long userId,
            AiChatRequest request,
            AiConversationEntity conversation,
            AiChatRequest.ContestContext contestContext
    ) {
        if (contestContext == null) {
            return aiConversationService.appendMessageWithStatus(
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
        }
        return aiConversationService.appendMessageWithStatus(
                conversation.getId(),
                userId,
                request.problemId(),
                "assistant",
                "",
                null,
                assistantClientMessageId(request),
                null,
                AiConversationService.MESSAGE_STATUS_RUNNING,
                null,
                contestContext
        );
    }

    private TurnResult complete(
            Long userId,
            AiConversationEntity conversation,
            AiChatRequest request,
            AiChatContext context,
            AiChatMessageResponse user,
            AiChatMessageResponse assistant,
            AiCapacityService.Lease capacityLease,
            AiChatRequest.ContestContext contestContext,
            ContestTurnGuard.GuardDecision guardDecision,
            boolean constrained,
            AiTurnEntity turn
    ) {
        try {
            // CAS non-terminal -> GENERATING and record the provider request id (turnId).
            // Losing this CAS means the timeout cleanup already finalized the turn: the
            // provider must not be called for a dead turn.
            if (!aiTurnService.advanceToGenerating(turn.getId(), turn.getId())) {
                throw turnTimeoutFailure();
            }
            AiCompletion completion = aiProvider.chat(request, context);
            completion = aiContextService.prepareCompletionForTurn(userId, conversation, request, completion);
            AiAssistantResponseNormalizer.NormalizedResponse normalized = responseNormalizer.normalize(completion);
            completion = normalized.completion();
            AiResponsePolicyGuard.GuardedCompletion guardedCompletion =
                    aiResponsePolicyGuard.guard(userId, conversation.getId(), completion, constrained);
            completion = guardedCompletion.completion();
            if (completion != normalized.completion()) {
                normalized = responseNormalizer.normalize(completion);
            }
            recordResponseReplacementAudit(userId, conversation, assistant.id(), guardDecision, context.contestPolicy(), guardedCompletion);
            completion = attachMemoryClarification(userId, conversation, request, assistant.id(), completion);
            // CAS GENERATING -> COMPLETED: only the winner may record usage and complete the
            // assistant message. A lost CAS means the timeout cleanup already recorded the
            // failed usage and failed the message; the late provider result is dropped.
            if (!aiTurnService.completeTurn(turn.getId())) {
                throw turnTimeoutFailure();
            }
            recordUsage(
                    userId,
                    completion.provider(),
                    completion.model(),
                    completion.promptTokens(),
                    completion.completionTokens(),
                    true,
                    contestContext
            );
            AiChatMessageResponse completedAssistant = aiConversationService.completeMessage(
                    userId,
                    assistant.id(),
                    completion.content(),
                    completion.model()
            );
            try {
                aiContextService.afterTurn(userId, conversation, request, completion, context, user.id(), completedAssistant.id());
            } catch (DomainException ex) {
                // A task-state CAS conflict must not fail an already completed turn; the
                // message and usage are stored, only the state update is lost.
                if (ex.errorCode() != ErrorCode.CONFLICT) {
                    throw ex;
                }
                log.error("Task-state CAS conflict after completed turn user={} conversation={} assistantMessage={}",
                        userId, conversation.getId(), completedAssistant.id());
            }
            String conversationMode = aiConversationService.updateAutomaticMode(userId, conversation.getId(), request, completion);
            enqueueAfterTurnMemoryProfileJob(userId, conversation, request, user, completedAssistant);
            return new TurnResult(completion, normalized, completedAssistant, conversationMode, 0);
        } catch (RuntimeException ex) {
            // CAS any non-terminal state -> terminal failure: exactly one of this failure
            // path and the timeout cleanup records the failed usage and fails the message.
            if (aiTurnService.failTurn(turn.getId(), failureStatus(ex), failureErrorCode(ex))) {
                recordUsage(userId, aiProvider.providerName(), aiProvider.model(), 0, 0, false, contestContext);
                aiConversationService.failMessage(userId, assistant.id(), failureMessage(ex));
            }
            throw new CompletionException(providerFailure(ex));
        } finally {
            capacityLease.close();
        }
    }

    private void failTimedOutTurn(
            Long userId,
            String turnId,
            Long assistantMessageId,
            AiChatRequest.ContestContext contestContext,
            AiCapacityService.Lease capacityLease
    ) {
        try {
            if (aiTurnService.failTurn(turnId, AiTurnService.STATUS_FAILED_RETRYABLE, "TURN_TIMEOUT")) {
                recordUsage(userId, aiProvider.providerName(), aiProvider.model(), 0, 0, false, contestContext);
                aiConversationService.failMessage(userId, assistantMessageId, "AI provider call timed out");
            }
        } catch (RuntimeException ex) {
            log.warn("AI turn timeout cleanup failed user={} turn={} assistantMessage={} error={}",
                    userId, turnId, assistantMessageId, ex.toString());
        } finally {
            capacityLease.close();
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

    private void enqueueAfterTurnMemoryProfileJob(
            Long userId,
            AiConversationEntity conversation,
            AiChatRequest request,
            AiChatMessageResponse user,
            AiChatMessageResponse completedAssistant
    ) {
        if (afterTurnMemoryProfileEventService == null) {
            return;
        }
        try {
            afterTurnMemoryProfileEventService.recordCompletedTurn(
                    userId,
                    conversation,
                    request,
                    user,
                    completedAssistant
            );
        } catch (RuntimeException ex) {
            log.warn("AI after-turn memory/profile job enqueue failed user={} conversation={} assistantMessage={} error={}",
                    userId, conversation.getId(), completedAssistant.id(), ex.toString());
        }
    }

    private void applyMemoryClarificationAnswer(
            Long userId,
            AiConversationEntity conversation,
            AiChatRequest request
    ) {
        if (memoryClarificationService == null || request == null || request.clarificationAnswer() == null) {
            return;
        }
        try {
            memoryClarificationService.applyAnswer(userId, conversation.getId(), request.clarificationAnswer());
        } catch (RuntimeException ex) {
            log.warn("AI memory clarification answer handling failed user={} conversation={} requestId={} error={}",
                    userId,
                    conversation.getId(),
                    request.clarificationAnswer().requestId(),
                    ex.toString());
        }
    }

    private AiCompletion attachMemoryClarification(
            Long userId,
            AiConversationEntity conversation,
            AiChatRequest request,
            Long assistantMessageId,
            AiCompletion completion
    ) {
        if (memoryClarificationService == null || completion == null || completion.hasClarification()) {
            return completion;
        }
        if (request != null && request.clarificationAnswer() != null) {
            return completion;
        }
        try {
            return memoryClarificationService.planClarification(userId, conversation.getId())
                    .map(plan -> attachMemoryClarification(userId, conversation, request, assistantMessageId, completion, plan))
                    .orElse(completion);
        } catch (RuntimeException ex) {
            log.warn("AI memory clarification planning failed user={} conversation={} error={}",
                    userId, conversation.getId(), ex.toString());
            return completion;
        }
    }

    private AiCompletion attachMemoryClarification(
            Long userId,
            AiConversationEntity conversation,
            AiChatRequest request,
            Long assistantMessageId,
            AiCompletion completion,
            AiMemoryClarificationService.PlannedClarification plan
    ) {
        AiCompletion withClarification = new AiCompletion(
                completion.content(),
                completion.provider(),
                completion.model(),
                completion.promptTokens(),
                completion.completionTokens(),
                completion.teachingDecision(),
                completion.stuckLayer(),
                completion.studentLevel(),
                plan.clarification()
        );
        AiCompletion prepared = aiContextService.prepareCompletionForTurn(userId, conversation, request, withClarification);
        if (prepared != null
                && prepared.hasClarification()
                && plan.clarification().id().equals(prepared.clarification().id())) {
            memoryClarificationService.markAsked(userId, plan, assistantMessageId);
        }
        return prepared == null ? completion : prepared;
    }

    private void recordResponseReplacementAudit(
            Long userId,
            AiConversationEntity conversation,
            Long assistantMessageId,
            ContestTurnGuard.GuardDecision guardDecision,
            ContestAiPolicyResponse policy,
            AiResponsePolicyGuard.GuardedCompletion guardedCompletion
    ) {
        if (auditWriter == null || guardedCompletion == null || !guardedCompletion.replaced()) {
            return;
        }
        try {
            ContestTurnGuard.MatchedProblem matched = guardDecision == null || guardDecision.matchedProblems().isEmpty()
                    ? null
                    : guardDecision.matchedProblems().get(0);
            Long problemId = matched != null ? matched.problemId() : policy == null ? null : policy.problemId();
            Long contestId = matched != null ? matched.contestId() : policy == null ? null : policy.contestId();
            Long contestRunId = matched != null ? matched.contestRunId() : policy == null ? null : policy.contestRunId();
            Long contestProblemId = matched != null ? matched.contestProblemId() : policy == null ? null : policy.contestProblemId();
            Map<String, Object> summary = new LinkedHashMap<>();
            summary.put("reason", guardedCompletion.reason());
            summary.put("conversationId", conversation.getId());
            summary.put("assistantMessageId", assistantMessageId);
            summary.put("problemId", problemId);
            summary.put("contestId", contestId);
            summary.put("contestRunId", contestRunId);
            summary.put("contestProblemId", contestProblemId);
            summary.put("constrained", true);
            summary.put("activeContestProblem", policy != null && policy.activeContestProblem());
            auditWriter.record(
                    "AI_CONTEST_RESPONSE_REPLACED",
                    "CONTEST_AI_POLICY",
                    problemId,
                    "REPLACED",
                    summary,
                    userId,
                    contestId,
                    contestRunId,
                    userId
            );
        } catch (RuntimeException ex) {
            log.warn("Contest AI response replacement audit failed user={} conversation={} assistantMessage={} error={}",
                    userId, conversation.getId(), assistantMessageId, ex.toString());
        }
    }

    private void recordUsage(
            Long userId,
            String provider,
            String model,
            long promptTokens,
            long completionTokens,
            boolean success,
            AiChatRequest.ContestContext contestContext
    ) {
        if (contestContext == null) {
            aiQuotaService.record(userId, provider, model, promptTokens, completionTokens, success);
            return;
        }
        aiQuotaService.record(userId, provider, model, promptTokens, completionTokens, success, contestContext);
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

    public record TurnHandle(
            AiConversationEntity conversation,
            AiChatContext context,
            AiChatMessageResponse user,
            AiChatMessageResponse assistant,
            CompletableFuture<TurnResult> result,
            String turnId
    ) {
    }

    public record TurnResult(
            AiCompletion completion,
            AiAssistantResponseNormalizer.NormalizedResponse normalized,
            AiChatMessageResponse assistant,
            String conversationMode,
            int memoryCount
    ) {
    }
}
