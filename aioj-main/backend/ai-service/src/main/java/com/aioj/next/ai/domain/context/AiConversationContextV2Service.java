package com.aioj.next.ai.domain.context;

import com.aioj.next.ai.domain.AiChatContext;
import com.aioj.next.ai.domain.AiCompletion;
import com.aioj.next.ai.domain.AiContextBudgetService;
import com.aioj.next.ai.domain.AiModelConfigResolver;
import com.aioj.next.ai.domain.AiModelContextWindowRegistry;
import com.aioj.next.ai.domain.AiModelEffectiveConfig;
import com.aioj.next.ai.domain.AiModelScope;
import com.aioj.next.ai.domain.AiRetrievalService;
import com.aioj.next.ai.config.AiProperties;
import com.aioj.next.ai.persistence.entity.AiClarificationAnswerEntity;
import com.aioj.next.ai.persistence.entity.AiClarificationRequestEntity;
import com.aioj.next.ai.persistence.entity.AiCodeSnapshotEntity;
import com.aioj.next.ai.persistence.entity.AiConversationEntity;
import com.aioj.next.ai.persistence.entity.AiConversationSummaryEntity;
import com.aioj.next.ai.persistence.entity.AiConversationTaskStateEntity;
import com.aioj.next.ai.persistence.entity.AiMessageEntity;
import com.aioj.next.ai.persistence.mapper.AiClarificationAnswerMapper;
import com.aioj.next.ai.persistence.mapper.AiClarificationRequestMapper;
import com.aioj.next.ai.persistence.mapper.AiCodeSnapshotMapper;
import com.aioj.next.ai.persistence.mapper.AiConversationMapper;
import com.aioj.next.ai.persistence.mapper.AiConversationSummaryMapper;
import com.aioj.next.ai.persistence.mapper.AiConversationTaskStateMapper;
import com.aioj.next.ai.persistence.mapper.AiMessageMapper;
import com.aioj.next.common.error.DomainException;
import com.aioj.next.common.error.ErrorCode;
import com.aioj.next.contract.ai.AiConversationContextDebugResponse;
import com.aioj.next.contract.ai.AiChatRequest;
import com.baomidou.mybatisplus.core.conditions.query.QueryWrapper;
import com.baomidou.mybatisplus.core.conditions.update.UpdateWrapper;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.time.Instant;
import java.util.HexFormat;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.locks.ReentrantLock;

@Service
public class AiConversationContextV2Service {
    private static final int TASK_STATE_MAX_ATTEMPTS = 2;
    private static final int MAX_TASK_STATE_LOCKS = 10_000;

    private final AiConversationTaskStateMapper taskStateMapper;
    private final AiConversationSummaryMapper summaryMapper;
    private final AiCodeSnapshotMapper codeSnapshotMapper;
    private final AiClarificationRequestMapper clarificationRequestMapper;
    private final AiClarificationAnswerMapper clarificationAnswerMapper;
    private final AiMessageMapper messageMapper;
    private final AiConversationMapper conversationMapper;
    private final AiRetrievalService retrievalService;
    private final ObjectMapper objectMapper;
    private final ConversationStateMerger stateMerger;
    private final ConversationContextPackBuilder contextPackBuilder;
    private final ConversationCompressionService compressionService;
    private final ClarificationDeduplicator clarificationDeduplicator;
    private final ClarificationPolicy clarificationPolicy;
    private final AiTeachingStrategyRouter teachingStrategyRouter;
    private final AiContextReportBuilder contextReportBuilder;
    private final AiContextBudgetService contextBudgetService;
    private final AiModelConfigResolver modelConfigResolver;
    /**
     * Per-conversation in-process locks reducing task-state CAS conflicts between turns of
     * the same conversation. Optimization only — NOT a consistency source: the state_version
     * CAS on ai_conversation_task_states is. A multi-instance deployment must replace this
     * with a DB or distributed lock. Soft-capped and lazily cleaned when uncontended.
     */
    private final Map<String, ReentrantLock> taskStateLocks = new ConcurrentHashMap<>();

    @Autowired
    public AiConversationContextV2Service(
            AiConversationTaskStateMapper taskStateMapper,
            AiConversationSummaryMapper summaryMapper,
            AiCodeSnapshotMapper codeSnapshotMapper,
            AiClarificationRequestMapper clarificationRequestMapper,
            AiClarificationAnswerMapper clarificationAnswerMapper,
            AiMessageMapper messageMapper,
            AiConversationMapper conversationMapper,
            AiRetrievalService retrievalService,
            ObjectMapper objectMapper,
            ConversationStateMerger stateMerger,
            ConversationContextPackBuilder contextPackBuilder,
            ConversationCompressionService compressionService,
            ClarificationDeduplicator clarificationDeduplicator,
            ClarificationPolicy clarificationPolicy,
            AiTeachingStrategyRouter teachingStrategyRouter,
            AiContextReportBuilder contextReportBuilder,
            AiContextBudgetService contextBudgetService,
            AiModelConfigResolver modelConfigResolver
    ) {
        this.taskStateMapper = taskStateMapper;
        this.summaryMapper = summaryMapper;
        this.codeSnapshotMapper = codeSnapshotMapper;
        this.clarificationRequestMapper = clarificationRequestMapper;
        this.clarificationAnswerMapper = clarificationAnswerMapper;
        this.messageMapper = messageMapper;
        this.conversationMapper = conversationMapper;
        this.retrievalService = retrievalService;
        this.objectMapper = objectMapper;
        this.stateMerger = stateMerger;
        this.contextPackBuilder = contextPackBuilder;
        this.compressionService = compressionService;
        this.clarificationDeduplicator = clarificationDeduplicator;
        this.clarificationPolicy = clarificationPolicy;
        this.teachingStrategyRouter = teachingStrategyRouter;
        this.contextReportBuilder = contextReportBuilder;
        this.contextBudgetService = contextBudgetService;

        this.modelConfigResolver = modelConfigResolver;
    }

    public AiConversationContextV2Service(
            AiConversationTaskStateMapper taskStateMapper,
            AiConversationSummaryMapper summaryMapper,
            AiCodeSnapshotMapper codeSnapshotMapper,
            AiClarificationRequestMapper clarificationRequestMapper,
            AiClarificationAnswerMapper clarificationAnswerMapper,
            AiMessageMapper messageMapper,
            AiConversationMapper conversationMapper,
            AiRetrievalService retrievalService,
            ObjectMapper objectMapper,
            ConversationStateMerger stateMerger,
            ConversationContextPackBuilder contextPackBuilder,
            ConversationCompressionService compressionService,
            ClarificationDeduplicator clarificationDeduplicator
    ) {
        this(
                taskStateMapper,
                summaryMapper,
                codeSnapshotMapper,
                clarificationRequestMapper,
                clarificationAnswerMapper,
                messageMapper,
                conversationMapper,
                retrievalService,
                objectMapper,
                stateMerger,
                contextPackBuilder,
                compressionService,
                clarificationDeduplicator,
                new ClarificationPolicy(),
                new AiTeachingStrategyRouter(),
                new AiContextReportBuilder(),
                defaultBudgetService(),
                defaultModelConfigResolver()
        );
    }

    @Transactional
    public void beforeTurn(Long userId, String conversationId, AiChatRequest request) {
        withTaskStateLock(conversationId, () -> {
            for (int attempt = 0; attempt < TASK_STATE_MAX_ATTEMPTS; attempt++) {
                AiConversationTaskStateEntity existing = findState(userId, conversationId);
                ConversationStateMerger.MergeResult merged = stateMerger.mergeBeforePrompt(existing == null ? null : existing.stateJson, request);
                try {
                    if (upsertState(userId, conversationId, request, null, merged.stateJson(), existing)) {
                        if (request.clarificationAnswer() != null) {
                            saveClarificationAnswer(userId, conversationId, request.clarificationAnswer(), merged.clarificationDelta());
                        }
                        return;
                    }
                } catch (DuplicateKeyException ex) {
                    // Concurrent first insert for the same (user_id, conversation_id): retry as an update.
                }
            }
            throw new DomainException(ErrorCode.CONFLICT, "Conversation task state update conflict");
        });
    }

    @Transactional
    public void afterTurn(Long userId, String conversationId, Long userMessageId, Long assistantMessageId, AiChatRequest request, AiCompletion completion, AiChatContext context) {
        completion = prepareCompletionForTurn(userId, conversationId, request, completion);
        Long codeSnapshotId = saveCodeSnapshot(userId, conversationId, userMessageId, request);
        String[] stateJsonHolder = new String[1];
        AiCompletion mergedCompletion = completion;
        withTaskStateLock(conversationId, () -> {
            for (int attempt = 0; attempt < TASK_STATE_MAX_ATTEMPTS; attempt++) {
                AiConversationTaskStateEntity existing = findState(userId, conversationId);
                String stateJson = stateMerger.mergeAfterCompletion(existing == null ? null : existing.stateJson, codeSnapshotId, request, mergedCompletion);
                try {
                    if (upsertState(userId, conversationId, request, codeSnapshotId, stateJson, existing)) {
                        stateJsonHolder[0] = stateJson;
                        return;
                    }
                } catch (DuplicateKeyException ex) {
                    // Concurrent first insert for the same (user_id, conversation_id): retry as an update.
                }
            }
            throw new DomainException(ErrorCode.CONFLICT, "Conversation task state update conflict");
        });
        saveClarificationRequest(userId, conversationId, assistantMessageId, completion);
        saveRollingSummary(userId, conversationId, userMessageId, assistantMessageId, request, completion, context, stateJsonHolder[0]);
    }

    public AiCompletion prepareCompletionForTurn(Long userId, String conversationId, AiCompletion completion) {
        return prepareCompletionForTurn(userId, conversationId, null, completion);
    }

    public AiCompletion prepareCompletionForTurn(Long userId, String conversationId, AiChatRequest request, AiCompletion completion) {
        if (completion == null || !completion.hasClarification()) {
            return completion;
        }
        AiConversationTaskStateEntity state = findState(userId, conversationId);
        Map<String, Object> stateMap = stateMerger.readState(state == null ? null : state.stateJson);
        AiTeachingStrategyRouter.StrategyDecision strategy = teachingStrategyRouter.route(request, stateMap);
        completion = clarificationPolicy.apply(completion, request, stateMap, strategy);
        if (completion == null || !completion.hasClarification()) {
            return completion;
        }
        ClarificationDeduplicator.DedupDecision decision = clarificationDeduplicator.decide(completion.clarification(), stateMap);
        if (decision.skip()) {
            return new AiCompletion(
                    completion.content(),
                    completion.provider(),
                    completion.model(),
                    completion.promptTokens(),
                    completion.completionTokens(),
                    completion.teachingDecision(),
                    completion.stuckLayer(),
                    completion.studentLevel(),
                    AiCompletion.Clarification.empty()
            );
        }
        if (decision.followUp()) {
            AiCompletion.Clarification current = completion.clarification();
            String question = decision.followUpQuestion();
            AiCompletion.Clarification followUp = new AiCompletion.Clarification(
                    "clarify_follow_up_" + Integer.toHexString(normalize(question).hashCode()),
                    firstNonBlank(current.priority(), "helpful"),
                    firstNonBlank(current.title(), "继续确认 check(d)"),
                    question,
                    current.input(),
                    current.options(),
                    firstNonBlank(current.defaultAction(), "ask_user"),
                    current.assumption()
            );
            return new AiCompletion(
                    completion.content(),
                    completion.provider(),
                    completion.model(),
                    completion.promptTokens(),
                    completion.completionTokens(),
                    completion.teachingDecision(),
                    completion.stuckLayer(),
                    completion.studentLevel(),
                    followUp
            );
        }
        return completion;
    }

    public String contextPack(Long userId, String conversationId) {
        return contextPack(userId, conversationId, null, "", "");
    }

    public String contextPack(Long userId, String conversationId, AiChatRequest request, String longTermMemories, String retrievedHistory) {
        return contextPack(userId, conversationId, request, longTermMemories, retrievedHistory, "");
    }

    public String contextPack(Long userId, String conversationId, AiChatRequest request, String longTermMemories, String retrievedHistory,
                              String resolvedReferenceBlock) {
        AiConversationTaskStateEntity state = findState(userId, conversationId);
        String stateJson = state == null ? "" : state.stateJson;
        if ((stateJson == null || stateJson.isBlank()) && request == null) {
            return "";
        }
        return contextPackBuilder.build(
                request == null ? new AiChatRequest(conversationId, null, "", "hint", null, null, null) : request,
                stateJson,
                recentMessages(userId, conversationId, 8),
                recentSummaries(userId, conversationId, 4),
                longTermMemories,
                retrievedHistory,
                resolvedReferenceBlock
        );
    }

    /**
     * W1.7: records which problem set is focused after a problem row was (re)registered this
     * turn. activeProblemSetId follows the set of the latest message; when it changes, the
     * previous active set becomes lastProblemSetId and is never cleared by topic switches.
     * Null setId (legacy rows without ordinals) is a no-op. Own locked read-merge-write with
     * the same state_version CAS discipline as beforeTurn/afterTurn.
     */
    @Transactional
    public void patchProblemSetFocus(Long userId, String conversationId, String newActiveSetId) {
        if (newActiveSetId == null || newActiveSetId.isBlank()) {
            return;
        }
        withTaskStateLock(conversationId, () -> {
            for (int attempt = 0; attempt < TASK_STATE_MAX_ATTEMPTS; attempt++) {
                AiConversationTaskStateEntity existing = findState(userId, conversationId);
                if (existing == null) {
                    return;
                }
                Map<String, Object> state = stateMerger.readState(existing.stateJson);
                String oldActive = state.get("activeProblemSetId") instanceof String value && !value.isBlank() ? value : null;
                if (newActiveSetId.equals(oldActive)) {
                    return;
                }
                state.put("activeProblemSetId", newActiveSetId);
                if (oldActive != null) {
                    state.put("lastProblemSetId", oldActive);
                }
                existing.stateJson = stateMerger.writeJson(state);
                existing.updatedAt = LocalDateTime.now();
                if (taskStateMapper.updateById(existing) > 0) {
                    return;
                }
            }
            throw new DomainException(ErrorCode.CONFLICT, "Conversation task state update conflict");
        });
    }

    @Transactional
    public PreProviderCompressionResult compactBeforeProvider(Long userId, String conversationId) {
        AiConversationTaskStateEntity state = findState(userId, conversationId);
        String stateJson = state == null || state.stateJson == null || state.stateJson.isBlank() ? "{}" : state.stateJson;
        Long latestCompactEnd = latestCompactEndMessageId(userId, conversationId);
        List<AiMessageEntity> window = oldestUncompactedMessages(userId, conversationId, latestCompactEnd, 14);
        if (window.isEmpty()) {
            return new PreProviderCompressionResult(false, "NO_UNCOMPACTED_MESSAGES", null, null, null);
        }
        ConversationCompressionService.CompressionResult compact = compressionService.compact(stateJson, window);
        Long from = window.get(0).getId();
        Long to = window.get(window.size() - 1).getId();
        if (!compact.valid()) {
            String warning = compact.warnings().isEmpty()
                    ? "COMPRESSION_INVALID"
                    : compact.warnings().stream().map(ConversationCompressionService.Warning::code).toList().toString();
            return new PreProviderCompressionResult(false, warning, compact.tokenEstimate(), string(from), string(to));
        }
        LocalDateTime now = LocalDateTime.now();
        insertSummary(userId, conversationId, "compact", compact.narrativeSummary(),
                compact.structuredSummaryJson(), from, to, now, compact.tokenEstimate());
        return new PreProviderCompressionResult(true, null, compact.tokenEstimate(), string(from), string(to));
    }

    public AiConversationContextDebugResponse contextDebug(Long userId, String conversationId, String longTermMemories, String retrievedHistory) {
        return contextDebug(userId, conversationId, longTermMemories, retrievedHistory, List.of());
    }

    public AiConversationContextDebugResponse contextDebug(
            Long userId,
            String conversationId,
            String longTermMemories,
            String retrievedHistory,
            List<Map<String, Object>> retrievalHits
    ) {
        AiConversationTaskStateEntity stateEntity = findState(userId, conversationId);
        Map<String, Object> state = sanitizeDebugMap(stateMerger.readState(stateEntity == null ? null : stateEntity.stateJson));
        String debugStateJson = toJson(state);
        List<AiMessageEntity> recentMessages = recentMessages(userId, conversationId, 10);
        List<AiConversationSummaryEntity> summaries = recentSummaries(userId, conversationId, 8);
        String safeLongTermMemories = filterDisabledMemoryBlocks(longTermMemories);
        String safeRetrievedHistory = sanitizeDebugText(retrievedHistory);
        List<AiClarificationRequestEntity> pending = clarificationRequestMapper.selectList(new QueryWrapper<AiClarificationRequestEntity>()
                .eq("user_id", userId)
                .eq("conversation_id", conversationId)
                .eq("status", "PENDING")
                .orderByAsc("created_at"));
        List<AiClarificationAnswerEntity> answered = clarificationAnswerMapper.selectList(new QueryWrapper<AiClarificationAnswerEntity>()
                .eq("user_id", userId)
                .eq("conversation_id", conversationId)
                .orderByAsc("created_at"));
        List<AiConversationContextDebugResponse.Warning> warnings = new ArrayList<>();
        List<AiConversationContextDebugResponse.SummarySegment> summarySegments = new ArrayList<>();
        for (AiConversationSummaryEntity summary : summaries) {
            Map<String, Object> structured = sanitizeDebugMap(readMap(summary.structuredSummary));
            double salience = salienceScore(structured);
            if ("compact".equalsIgnoreCase(summary.summaryType)) {
                warnings.addAll(summaryWarnings(summary, structured, salience));
            }
            summarySegments.add(new AiConversationContextDebugResponse.SummarySegment(
                    string(summary.id),
                    summary.summaryType,
                    string(summary.messageStartId),
                    string(summary.messageEndId),
                    truncate(sanitizeDebugText(summary.narrativeSummary), 700),
                    structured,
                    salience,
                    summary.tokenEstimate == null ? 0 : summary.tokenEstimate,
                    "ACTIVE",
                    toInstant(summary.createdAt)
            ));
        }
        AiChatRequest debugRequest = new AiChatRequest(conversationId, null, "[context-debug preview]", "hint", null, null, null);
        String contextPackPreview = contextPackBuilder.build(
                debugRequest,
                debugStateJson,
                recentMessages,
                summaries,
                safeLongTermMemories,
                safeRetrievedHistory
        );
        contextPackPreview = sanitizeDebugText(contextPackPreview);
        AiConversationContextDebugResponse.TokenEstimate tokenEstimate = tokenEstimate(
                recentMessages,
                debugStateJson,
                summaries,
                safeLongTermMemories
        );
        AiContextBuildReport report = debugContextReport(
                state,
                recentMessages,
                summarySegments,
                safeLongTermMemories,
                safeRetrievedHistory,
                retrievalHits,
                contextPackPreview
        );
        String model = modelConfigResolver.effectiveConfig(AiModelScope.TEXT_GENERATION).model();
        AiContextBudgetService.BudgetEvaluation budget = contextBudgetService.evaluate(model, debugRequest, report, contextPackPreview);
        report = report.withBudget(contextBudgetService.report(budget, budget, false, List.of(), List.of(), List.of()));
        AiConversationContextDebugResponse.ContextBuildReport debugReport = toDebugReport(report);
        return new AiConversationContextDebugResponse(
                conversationId,
                String.valueOf(userId),
                state,
                recentMessages.stream().map(message -> new AiConversationContextDebugResponse.RecentMessage(
                        string(message.getId()),
                        message.getRole(),
                        truncate(sanitizeDebugText(message.getContent()), 320),
                        toInstant(message.getCreatedAt())
                )).toList(),
                pending.stream().map(request -> new AiConversationContextDebugResponse.ClarificationDebug(
                        string(request.id),
                        request.requestKey,
                        truncate(sanitizeDebugText(request.question), 320),
                        request.priority,
                        request.status,
                        sanitizeDebugMap(readMap(request.inputSchema)),
                        toInstant(request.createdAt),
                        toInstant(request.answeredAt)
                )).toList(),
                answered.stream().map(answer -> new AiConversationContextDebugResponse.ClarificationAnswerDebug(
                        string(answer.id),
                        string(answer.requestId),
                        answer.requestKey,
                        truncate(sanitizeDebugText(answer.question), 320),
                        truncate(sanitizeDebugText(answer.answerText), 320),
                        sanitizeDebugMap(readMap(answer.interpretedDeltaJson)),
                        Boolean.TRUE.equals(answer.mergedToState),
                        toInstant(answer.createdAt)
                )).toList(),
                summarySegments,
                truncate(contextPackPreview, 5000),
                debugReport.sections(),
                debugReport.sourceSummary(),
                debugReport,
                tokenEstimate,
                warnings
        );
    }

    private AiConversationTaskStateEntity findState(Long userId, String conversationId) {
        return taskStateMapper.selectOne(new QueryWrapper<AiConversationTaskStateEntity>()
                .eq("user_id", userId)
                .eq("conversation_id", conversationId)
                .last("LIMIT 1"));
    }

    /**
     * Read-modify-write guarded by the state_version optimistic CAS (@Version on updateById):
     * returns false when the row changed underneath us. Callers re-read, re-merge and retry
     * once before surfacing CONFLICT.
     */
    private boolean upsertState(Long userId, String conversationId, AiChatRequest request, Long codeSnapshotId, String stateJson,
                                AiConversationTaskStateEntity entity) {
        LocalDateTime now = LocalDateTime.now();
        boolean isNew = entity == null;
        if (isNew) {
            entity = new AiConversationTaskStateEntity();
            entity.userId = userId;
            entity.conversationId = conversationId;
            entity.stateVersion = 0L;
            entity.createdAt = now;
        }
        Long problemId = resolveProblemId(request);
        entity.currentProblemId = problemId == null ? entity.currentProblemId : problemId;
        entity.currentGoal = inferGoal(request);
        entity.language = request.codeContext() == null ? entity.language : normalize(request.codeContext().language());
        entity.latestCodeSnapshotId = codeSnapshotId == null ? entity.latestCodeSnapshotId : codeSnapshotId;
        entity.latestErrorJson = toJson(latestError(request.message()));
        entity.stateJson = stateJson == null || stateJson.isBlank() ? "{}" : stateJson;
        entity.updatedAt = now;
        if (isNew) {
            taskStateMapper.insert(entity);
            return true;
        }
        return taskStateMapper.updateById(entity) > 0;
    }

    /**
     * Runs the task-state read-merge-write under the per-conversation in-process lock.
     * Optimization only — NOT a consistency source (the state_version CAS is); a
     * multi-instance deployment must replace this with a DB or distributed lock.
     * Never held across the model call: only beforeTurn/afterTurn short writes take it.
     */
    private void withTaskStateLock(String conversationId, Runnable action) {
        ReentrantLock lock = taskStateLocks.get(conversationId);
        if (lock == null && taskStateLocks.size() < MAX_TASK_STATE_LOCKS) {
            lock = taskStateLocks.computeIfAbsent(conversationId, key -> new ReentrantLock());
        }
        if (lock == null) {
            // Soft cap exceeded: skip the lock; correctness still comes from the CAS.
            action.run();
            return;
        }
        lock.lock();
        try {
            action.run();
        } finally {
            lock.unlock();
            // Lazy cleanup: drop the entry once no thread holds or waits on it.
            taskStateLocks.computeIfPresent(conversationId,
                    (key, candidate) -> candidate.isLocked() || candidate.hasQueuedThreads() ? candidate : null);
        }
    }

    private void saveClarificationAnswer(Long userId, String conversationId, AiChatRequest.ClarificationAnswer answer, Map<String, Object> delta) {
        LocalDateTime now = LocalDateTime.now();
        AiClarificationRequestEntity request = findPendingClarification(userId, conversationId, answer);
        if (request != null) {
            request.status = "ANSWERED";
            request.answerJson = toJson(Map.of(
                    "answerText", firstNonBlank(answer.answerText(), answer.customText()),
                    "selectedOptionIds", answer.selectedOptionIds() == null ? List.of() : answer.selectedOptionIds(),
                    "interpretedDelta", delta == null ? Map.of() : delta
            ));
            request.answeredAt = now;
            clarificationRequestMapper.updateById(request);
        }

        AiClarificationAnswerEntity entity = new AiClarificationAnswerEntity();
        entity.userId = userId;
        entity.conversationId = conversationId;
        entity.requestId = request == null ? null : request.id;
        entity.requestKey = firstNonBlank(answer.requestId(), request == null ? null : request.requestKey);
        entity.question = firstNonBlank(answer.question(), request == null ? null : request.question);
        entity.answerText = firstNonBlank(answer.answerText(), answer.customText());
        entity.selectedOptionIdsJson = toJson(answer.selectedOptionIds() == null ? List.of() : answer.selectedOptionIds());
        entity.interpretedDeltaJson = toJson(delta == null ? Map.of() : delta);
        entity.mergedToState = Boolean.TRUE;
        entity.createdAt = now;
        clarificationAnswerMapper.insert(entity);
    }

    private AiClarificationRequestEntity findPendingClarification(Long userId, String conversationId, AiChatRequest.ClarificationAnswer answer) {
        QueryWrapper<AiClarificationRequestEntity> query = new QueryWrapper<AiClarificationRequestEntity>()
                .eq("user_id", userId)
                .eq("conversation_id", conversationId)
                .eq("status", "PENDING");
        if (answer.requestId() != null && !answer.requestId().isBlank()) {
            query.eq("request_key", answer.requestId().trim());
        } else if (answer.question() != null && !answer.question().isBlank()) {
            query.like("question", truncate(answer.question(), 120));
        }
        return clarificationRequestMapper.selectOne(query.orderByDesc("created_at").last("LIMIT 1"));
    }

    private void saveClarificationRequest(Long userId, String conversationId, Long assistantMessageId, AiCompletion completion) {
        if (!completion.hasClarification()) {
            return;
        }
        AiCompletion.Clarification clarification = completion.clarification();
        String requestKey = firstNonBlank(clarification.id(), "clarify_" + Integer.toHexString(normalize(clarification.prompt()).hashCode()));
        AiClarificationRequestEntity existing = clarificationRequestMapper.selectOne(new QueryWrapper<AiClarificationRequestEntity>()
                .eq("user_id", userId)
                .eq("conversation_id", conversationId)
                .eq("request_key", requestKey)
                .eq("status", "PENDING")
                .last("LIMIT 1"));
        if (existing != null) {
            return;
        }
        LocalDateTime now = LocalDateTime.now();
        AiClarificationRequestEntity entity = new AiClarificationRequestEntity();
        entity.userId = userId;
        entity.conversationId = conversationId;
        entity.sourceMessageId = assistantMessageId;
        entity.requestKey = requestKey;
        entity.priority = firstNonBlank(clarification.priority(), "helpful");
        entity.question = firstNonBlank(clarification.prompt(), clarification.title());
        entity.inputSchema = toJson(clarification.input());
        entity.defaultAction = clarification.defaultAction();
        entity.assumption = clarification.assumption();
        entity.status = "PENDING";
        entity.createdAt = now;
        clarificationRequestMapper.insert(entity);
    }

    private Long saveCodeSnapshot(Long userId, String conversationId, Long userMessageId, AiChatRequest request) {
        AiChatRequest.CodeContext code = request.codeContext();
        if (code == null || code.code() == null || code.code().isBlank() || userMessageId == null) {
            return null;
        }
        // Transition-period dual write: snapshots are immutable inserts; is_latest is still
        // maintained for legacy readers while ai_conversations.current_snapshot_id becomes
        // the pointer new readers use. The old column is intentionally kept.
        codeSnapshotMapper.update(
                new AiCodeSnapshotEntity(),
                new UpdateWrapper<AiCodeSnapshotEntity>()
                        .eq("user_id", userId)
                        .eq("conversation_id", conversationId)
                        .set("is_latest", 0)
        );
        AiCodeSnapshotEntity snapshot = new AiCodeSnapshotEntity();
        snapshot.userId = userId;
        snapshot.conversationId = conversationId;
        snapshot.messageId = userMessageId;
        snapshot.language = normalize(code.language());
        snapshot.codeText = code.code();
        snapshot.codeHash = sha256(code.code());
        snapshot.codeSummary = summarizeCode(code.code());
        snapshot.isLatest = Boolean.TRUE;
        snapshot.createdAt = LocalDateTime.now();
        codeSnapshotMapper.insert(snapshot);
        conversationMapper.update(null, new UpdateWrapper<AiConversationEntity>()
                .eq("id", conversationId)
                .set("current_snapshot_id", String.valueOf(snapshot.id)));
        retrievalService.indexChunk(
                userId,
                "code_snapshot",
                String.valueOf(snapshot.id),
                codeSnapshotRetrievalText(snapshot),
                retrievalMetadata(request, "code_snapshot", Map.of(
                        "language", snapshot.language == null ? "" : snapshot.language,
                        "codeHash", snapshot.codeHash == null ? "" : snapshot.codeHash,
                        "lines", lineCount(code.code())
                ))
        );
        return snapshot.id;
    }

    private void saveRollingSummary(Long userId, String conversationId, Long userMessageId, Long assistantMessageId, AiChatRequest request, AiCompletion completion, AiChatContext context, String stateJson) {
        LocalDateTime now = LocalDateTime.now();
        String narrative = "用户：" + truncate(request.message(), 500) + "\nAI：" + truncate(completion.content(), 700);
        Map<String, Object> structured = new LinkedHashMap<>();
        structured.put("current_goal", inferGoal(request));
        structured.put("problem_id", resolveProblemId(request));
        structured.put("language", request.codeContext() == null ? null : request.codeContext().language());
        structured.put("clarification_answer", request.clarificationAnswer() == null ? null : request.clarificationAnswer().answerText());
        structured.put("has_code_snapshot", request.codeContext() != null && request.codeContext().code() != null && !request.codeContext().code().isBlank());
        structured.put("context_pack_available", context != null && context.hasContent());
        insertSummary(userId, conversationId, "rolling", narrative, toJson(structured), userMessageId, assistantMessageId, now,
                retrievalMetadata(request, "conversation_summary", Map.of("summaryType", "rolling")));

        List<AiMessageEntity> recent = recentMessages(userId, conversationId, 14);
        if (compressionService.shouldCompact(recent, stateJson, request.clarificationAnswer() != null)) {
            ConversationCompressionService.CompressionResult compact = compressionService.compact(stateJson, recent);
            if (compact.valid()) {
                Long from = recent.isEmpty() ? userMessageId : recent.get(0).getId();
                Long to = recent.isEmpty() ? assistantMessageId : recent.get(recent.size() - 1).getId();
                insertSummary(userId, conversationId, "compact", compact.narrativeSummary(), compact.structuredSummaryJson(), from, to, now, compact.tokenEstimate(),
                        retrievalMetadata(request, "conversation_summary", Map.of("summaryType", "compact")));
            }
        }
    }

    private void insertSummary(Long userId, String conversationId, String type, String narrative, String structuredJson, Long startId, Long endId, LocalDateTime now) {
        insertSummary(userId, conversationId, type, narrative, structuredJson, startId, endId, now, null, AiRetrievalService.AiRetrievalChunkMetadata.safe());
    }

    private void insertSummary(Long userId, String conversationId, String type, String narrative, String structuredJson, Long startId, Long endId, LocalDateTime now, AiRetrievalService.AiRetrievalChunkMetadata retrievalMetadata) {
        insertSummary(userId, conversationId, type, narrative, structuredJson, startId, endId, now, null, retrievalMetadata);
    }

    private void insertSummary(Long userId, String conversationId, String type, String narrative, String structuredJson, Long startId, Long endId, LocalDateTime now, Integer explicitTokenEstimate) {
        insertSummary(userId, conversationId, type, narrative, structuredJson, startId, endId, now, explicitTokenEstimate, AiRetrievalService.AiRetrievalChunkMetadata.safe());
    }

    private void insertSummary(Long userId, String conversationId, String type, String narrative, String structuredJson, Long startId, Long endId, LocalDateTime now, Integer explicitTokenEstimate, AiRetrievalService.AiRetrievalChunkMetadata retrievalMetadata) {
        AiConversationSummaryEntity summary = new AiConversationSummaryEntity();
        summary.userId = userId;
        summary.conversationId = conversationId;
        summary.summaryType = type;
        summary.narrativeSummary = narrative;
        summary.structuredSummary = structuredJson;
        summary.messageStartId = startId;
        summary.messageEndId = endId;
        summary.tokenEstimate = explicitTokenEstimate == null ? Math.max(1, (narrative.length() + 3) / 4) : Math.max(1, explicitTokenEstimate);
        summary.embeddingOwnerType = "conversation_summary";
        summary.embeddingOwnerId = conversationId + ":" + type + ":" + (endId == null ? now.toString() : endId);
        summary.createdAt = now;
        summary.updatedAt = now;
        summaryMapper.insert(summary);
        retrievalService.indexChunk(userId, "conversation_summary", String.valueOf(summary.id), narrative, retrievalMetadata);
    }

    private List<AiMessageEntity> recentMessages(Long userId, String conversationId, int limit) {
        List<AiMessageEntity> messages = messageMapper.selectList(new QueryWrapper<AiMessageEntity>()
                .eq("user_id", userId)
                .eq("conversation_id", conversationId)
                .orderByDesc("created_at")
                .last("LIMIT " + Math.max(1, limit)));
        List<AiMessageEntity> ordered = new ArrayList<>(messages);
        Collections.reverse(ordered);
        return ordered;
    }

    private Long latestCompactEndMessageId(Long userId, String conversationId) {
        AiConversationSummaryEntity latest = summaryMapper.selectOne(new QueryWrapper<AiConversationSummaryEntity>()
                .eq("user_id", userId)
                .eq("conversation_id", conversationId)
                .eq("summary_type", "compact")
                .orderByDesc("message_end_id")
                .orderByDesc("created_at")
                .last("LIMIT 1"));
        return latest == null ? null : latest.messageEndId;
    }

    private List<AiMessageEntity> oldestUncompactedMessages(Long userId, String conversationId, Long afterMessageId, int limit) {
        QueryWrapper<AiMessageEntity> query = new QueryWrapper<AiMessageEntity>()
                .eq("user_id", userId)
                .eq("conversation_id", conversationId);
        if (afterMessageId != null) {
            query.gt("id", afterMessageId);
        }
        return messageMapper.selectList(query.orderByAsc("created_at").last("LIMIT " + Math.max(1, limit)));
    }

    private List<AiConversationSummaryEntity> recentSummaries(Long userId, String conversationId, int limit) {
        List<AiConversationSummaryEntity> summaries = summaryMapper.selectList(new QueryWrapper<AiConversationSummaryEntity>()
                .eq("user_id", userId)
                .eq("conversation_id", conversationId)
                .orderByDesc("created_at")
                .last("LIMIT " + Math.max(1, limit)));
        List<AiConversationSummaryEntity> ordered = new ArrayList<>(summaries);
        Collections.reverse(ordered);
        return ordered;
    }

    public record PreProviderCompressionResult(
            boolean applied,
            String warning,
            Integer tokenEstimate,
            String messageStartId,
            String messageEndId
    ) {
    }

    private static AiContextBudgetService defaultBudgetService() {
        AiProperties properties = new AiProperties();
        return new AiContextBudgetService(properties, new AiModelContextWindowRegistry(properties));
    }

    private static AiModelConfigResolver defaultModelConfigResolver() {
        return scope -> new AiModelEffectiveConfig(
                scope == null ? AiModelScope.TEXT_GENERATION : scope,
                true,
                true,
                "test",
                "mock",
                "",
                "",
                "",
                "",
                "",
                "mock-64k",
                false,
                false,
                "high",
                null,
                null,
                null,
                Instant.now(),
                null
        );
    }

    private AiConversationContextDebugResponse.TokenEstimate tokenEstimate(
            List<AiMessageEntity> recentMessages,
            String stateJson,
            List<AiConversationSummaryEntity> summaries,
            String longTermMemories
    ) {
        int recent = estimateTokens(recentMessages == null ? "" : recentMessages.stream()
                .map(AiMessageEntity::getContent)
                .filter(content -> content != null && !content.isBlank())
                .reduce("", (left, right) -> left + "\n" + right));
        int state = estimateTokens(stateJson);
        int summary = estimateTokens(summaries == null ? "" : summaries.stream()
                .map(item -> normalize(item.narrativeSummary) + "\n" + normalize(item.structuredSummary))
                .reduce("", (left, right) -> left + "\n" + right));
        int memory = estimateTokens(longTermMemories);
        return new AiConversationContextDebugResponse.TokenEstimate(recent, state, summary, memory, recent + state + summary + memory);
    }

    private List<AiConversationContextDebugResponse.Warning> summaryWarnings(AiConversationSummaryEntity summary, Map<String, Object> structured, double salience) {
        List<AiConversationContextDebugResponse.Warning> warnings = new ArrayList<>();
        if (structured.isEmpty()) {
            warnings.add(warning("WARN", "EMPTY_STRUCTURED_SUMMARY", "Compact summary has empty structuredSummary."));
        }
        if (salience <= 0) {
            warnings.add(warning("WARN", "ZERO_SALIENCE_SCORE", "Compact summary has salienceScore <= 0."));
        }
        if (summary.tokenEstimate == null || summary.tokenEstimate <= 0) {
            warnings.add(warning("WARN", "ZERO_TOKEN_ESTIMATE", "Compact summary has tokenEstimate <= 0."));
        }
        if (list(structured.get("keptSegments")).isEmpty()) {
            warnings.add(warning("WARN", "EMPTY_KEPT_SEGMENTS", "Compact summary has no kept high-value segments."));
        }
        return warnings;
    }

    private AiConversationContextDebugResponse.Warning warning(String level, String code, String message) {
        return new AiConversationContextDebugResponse.Warning(level, code, message);
    }

    private AiContextBuildReport debugContextReport(
            Map<String, Object> state,
            List<AiMessageEntity> recentMessages,
            List<AiConversationContextDebugResponse.SummarySegment> summarySegments,
            String longTermMemories,
            String retrievedHistory,
            List<Map<String, Object>> retrievalHits,
            String contextPackPreview
    ) {
        List<AiContextSection> sections = contextReportBuilder.mutableSections();
        Map<String, Object> problem = map(state.get("problem"));
        if (!problem.isEmpty()) {
            sections.add(contextReportBuilder.section(
                    "debug.state.problem",
                    "problem_context",
                    "Problem state",
                    90,
                    "ai-service.state",
                    "internal",
                    true,
                    toJson(problem),
                    problem
            ));
        }
        Map<String, Object> codeState = map(state.get("codeState"));
        if (codeState.get("latestSubmissionId") != null) {
            sections.add(contextReportBuilder.section(
                    "debug.state.submission",
                    "submission_context",
                    "Latest submission focus",
                    88,
                    "ai-service.state",
                    "submission_safe",
                    true,
                    toJson(codeState),
                    codeState
            ));
        }
        sections.add(contextReportBuilder.section(
                "debug.state",
                "conversation_state",
                "Current conversation state",
                86,
                "ai-service.state",
                "internal",
                true,
                toJson(state),
                Map.of()
        ));
        sections.add(contextReportBuilder.section(
                "debug.recent_messages",
                "recent_messages",
                "Recent messages",
                60,
                "ai-service.messages",
                "internal",
                false,
                recentMessages == null ? "" : recentMessages.stream()
                        .map(message -> message.getRole() + ": " + sanitizeDebugText(message.getContent()))
                        .reduce("", (left, right) -> left + "\n" + right),
                Map.of("count", recentMessages == null ? 0 : recentMessages.size())
        ));
        sections.add(contextReportBuilder.section(
                "debug.summaries",
                "compressed_summaries",
                "Compressed summaries",
                58,
                "ai-service.summary",
                "internal",
                false,
                summarySegments == null ? "" : summarySegments.stream()
                        .map(segment -> segment.summaryType() + ": " + segment.narrativeSummary())
                        .reduce("", (left, right) -> left + "\n" + right),
                Map.of("count", summarySegments == null ? 0 : summarySegments.size())
        ));
        sections.add(contextReportBuilder.section(
                "debug.memory",
                "long_term_memory",
                "Relevant long-term memories",
                55,
                "ai-service.memory",
                "memory",
                false,
                longTermMemories,
                Map.of()
        ));
        sections.add(contextReportBuilder.section(
                "debug.retrieval",
                "retrieved_history",
                "Retrieved past context",
                50,
                "ai-service.retrieval",
                "memory",
                false,
                retrievedHistory,
                retrievalHits == null || retrievalHits.isEmpty() ? Map.of() : Map.of("hits", sanitizeDebugValue(retrievalHits))
        ));
        sections.add(contextReportBuilder.section(
                "debug.context_pack_preview",
                "context_pack_preview",
                "Rendered context pack preview",
                40,
                "ai-service.debug",
                "internal",
                false,
                contextPackPreview,
                Map.of()
        ));
        return contextReportBuilder.build(sections);
    }

    private AiConversationContextDebugResponse.ContextBuildReport toDebugReport(AiContextBuildReport report) {
        if (report == null) {
            return AiConversationContextDebugResponse.ContextBuildReport.empty();
        }
        List<AiConversationContextDebugResponse.ContextSection> sections = report.sections().stream()
                .map(section -> new AiConversationContextDebugResponse.ContextSection(
                        section.id(),
                        section.type(),
                        section.title(),
                        section.priority(),
                        section.source(),
                        section.sensitivity(),
                        section.estimatedTokens(),
                        section.required(),
                        section.contentPreview(),
                        section.metadata()
                ))
                .toList();
        return new AiConversationContextDebugResponse.ContextBuildReport(
                sections,
                report.sourceSummary(),
                report.totalEstimatedTokens(),
                report.requiredEstimatedTokens(),
                report.optionalEstimatedTokens(),
                report.requiredSectionCount(),
                report.optionalSectionCount(),
                toDebugBudget(report.budget())
        );
    }

    private AiConversationContextDebugResponse.ContextBudgetReport toDebugBudget(AiContextBudgetReport budget) {
        if (budget == null) {
            return AiConversationContextDebugResponse.ContextBudgetReport.empty();
        }
        return new AiConversationContextDebugResponse.ContextBudgetReport(
                budget.model(),
                budget.modelWindowTokens(),
                budget.compressionThresholdTokens(),
                budget.maxPromptBudgetTokens(),
                budget.estimatedPromptTokensBefore(),
                budget.estimatedPromptTokensAfter(),
                budget.compressionApplied(),
                budget.trimmedSections(),
                budget.droppedSections(),
                budget.estimatedBySection(),
                budget.warnings()
        );
    }

    private double salienceScore(Map<String, Object> structured) {
        Object direct = structured.get("salienceScore");
        if (direct instanceof Number number) {
            return number.doubleValue();
        }
        Object meta = structured.get("_meta");
        if (meta instanceof Map<?, ?> metaMap) {
            Object score = metaMap.get("salienceScore");
            if (score instanceof Number number) {
                return number.doubleValue();
            }
        }
        return 0;
    }

    @SuppressWarnings("unchecked")
    private Map<String, Object> readMap(String json) {
        if (json == null || json.isBlank()) {
            return Map.of();
        }
        try {
            Object value = objectMapper.readValue(json, Object.class);
            if (value instanceof Map<?, ?> map) {
                return (Map<String, Object>) map;
            }
        } catch (Exception ignored) {
            return Map.of();
        }
        return Map.of();
    }

    @SuppressWarnings("unchecked")
    private Map<String, Object> map(Object value) {
        if (value instanceof Map<?, ?> source) {
            Map<String, Object> normalized = new LinkedHashMap<>();
            for (Map.Entry<?, ?> entry : source.entrySet()) {
                normalized.put(String.valueOf(entry.getKey()), entry.getValue());
            }
            return normalized;
        }
        return Map.of();
    }

    private Map<String, Object> sanitizeDebugMap(Map<String, Object> source) {
        if (source == null || source.isEmpty()) {
            return Map.of();
        }
        Map<String, Object> sanitized = new LinkedHashMap<>();
        for (Map.Entry<String, Object> entry : source.entrySet()) {
            if (isCodeBodyKey(entry.getKey())) {
                sanitized.put(entry.getKey(), "[code omitted in context-debug preview]");
                continue;
            }
            sanitized.put(entry.getKey(), sanitizeDebugValue(entry.getValue()));
        }
        return sanitized;
    }

    @SuppressWarnings("unchecked")
    private Object sanitizeDebugValue(Object value) {
        if (value instanceof String text) {
            return sanitizeDebugText(text);
        }
        if (value instanceof Map<?, ?> map) {
            Map<String, Object> sanitized = new LinkedHashMap<>();
            for (Map.Entry<?, ?> entry : map.entrySet()) {
                String key = String.valueOf(entry.getKey());
                if (isCodeBodyKey(key)) {
                    sanitized.put(key, "[code omitted in context-debug preview]");
                    continue;
                }
                sanitized.put(key, sanitizeDebugValue(entry.getValue()));
            }
            return sanitized;
        }
        if (value instanceof List<?> values) {
            return values.stream().map(this::sanitizeDebugValue).toList();
        }
        return value;
    }

    private String filterDisabledMemoryBlocks(String value) {
        String normalized = normalize(value);
        if (normalized.isBlank()) {
            return "";
        }
        List<String> kept = new ArrayList<>();
        boolean skippingDisabledBlock = false;
        for (String line : normalized.split("\\R")) {
            String marker = line.trim().toUpperCase();
            if (marker.contains("DISABLED")) {
                skippingDisabledBlock = true;
                continue;
            }
            if (marker.startsWith("[") && marker.endsWith("]")) {
                skippingDisabledBlock = false;
            }
            if (!skippingDisabledBlock) {
                kept.add(line);
            }
        }
        return sanitizeDebugText(String.join("\n", kept));
    }

    private String sanitizeDebugText(String value) {
        String sanitized = normalize(value);
        if (sanitized.isBlank()) {
            return "";
        }
        sanitized = sanitized.replaceAll("(?is)```.*?```", "[code block omitted in context-debug preview]");
        sanitized = sanitized.replaceAll("(?i)sk-[a-z0-9_\\-]{4,}", "sk-***");
        sanitized = sanitized.replaceAll("(?i)(api[_-]?key|token|secret|password)\\s*(?:=|:|是|为)\\s*[^\\s,;，。]+", "$1=***");
        sanitized = sanitized.replaceAll("(?i)(bearer)\\s+[a-z0-9._\\-]{8,}", "$1 ***");
        return sanitizeCodeLinesForDebug(sanitized);
    }

    private String sanitizeCodeLinesForDebug(String value) {
        String[] lines = value.split("\\R", -1);
        List<String> sanitized = new ArrayList<>();
        boolean omittedRun = false;
        for (String line : lines) {
            if (isCodeLikeLine(line)) {
                if (!omittedRun) {
                    sanitized.add("[code line omitted in context-debug preview]");
                    omittedRun = true;
                }
                continue;
            }
            omittedRun = false;
            sanitized.add(line);
        }
        return String.join("\n", sanitized);
    }

    private boolean isCodeBodyKey(String key) {
        String normalized = key == null ? "" : key.replace("_", "").replace("-", "").toLowerCase();
        return normalized.equals("code")
                || normalized.equals("codetext")
                || normalized.equals("sourcecode")
                || normalized.equals("latestcode");
    }

    private boolean isCodeLikeLine(String line) {
        String trimmed = line == null ? "" : line.trim();
        String lower = trimmed.toLowerCase();
        if (trimmed.isBlank()) {
            return false;
        }
        if (lower.contains("#include")
                || lower.contains("using namespace")
                || lower.contains("int main")
                || lower.contains("public class")
                || lower.contains("system.out")
                || lower.contains("import sys")
                || lower.contains("std::")) {
            return true;
        }
        if (lower.startsWith("def ") || lower.startsWith("class ")) {
            return true;
        }
        if ((trimmed.contains("{") || trimmed.contains("}")) && trimmed.contains(";")) {
            return true;
        }
        return trimmed.matches(".*\\b(for|while|if)\\s*\\(.*\\).*") && trimmed.contains(";");
    }

    @SuppressWarnings("unchecked")
    private List<Object> list(Object value) {
        if (value instanceof List<?> list) {
            return (List<Object>) list;
        }
        return List.of();
    }

    private int estimateTokens(String text) {
        return Math.max(0, normalize(text).length() / 4);
    }

    private java.time.Instant toInstant(LocalDateTime time) {
        return time == null ? null : time.atZone(ZoneId.systemDefault()).toInstant();
    }

    private String string(Object value) {
        return value == null ? "" : String.valueOf(value);
    }

    private Map<String, Object> latestError(String message) {
        String lower = normalize(message).toLowerCase();
        Map<String, Object> value = new LinkedHashMap<>();
        if (containsAny(lower, "wa", "wrong answer", "答案错误")) value.put("type", "WRONG_ANSWER");
        if (containsAny(lower, "tle", "超时", "time limit")) value.put("type", "TIME_LIMIT");
        if (containsAny(lower, "re", "runtime", "运行错误")) value.put("type", "RUNTIME_ERROR");
        if (containsAny(lower, "compile", "编译", "ce")) value.put("type", "COMPILE_ERROR");
        value.put("raw", truncate(message, 500));
        return value;
    }

    private Long resolveProblemId(AiChatRequest request) {
        if (request.problemId() != null) {
            return request.problemId();
        }
        if (request.problemContext() != null && request.problemContext().id() != null) {
            try {
                return Long.parseLong(request.problemContext().id());
            } catch (NumberFormatException ignored) {
                return null;
            }
        }
        return null;
    }

    private String inferGoal(AiChatRequest request) {
        String lower = normalize(request.mode()).toLowerCase() + " " + normalize(request.message()).toLowerCase();
        if (request.clarificationAnswer() != null) return "continue_after_clarification";
        if (containsAny(lower, "debug", "wa", "re", "tle", "调试", "哪里错")) return "debug_code";
        if (containsAny(lower, "edge", "边界", "反例")) return "find_edge_cases";
        if (containsAny(lower, "optimize", "优化", "复杂度")) return "optimize_solution";
        return "solve_problem";
    }

    private String codeSnapshotRetrievalText(AiCodeSnapshotEntity snapshot) {
        return "代码快照摘要：language=%s, lines=%s, codeHash=%s".formatted(
                normalize(snapshot.language),
                snapshot.codeSummary == null ? "unknown" : snapshot.codeSummary.replaceFirst(",.*$", ""),
                snapshot.codeHash == null ? "" : snapshot.codeHash
        );
    }

    private int lineCount(String code) {
        return (int) normalize(code).lines().count();
    }

    private AiRetrievalService.AiRetrievalChunkMetadata retrievalMetadata(AiChatRequest request, String source, Map<String, Object> attributes) {
        Map<String, Object> safeAttributes = new LinkedHashMap<>();
        if (source != null && !source.isBlank()) {
            safeAttributes.put("source", source);
        }
        if (attributes != null) {
            for (Map.Entry<String, Object> entry : attributes.entrySet()) {
                if (entry.getKey() != null && entry.getValue() != null) {
                    safeAttributes.put(entry.getKey(), entry.getValue());
                }
            }
        }
        return AiRetrievalService.AiRetrievalChunkMetadata.of(
                request == null ? null : resolveProblemId(request),
                request == null || request.submissionContext() == null ? null : request.submissionContext().submissionId(),
                null,
                null,
                null,
                firstProblemTag(request),
                null,
                safeAttributes
        );
    }

    private String firstProblemTag(AiChatRequest request) {
        if (request == null || request.problemContext() == null || request.problemContext().tags() == null || request.problemContext().tags().isEmpty()) {
            return null;
        }
        return request.problemContext().tags().get(0);
    }

    private String summarizeCode(String code) {
        String normalized = normalize(code);
        long lines = normalized.lines().count();
        return "lines=" + lines + ", preview=" + truncate(normalized.replaceAll("\\s+", " "), 600);
    }

    private boolean containsAny(String value, String... needles) {
        for (String needle : needles) {
            if (value.contains(needle)) return true;
        }
        return false;
    }

    private String toJson(Object value) {
        try {
            return objectMapper.writeValueAsString(value);
        } catch (Exception ignored) {
            return "{}";
        }
    }

    private String sha256(String value) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            return HexFormat.of().formatHex(digest.digest(value.getBytes(StandardCharsets.UTF_8)));
        } catch (Exception ex) {
            return Integer.toHexString(value.hashCode());
        }
    }

    private String firstNonBlank(String first, String second) {
        return first != null && !first.isBlank() ? first.trim() : normalize(second);
    }

    private String normalize(String value) {
        return value == null ? "" : value.replace("\r\n", "\n").replace('\r', '\n').trim();
    }

    private String truncate(String value, int max) {
        String normalized = normalize(value);
        return normalized.length() <= max ? normalized : normalized.substring(0, max) + "…";
    }
}
