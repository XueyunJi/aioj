package com.aioj.next.ai.domain;

import com.aioj.next.ai.config.AiProperties;
import com.aioj.next.ai.persistence.entity.AiConversationEntity;
import com.aioj.next.ai.persistence.entity.AiConversationProblemEntity;
import com.aioj.next.ai.persistence.entity.AiMessageEntity;
import com.aioj.next.ai.persistence.mapper.AiConversationMapper;
import com.aioj.next.ai.persistence.mapper.AiConversationProblemMapper;
import com.aioj.next.ai.persistence.mapper.AiMessageMapper;
import com.aioj.next.common.error.DomainException;
import com.aioj.next.common.error.ErrorCode;
import com.aioj.next.ai.domain.context.AiContextBudgetReport;
import com.aioj.next.ai.domain.context.AiContextBuildReport;
import com.aioj.next.ai.domain.context.AiContextReportBuilder;
import com.aioj.next.ai.domain.context.AiContextSection;
import com.aioj.next.ai.domain.context.AiConversationContextV2Service;
import com.aioj.next.ai.domain.context.AiReferenceResolutionService;
import com.aioj.next.contract.ai.AiChatRequest;
import com.aioj.next.contract.ai.AiConversationContextDebugResponse;
import com.aioj.next.contract.ai.AiProblemContextResponse;
import com.aioj.next.contract.ai.AiSubmissionContextResponse;
import com.aioj.next.contract.contest.ContestAiPolicyResponse;
import com.baomidou.mybatisplus.core.conditions.query.QueryWrapper;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

@Service
public class AiContextService {
    private static final Logger log = LoggerFactory.getLogger(AiContextService.class);
    /** Hard degradation never truncates the rendered pack below this size. */
    private static final int HARD_TRUNCATE_MIN_PACK_CHARS = 512;
    private static final int RELATED_SUBMISSION_CANDIDATE_LIMIT = 8;
    private static final int RELATED_SUBMISSION_MEMORY_LIMIT = 3;
    private static final int RELATED_SUBMISSION_SNIPPET_LIMIT = 520;

    private final AiConversationMapper conversationMapper;
    private final AiMessageMapper messageMapper;
    private final AiConversationProblemMapper problemMapper;
    private final AiMemoryService memoryService;
    private final AiRetrievalService retrievalService;
    private final AiLearningProfileService learningProfileService;
    private final AiConversationContextV2Service contextV2Service;
    private final AiProblemContextResolver problemContextResolver;
    private final AiSubmissionContextResolver submissionContextResolver;
    private final AiContextReportBuilder contextReportBuilder;
    private final AiContextBudgetService contextBudgetService;
    private final AiModelConfigResolver modelConfigResolver;
    private final ObjectMapper objectMapper;
    /** W1.7 reference resolution; nullable in the legacy test-only constructors. */
    private final AiReferenceResolutionService referenceResolutionService;

    @Autowired
    public AiContextService(
            AiConversationMapper conversationMapper,
            AiMessageMapper messageMapper,
            AiConversationProblemMapper problemMapper,
            AiMemoryService memoryService,
            AiRetrievalService retrievalService,
            AiLearningProfileService learningProfileService,
            AiConversationContextV2Service contextV2Service,
            AiProblemContextResolver problemContextResolver,
            AiSubmissionContextResolver submissionContextResolver,
            AiContextReportBuilder contextReportBuilder,
            AiContextBudgetService contextBudgetService,
            AiModelConfigResolver modelConfigResolver,
            ObjectMapper objectMapper,
            AiReferenceResolutionService referenceResolutionService
    ) {
        this.conversationMapper = conversationMapper;
        this.messageMapper = messageMapper;
        this.problemMapper = problemMapper;
        this.memoryService = memoryService;
        this.retrievalService = retrievalService;
        this.learningProfileService = learningProfileService;
        this.contextV2Service = contextV2Service;
        this.problemContextResolver = problemContextResolver;
        this.submissionContextResolver = submissionContextResolver;
        this.contextReportBuilder = contextReportBuilder;
        this.contextBudgetService = contextBudgetService;
        this.modelConfigResolver = modelConfigResolver;
        this.objectMapper = objectMapper;
        this.referenceResolutionService = referenceResolutionService;
    }

    public AiContextService(
            AiConversationMapper conversationMapper,
            AiMessageMapper messageMapper,
            AiConversationProblemMapper problemMapper,
            AiMemoryService memoryService,
            AiRetrievalService retrievalService,
            AiConversationContextV2Service contextV2Service,
            AiProblemContextResolver problemContextResolver,
            AiSubmissionContextResolver submissionContextResolver,
            AiContextReportBuilder contextReportBuilder,
            ObjectMapper objectMapper
    ) {
        this(
                conversationMapper,
                messageMapper,
                problemMapper,
                memoryService,
                retrievalService,
                null,
                contextV2Service,
                problemContextResolver,
                submissionContextResolver,
                contextReportBuilder,
                defaultBudgetService(),
                defaultModelConfigResolver(),
                objectMapper,
                null
        );
    }

    public AiContextService(
            AiConversationMapper conversationMapper,
            AiMessageMapper messageMapper,
            AiConversationProblemMapper problemMapper,
            AiMemoryService memoryService,
            AiRetrievalService retrievalService,
            AiLearningProfileService learningProfileService,
            AiConversationContextV2Service contextV2Service,
            AiProblemContextResolver problemContextResolver,
            AiSubmissionContextResolver submissionContextResolver,
            AiContextReportBuilder contextReportBuilder,
            ObjectMapper objectMapper
    ) {
        this(
                conversationMapper,
                messageMapper,
                problemMapper,
                memoryService,
                retrievalService,
                learningProfileService,
                contextV2Service,
                problemContextResolver,
                submissionContextResolver,
                contextReportBuilder,
                defaultBudgetService(),
                defaultModelConfigResolver(),
                objectMapper,
                null
        );
    }

    public AiContextService(
            AiConversationMapper conversationMapper,
            AiMessageMapper messageMapper,
            AiConversationProblemMapper problemMapper,
            AiMemoryService memoryService,
            AiRetrievalService retrievalService,
            AiConversationContextV2Service contextV2Service,
            AiProblemContextResolver problemContextResolver,
            AiSubmissionContextResolver submissionContextResolver,
            AiContextReportBuilder contextReportBuilder,
            AiContextBudgetService contextBudgetService,
            AiModelConfigResolver modelConfigResolver,
            ObjectMapper objectMapper
    ) {
        this(
                conversationMapper,
                messageMapper,
                problemMapper,
                memoryService,
                retrievalService,
                null,
                contextV2Service,
                problemContextResolver,
                submissionContextResolver,
                contextReportBuilder,
                contextBudgetService,
                modelConfigResolver,
                objectMapper,
                null
        );
    }

    public AiChatContext build(Long userId, AiConversationEntity conversation, AiChatRequest request) {
        return build(userId, conversation, request, ContestAiPolicyResponse.inactive());
    }

    public AiChatContext build(Long userId, AiConversationEntity conversation, AiChatRequest request, ContestAiPolicyResponse contestPolicy) {
        String summary = conversation.getSummary();
        ServiceResolvedContext resolvedContext = serviceResolvedContext(userId, request);
        ContestAiPolicyResponse effectiveContestPolicy = effectiveContestPolicy(contestPolicy, resolvedContext.submissionContext());
        String model = textGenerationModel();
        // W1.7: computed once per build and reused across compression/trim re-assemblies so
        // the [Resolved Reference] block next to [Current User Message] stays stable.
        String resolvedReferenceBlock = referenceResolutionService == null
                ? ""
                : referenceResolutionService.injectionBlock(userId, conversation.getId(), request);
        ContextAssembly assembly = assembleContext(
                userId,
                conversation,
                request,
                summary,
                resolvedContext,
                effectiveContestPolicy,
                TrimOptions.none(),
                resolvedReferenceBlock
        );
        AiContextBudgetService.BudgetEvaluation before = contextBudgetService.evaluate(
                model,
                request,
                assembly.report(),
                assembly.finalContextPack()
        );
        AiContextBudgetService.BudgetEvaluation after = before;
        boolean compressionApplied = false;
        List<String> trimmedSections = new ArrayList<>();
        List<String> droppedSections = new ArrayList<>();
        List<String> warnings = new ArrayList<>();

        if (before.overBudget()) {
            AiConversationContextV2Service.PreProviderCompressionResult compact =
                    contextV2Service.compactBeforeProvider(userId, conversation.getId());
            if (compact.applied()) {
                compressionApplied = true;
                assembly = assembleContext(
                        userId,
                        conversation,
                        request,
                        summary,
                        resolvedContext,
                        effectiveContestPolicy,
                        TrimOptions.none(),
                        resolvedReferenceBlock
                );
                after = contextBudgetService.evaluate(model, request, assembly.report(), assembly.finalContextPack());
            } else {
                warnings.add("COMPRESSION_SKIPPED:" + nonBlank(compact.warning(), "UNKNOWN"));
            }
        }

        if (after.overBudget()) {
            TrimResult trim = trimOptionalContext(
                    userId,
                    conversation,
                    request,
                    summary,
                    resolvedContext,
                    effectiveContestPolicy,
                    model,
                    after,
                    resolvedReferenceBlock
            );
            assembly = trim.assembly();
            after = trim.budget();
            trimmedSections.addAll(trim.trimmedSections());
            droppedSections.addAll(trim.droppedSections());
            warnings.addAll(trim.warnings());
        }

        AiContextBudgetReport budgetReport = contextBudgetService.report(
                before,
                after,
                compressionApplied,
                trimmedSections,
                droppedSections,
                warnings
        );
        AiContextBuildReport report = assembly.report().withBudget(budgetReport);
        return new AiChatContext(assembly.memories(), summary == null ? "" : summary, assembly.currentProblems(), assembly.history(),
                assembly.finalContextPack(),
                resolvedContext.submissionContextSummary(),
                report,
                effectiveContestPolicy);
    }

    public String snapshot(AiChatContext context) {
        try {
            return objectMapper.writeValueAsString(context);
        } catch (Exception ex) {
            return "{}";
        }
    }

    @Transactional
    public void beforeTurn(Long userId, AiConversationEntity conversation, AiChatRequest request) {
        contextV2Service.beforeTurn(userId, conversation.getId(), request);
        if (referenceResolutionService != null) {
            // W1.7: resolve "第N题/上一批..." references once per turn, persist the manifest
            // on the ai_turns row and issue a clarification when the reference is ambiguous.
            referenceResolutionService.processTurn(userId, conversation.getId(), request);
        }
    }

    public AiCompletion prepareCompletionForTurn(Long userId, AiConversationEntity conversation, AiChatRequest request, AiCompletion completion) {
        AiCompletion prepared = contextV2Service.prepareCompletionForTurn(userId, conversation.getId(), request, completion);
        if (prepared != null && !prepared.hasClarification() && referenceResolutionService != null) {
            // W1.7: an ambiguous reference issued a server-side clarification during context
            // build; attach it so the standard SSE clarification event fires and the answer
            // returns through the existing clarificationAnswer path.
            var attachment = referenceResolutionService.pendingClarificationAttachment(userId, conversation.getId(), request);
            if (attachment.isPresent()) {
                prepared = new AiCompletion(
                        prepared.content(),
                        prepared.provider(),
                        prepared.model(),
                        prepared.promptTokens(),
                        prepared.completionTokens(),
                        prepared.teachingDecision(),
                        prepared.stuckLayer(),
                        prepared.studentLevel(),
                        attachment.get()
                );
            }
        }
        return prepared;
    }

    public AiCompletion prepareCompletionForTurn(Long userId, AiConversationEntity conversation, AiCompletion completion) {
        return contextV2Service.prepareCompletionForTurn(userId, conversation.getId(), completion);
    }

    public AiConversationContextDebugResponse contextDebug(Long userId, String conversationId) {
        String stateContext = contextV2Service.contextPack(userId, conversationId, null, "", "");
        String memories = memoryService.recallForContext(userId, stateContext);
        RetrievedHistory history = retrievedHistory(userId, stateContext, AiRetrievalService.AiRetrievalSearchContext.empty());
        return contextV2Service.contextDebug(userId, conversationId, memories, history.text(), history.hits());
    }

    @Transactional
    public void afterTurn(
            Long userId,
            AiConversationEntity conversation,
            AiChatRequest request,
            AiCompletion completion,
            AiChatContext context,
            Long userMessageId,
            Long assistantMessageId
    ) {
        recordActualPromptUsage(context, completion);
        AiConversationProblemEntity activeProblem = upsertProblemContext(userId, conversation, request, completion, userMessageId);
        updateConversationSummary(conversation, request.message(), completion.content());
        AiRetrievalService.AiRetrievalChunkMetadata messageMetadata = retrievalMetadata(request, context);
        retrievalService.indexChunk(
                userId,
                "message",
                String.valueOf(assistantMessageId),
                "用户：" + request.message() + "\nAI：" + completion.content(),
                messageMetadata
        );
        if (conversation.getSummary() != null && !conversation.getSummary().isBlank()) {
            retrievalService.indexChunk(userId, "conversation_summary", conversation.getId(), conversation.getSummary(), messageMetadata);
        }
        contextV2Service.afterTurn(userId, conversation.getId(), userMessageId, assistantMessageId, request, completion, context);
        if (activeProblem != null && activeProblem.getSetId() != null) {
            // W1.7: focus follows the set of the latest message; legacy rows without ordinals
            // leave the focus untouched. A CAS conflict here must not fail the completed turn.
            try {
                contextV2Service.patchProblemSetFocus(userId, conversation.getId(), activeProblem.getSetId());
            } catch (DomainException ex) {
                if (ex.errorCode() != ErrorCode.CONFLICT) {
                    throw ex;
                }
                log.error("Problem-set focus CAS conflict after completed turn user={} conversation={} setId={}",
                        userId, conversation.getId(), activeProblem.getSetId());
            }
        }
    }

    private String hardTruncate(String value, int charLimit) {
        if (charLimit <= 0 || value == null || value.length() <= charLimit) {
            return value;
        }
        return value.substring(0, charLimit) + " [truncated: prompt over token budget]";
    }

    private String appendBlock(String first, String second) {
        if (second == null || second.isBlank()) {
            return first == null ? "" : first;
        }
        if (first == null || first.isBlank()) {
            return second;
        }
        return first + "\n\n" + second;
    }

    private ContextAssembly assembleContext(
            Long userId,
            AiConversationEntity conversation,
            AiChatRequest request,
            String summary,
            ServiceResolvedContext resolvedContext,
            ContestAiPolicyResponse effectiveContestPolicy,
            TrimOptions trimOptions,
            String resolvedReferenceBlock
    ) {
        String stateContext = hardTruncate(
                contextV2Service.contextPack(userId, conversation.getId(), request, "", ""),
                trimOptions.hardPackCharLimit());
        String conversationProblems = trimOptions.dropCurrentProblems()
                ? ""
                : currentProblems(conversation.getId(), userId);
        String currentProblems = appendBlock(appendBlock(conversationProblems, resolvedContext.promptBlock()), stateContext);
        String query = memoryQuery(request, summary, currentProblems);
        String memories = trimOptions.dropMemories() ? "" : memoryService.recallForContext(userId, query);
        RetrievedHistory history = trimOptions.dropRetrievedHistory()
                ? RetrievedHistory.empty()
                : retrievedHistory(userId, query, retrievalSearchContext(request, resolvedContext));
        String relatedSubmissionHistory = trimOptions.dropRetrievedHistory()
                ? ""
                : relatedSubmissionHistory(userId, conversation, request, resolvedContext);
        String combinedHistory = appendBlock(history.text(), relatedSubmissionHistory);
        String contextPack = resolvedReferenceBlock == null || resolvedReferenceBlock.isBlank()
                ? contextV2Service.contextPack(userId, conversation.getId(), request, memories, combinedHistory)
                : contextV2Service.contextPack(userId, conversation.getId(), request, memories, combinedHistory, resolvedReferenceBlock);
        String finalContextPack = hardTruncate(
                appendBlock(resolvedContext.promptBlock(), contextPack),
                trimOptions.hardPackCharLimit());
        AiContextBuildReport report = buildContextReport(
                request,
                resolvedContext,
                effectiveContestPolicy,
                stateContext,
                currentProblems,
                memories,
                combinedHistory,
                history.hits(),
                finalContextPack
        );
        return new ContextAssembly(memories, currentProblems, combinedHistory, finalContextPack, report);
    }

    private TrimResult trimOptionalContext(
            Long userId,
            AiConversationEntity conversation,
            AiChatRequest request,
            String summary,
            ServiceResolvedContext resolvedContext,
            ContestAiPolicyResponse effectiveContestPolicy,
            String model,
            AiContextBudgetService.BudgetEvaluation currentBudget,
            String resolvedReferenceBlock
    ) {
        TrimOptions options = TrimOptions.none();
        ContextAssembly assembly = null;
        AiContextBudgetService.BudgetEvaluation budget = currentBudget;
        List<String> trimmed = new ArrayList<>();
        List<String> dropped = new ArrayList<>();
        List<String> warnings = new ArrayList<>();

        if (budget.overBudget()) {
            options = options.withDroppedRetrievedHistory();
            assembly = assembleContext(userId, conversation, request, summary, resolvedContext, effectiveContestPolicy, options, resolvedReferenceBlock);
            budget = contextBudgetService.evaluate(model, request, assembly.report(), assembly.finalContextPack());
            trimmed.add("retrieval.history");
        }
        if (budget.overBudget()) {
            options = options.withDroppedMemories();
            assembly = assembleContext(userId, conversation, request, summary, resolvedContext, effectiveContestPolicy, options, resolvedReferenceBlock);
            budget = contextBudgetService.evaluate(model, request, assembly.report(), assembly.finalContextPack());
            trimmed.add("memory.long_term");
        }
        if (budget.overBudget()) {
            options = options.withDroppedCurrentProblems();
            assembly = assembleContext(userId, conversation, request, summary, resolvedContext, effectiveContestPolicy, options, resolvedReferenceBlock);
            budget = contextBudgetService.evaluate(model, request, assembly.report(), assembly.finalContextPack());
            trimmed.add("conversation.current_problems");
        }
        if (assembly == null) {
            assembly = assembleContext(userId, conversation, request, summary, resolvedContext, effectiveContestPolicy, options, resolvedReferenceBlock);
            budget = contextBudgetService.evaluate(model, request, assembly.report(), assembly.finalContextPack());
        }
        if (budget.overBudget()) {
            // W1.8 hard degradation: after the drop order (history -> memory ->
            // current_problems) still leaves the prompt over budget, truncate
            // the rendered context pack text itself until it fits.
            boolean hardTruncated = false;
            int charLimit = assembly.finalContextPack() == null ? 0 : assembly.finalContextPack().length();
            while (budget.overBudget() && charLimit > HARD_TRUNCATE_MIN_PACK_CHARS) {
                charLimit = Math.max(HARD_TRUNCATE_MIN_PACK_CHARS, charLimit / 2);
                options = options.withHardPackCharLimit(charLimit);
                assembly = assembleContext(userId, conversation, request, summary, resolvedContext, effectiveContestPolicy, options, resolvedReferenceBlock);
                budget = contextBudgetService.evaluate(model, request, assembly.report(), assembly.finalContextPack());
                hardTruncated = true;
            }
            if (hardTruncated) {
                trimmed.add("context_pack.hard_truncated");
            }
        }
        if (budget.overBudget()) {
            warnings.add("BUDGET_STILL_OVER_LIMIT");
            log.warn("context still over token budget after hard truncation: estimated={} budget={}",
                    budget.estimatedPromptTokens(), budget.maxPromptBudgetTokens());
        }
        return new TrimResult(assembly, budget, trimmed, dropped, warnings);
    }

    /**
     * W1.8: feeds the budget estimator EWMA with the actual prompt_tokens the
     * provider reported for this turn, so future estimates self-correct.
     */
    private void recordActualPromptUsage(AiChatContext context, AiCompletion completion) {
        if (context == null || completion == null || completion.promptTokens() <= 0) {
            return;
        }
        AiContextBuildReport report = context.contextBuildReport();
        AiContextBudgetReport budget = report == null ? null : report.budget();
        if (budget != null && budget.estimatedPromptTokensAfter() > 0) {
            contextBudgetService.recordActualUsage(budget.estimatedPromptTokensAfter(), completion.promptTokens());
        }
    }

    private String textGenerationModel() {
        AiModelEffectiveConfig config = modelConfigResolver.effectiveConfig(AiModelScope.TEXT_GENERATION);
        return config == null ? "" : config.model();
    }

    private ServiceResolvedContext serviceResolvedContext(Long userId, AiChatRequest request) {
        AiSubmissionContextResponse submission = submissionContextResolver.resolve(userId, request);
        if (submission != null) {
            return new ServiceResolvedContext(
                    submissionContextResolver.contextBlock(submission),
                    problemContextResolver.safeSummary(submission.problemContext()),
                    submissionContextResolver.safeSummary(submission),
                    submission
            );
        }
        AiProblemContextResponse problem = problemContextResolver.resolve(userId, request);
        return new ServiceResolvedContext(problemContextResolver.contextBlock(problem), problemContextResolver.safeSummary(problem), Map.of(), null);
    }

    private AiContextBuildReport buildContextReport(
            AiChatRequest request,
            ServiceResolvedContext resolvedContext,
            ContestAiPolicyResponse contestPolicy,
            String stateContext,
            String currentProblems,
            String memories,
            String history,
            List<Map<String, Object>> retrievalHits,
            String finalContextPack
    ) {
        List<AiContextSection> sections = contextReportBuilder.mutableSections();
        sections.add(contextReportBuilder.section(
                "request.message",
                "user_message",
                "Current user message",
                100,
                "request.message",
                "user_input",
                true,
                request == null ? "" : request.message(),
                metadata("mode", request == null ? null : request.mode())
        ));
        if (!resolvedContext.problemContextSummary().isEmpty()) {
            sections.add(contextReportBuilder.section(
                    "resolved.problem",
                    "problem_context",
                    "Server resolved problem context",
                    95,
                    "problem-service.internal",
                    "problem_safe",
                    true,
                    toJson(resolvedContext.problemContextSummary()),
                    resolvedContext.problemContextSummary()
            ));
        }
        if (!resolvedContext.submissionContextSummary().isEmpty()) {
            sections.add(contextReportBuilder.section(
                    "resolved.submission",
                    "submission_context",
                    "Server resolved submission context",
                    94,
                    "problem-service.internal",
                    "submission_safe",
                    true,
                    toJson(resolvedContext.submissionContextSummary()),
                    resolvedContext.submissionContextSummary()
            ));
        }
        if (shouldReportContestPolicy(contestPolicy, request)) {
            Map<String, Object> policy = contestPolicySummary(contestPolicy);
            sections.add(contextReportBuilder.section(
                    "contest.ai_policy",
                    "contest_policy",
                    "Contest AI policy",
                    93,
                    "problem-service.contest-policy",
                    "policy",
                    true,
                    toJson(policy),
                    policy
            ));
        }
        sections.add(contextReportBuilder.section(
                "conversation.state",
                "conversation_state",
                "Current conversation state",
                90,
                "ai-service.state",
                "internal",
                true,
                stateContext,
                Map.of()
        ));
        sections.add(contextReportBuilder.section(
                "conversation.current_problems",
                "current_problems",
                "Current problem and fallback turns",
                70,
                "ai-service.conversation",
                "internal",
                false,
                currentProblems,
                Map.of()
        ));
        sections.add(contextReportBuilder.section(
                "memory.long_term",
                "long_term_memory",
                "Relevant long-term memories",
                55,
                "ai-service.memory",
                "memory",
                false,
                memories,
                Map.of()
        ));
        sections.add(contextReportBuilder.section(
                "retrieval.history",
                "retrieved_history",
                "Retrieved past context",
                50,
                "ai-service.retrieval",
                "memory",
                false,
                history,
                retrievalHits == null || retrievalHits.isEmpty() ? Map.of() : Map.of("hits", retrievalHits)
        ));
        String teachingStrategy = extractBlock(finalContextPack, "[Teaching Strategy]");
        sections.add(contextReportBuilder.section(
                "teaching.strategy",
                "teaching_strategy",
                "Teaching strategy",
                88,
                "ai-service.strategy",
                "policy",
                true,
                teachingStrategy,
                Map.of()
        ));
        return contextReportBuilder.build(sections);
    }

    private boolean shouldReportContestPolicy(ContestAiPolicyResponse policy, AiChatRequest request) {
        if (policy != null && policy.activeContestProblem()) {
            return true;
        }
        return request != null && request.contestContext() != null;
    }

    private ContestAiPolicyResponse effectiveContestPolicy(ContestAiPolicyResponse policy, AiSubmissionContextResponse submission) {
        if (policy != null && policy.activeContestProblem()) {
            return policy;
        }
        if (submission == null || !submission.contestActive()) {
            return policy == null ? ContestAiPolicyResponse.inactive() : policy;
        }
        String message = submission.policyMessage() == null || submission.policyMessage().isBlank()
                ? "比赛进行中只能提供思路、复杂度、边界情况和调试方向，不能提供完整可提交代码。"
                : submission.policyMessage();
        return new ContestAiPolicyResponse(
                true,
                submission.contestId(),
                submission.contestRunId(),
                submission.contestProblemId(),
                submission.problemId(),
                null,
                null,
                null,
                true,
                true,
                true,
                false,
                false,
                true,
                12,
                message
        );
    }

    private Map<String, Object> contestPolicySummary(ContestAiPolicyResponse policy) {
        if (policy == null) {
            return Map.of();
        }
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("activeContestProblem", policy.activeContestProblem());
        putIfPresent(payload, "contestId", policy.contestId());
        putIfPresent(payload, "contestRunId", policy.contestRunId());
        putIfPresent(payload, "contestProblemId", policy.contestProblemId());
        putIfPresent(payload, "problemId", policy.problemId());
        payload.put("allowIdeaGuidance", policy.allowIdeaGuidance());
        payload.put("allowDebugGuidance", policy.allowDebugGuidance());
        payload.put("allowSubmissionMetadataToAi", policy.allowSubmissionMetadataToAi());
        payload.put("allowOwnSubmissionCodeToAi", policy.allowOwnSubmissionCodeToAi());
        payload.put("allowFullCodeInResponse", policy.allowFullCodeInResponse());
        payload.put("allowPseudocode", policy.allowPseudocode());
        putIfPresent(payload, "maxPseudocodeLines", policy.maxPseudocodeLines());
        putIfPresent(payload, "policyMessage", policy.policyMessage());
        return payload;
    }

    private Map<String, Object> metadata(String key, Object value) {
        Map<String, Object> payload = new LinkedHashMap<>();
        putIfPresent(payload, key, value);
        return payload;
    }

    private void putIfPresent(Map<String, Object> payload, String key, Object value) {
        if (value != null && !value.toString().isBlank()) {
            payload.put(key, value);
        }
    }

    private String extractBlock(String value, String heading) {
        if (value == null || value.isBlank() || heading == null || heading.isBlank()) {
            return "";
        }
        int start = value.indexOf(heading);
        if (start < 0) {
            return "";
        }
        int next = value.indexOf("\n[", start + heading.length());
        return next < 0 ? value.substring(start) : value.substring(start, next);
    }

    private String toJson(Object value) {
        try {
            return objectMapper.writeValueAsString(value);
        } catch (Exception ex) {
            return String.valueOf(value);
        }
    }

    private record ServiceResolvedContext(
            String promptBlock,
            Map<String, Object> problemContextSummary,
            Map<String, Object> submissionContextSummary,
            AiSubmissionContextResponse submissionContext
    ) {
    }

    private record ContextAssembly(
            String memories,
            String currentProblems,
            String history,
            String finalContextPack,
            AiContextBuildReport report
    ) {
    }

    private record RetrievedHistory(
            String text,
            List<Map<String, Object>> hits
    ) {
        static RetrievedHistory empty() {
            return new RetrievedHistory("", List.of());
        }
    }

    private record RelatedSubmissionMemory(
            AiConversationEntity conversation,
            String snippet,
            int score
    ) {
    }

    private record TrimOptions(
            boolean dropRetrievedHistory,
            boolean dropMemories,
            boolean dropCurrentProblems,
            int hardPackCharLimit
    ) {
        static TrimOptions none() {
            return new TrimOptions(false, false, false, 0);
        }

        TrimOptions withDroppedRetrievedHistory() {
            return new TrimOptions(true, dropMemories, dropCurrentProblems, hardPackCharLimit);
        }

        TrimOptions withDroppedMemories() {
            return new TrimOptions(dropRetrievedHistory, true, dropCurrentProblems, hardPackCharLimit);
        }

        TrimOptions withDroppedCurrentProblems() {
            return new TrimOptions(dropRetrievedHistory, dropMemories, true, hardPackCharLimit);
        }

        TrimOptions withHardPackCharLimit(int limit) {
            return new TrimOptions(dropRetrievedHistory, dropMemories, dropCurrentProblems, limit);
        }
    }

    private record TrimResult(
            ContextAssembly assembly,
            AiContextBudgetService.BudgetEvaluation budget,
            List<String> trimmedSections,
            List<String> droppedSections,
            List<String> warnings
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

    private String currentProblems(String conversationId, Long userId) {
        List<AiConversationProblemEntity> problems = problemMapper.selectList(new QueryWrapper<AiConversationProblemEntity>()
                .eq("conversation_id", conversationId)
                .eq("user_id", userId)
                .orderByDesc("last_active_at")
                .last("LIMIT 5"));
        if (problems.isEmpty()) {
            List<AiMessageEntity> messages = messageMapper.selectList(new QueryWrapper<AiMessageEntity>()
                    .eq("conversation_id", conversationId)
                    .orderByDesc("created_at")
                    .last("LIMIT 8"));
            StringBuilder fallback = new StringBuilder();
            for (int i = messages.size() - 1; i >= 0; i--) {
                AiMessageEntity message = messages.get(i);
                fallback.append(message.getRole()).append("：")
                        .append(truncate(message.getContent(), 600))
                        .append("\n");
            }
            return fallback.toString().trim();
        }
        StringBuilder block = new StringBuilder();
        for (AiConversationProblemEntity problem : problems) {
            block.append("## ")
                    .append(problem.getTitle() == null ? "未命名题目" : problem.getTitle())
                    .append("\n");
            if (problem.getProblemId() != null) {
                block.append("题目 ID：").append(problem.getProblemId()).append("\n");
            }
            if (problem.getDifficulty() != null) {
                block.append("难度：").append(problem.getDifficulty()).append("\n");
            }
            if (problem.getStatementSnapshot() != null) {
                block.append("题面摘要：").append(truncate(problem.getStatementSnapshot(), 1000)).append("\n");
            }
            if (problem.getLatestCode() != null) {
                block.append("最近代码语言：").append(problem.getLatestLanguage()).append("\n")
                        .append("最近代码（本会话已提供，除非缺少失败样例或具体报错，不要再次要求用户提供完整代码）：\n")
                        .append(truncate(problem.getLatestCode(), 1600)).append("\n");
            }
            if (problem.getUserFollowupsSummary() != null) {
                block.append("追问摘要：").append(problem.getUserFollowupsSummary()).append("\n");
            }
            block.append("\n");
        }
        return block.toString().trim();
    }

    private String memoryQuery(AiChatRequest request, String summary, String currentProblems) {
        StringBuilder query = new StringBuilder();
        appendQuery(query, request.mode());
        appendQuery(query, request.message());
        AiChatRequest.ProblemContext problem = request.problemContext();
        if (problem != null) {
            appendQuery(query, problem.title());
            appendQuery(query, problem.difficulty());
            appendQuery(query, problem.statement());
            appendQuery(query, problem.notes());
            if (problem.tags() != null) {
                for (String tag : problem.tags()) {
                    appendQuery(query, tag);
                }
            }
        }
        AiChatRequest.CodeContext code = request.codeContext();
        if (code != null) {
            appendQuery(query, code.language());
            appendQuery(query, truncate(code.code(), 500));
        }
        appendQuery(query, truncate(summary, 800));
        appendQuery(query, truncate(currentProblems, 1200));
        return query.toString().trim();
    }

    private void appendQuery(StringBuilder query, String value) {
        if (value == null || value.isBlank()) {
            return;
        }
        query.append(value.trim()).append('\n');
    }

    private RetrievedHistory retrievedHistory(Long userId, String query, AiRetrievalService.AiRetrievalSearchContext context) {
        List<AiRetrievalService.AiRetrievalHit> hits = retrievalService.searchDetailed(
                userId,
                query,
                List.of("conversation_summary", "message", "memory", "learning_profile", "profile_evidence", "submission_analysis", "code_snapshot"),
                5,
                context
        );
        if (hits == null) {
            List<String> legacyHits = retrievalService.search(
                    userId,
                    query,
                    List.of("conversation_summary", "message", "memory"),
                    5
            );
            hits = (legacyHits == null ? List.<String>of() : legacyHits).stream()
                    .map(AiRetrievalService.AiRetrievalHit::legacy)
                    .toList();
        } else {
            List<AiRetrievalService.AiRetrievalHit> memoryFiltered = memoryService.filterRecallableRetrievalHits(userId, hits);
            if (memoryFiltered != null) {
                hits = memoryFiltered;
            }
            if (learningProfileService != null) {
                List<AiRetrievalService.AiRetrievalHit> profileFiltered = learningProfileService.filterRecallableRetrievalHits(userId, hits);
                if (profileFiltered != null) {
                    hits = profileFiltered;
                }
            }
        }
        if (hits.isEmpty()) {
            return RetrievedHistory.empty();
        }
        List<String> lines = new ArrayList<>();
        List<Map<String, Object>> metadata = new ArrayList<>();
        for (int i = 0; i < hits.size(); i++) {
            AiRetrievalService.AiRetrievalHit hit = hits.get(i);
            lines.add((i + 1) + ". " + truncate(hit.content(), 900));
            metadata.add(hit.debugMetadata());
        }
        return new RetrievedHistory(String.join("\n\n", lines), metadata);
    }

    private String relatedSubmissionHistory(
            Long userId,
            AiConversationEntity conversation,
            AiChatRequest request,
            ServiceResolvedContext resolvedContext
    ) {
        if (userId == null || conversation == null || !isSubmissionAnalysisConversation(conversation)) {
            return "";
        }
        Long problemId = firstLong(
                request == null ? null : request.problemId(),
                conversation.getProblemId(),
                longFromMap(resolvedContext.problemContextSummary(), "problemId"),
                longFromMap(resolvedContext.submissionContextSummary(), "problemId")
        );
        if (problemId == null) {
            return "";
        }
        List<AiConversationEntity> candidates = conversationMapper.selectList(new QueryWrapper<AiConversationEntity>()
                .eq("user_id", userId)
                .eq("problem_id", problemId)
                .isNull("deleted_at")
                .ne("id", conversation.getId())
                .in("source", List.of("submission_analysis", "ai_tutor"))
                .orderByDesc("updated_at")
                .last("LIMIT " + RELATED_SUBMISSION_CANDIDATE_LIMIT));
        if (candidates == null || candidates.isEmpty()) {
            return "";
        }
        String currentSourceRefId = normalizeBlank(conversation.getSourceRefId());
        List<RelatedSubmissionMemory> memories = new ArrayList<>();
        for (AiConversationEntity candidate : candidates) {
            if (candidate == null) {
                continue;
            }
            String candidateSourceRefId = normalizeBlank(candidate.getSourceRefId());
            if (currentSourceRefId != null
                    && currentSourceRefId.equals(candidateSourceRefId)
                    && isSubmissionAnalysisConversation(candidate)) {
                continue;
            }
            String snippet = relatedConversationSnippet(userId, candidate);
            if (snippet.isBlank()) {
                continue;
            }
            memories.add(new RelatedSubmissionMemory(candidate, snippet, relatedSubmissionScore(candidate, snippet, resolvedContext)));
        }
        if (memories.isEmpty()) {
            return "";
        }
        memories.sort(Comparator
                .comparingInt(RelatedSubmissionMemory::score)
                .reversed()
                .thenComparing(memory -> memory.conversation().getUpdatedAt(), Comparator.nullsLast(Comparator.reverseOrder())));
        List<Map<String, Object>> entries = new ArrayList<>();
        int count = Math.min(RELATED_SUBMISSION_MEMORY_LIMIT, memories.size());
        for (int i = 0; i < count; i++) {
            RelatedSubmissionMemory memory = memories.get(i);
            AiConversationEntity candidate = memory.conversation();
            Map<String, Object> entry = new LinkedHashMap<>();
            entry.put("sourceConversationId", candidate.getId());
            entry.put("source", nonBlank(candidate.getSource(), "unknown"));
            entry.put("relation", isSubmissionAnalysisConversation(candidate)
                    ? "different_submission_same_problem"
                    : "same_problem_tutor");
            entry.put("confidence", relatedMemoryConfidence(memory.score()));
            entry.put("strength", relatedMemoryStrength(memory));
            entry.put("updatedAt", candidate.getUpdatedAt() == null ? null : candidate.getUpdatedAt().toString());
            entry.put("summary", memory.snippet());
            entries.add(entry);
        }
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("instruction", "These are historical hints from this user's same problem. Current submission facts override them; do not copy old code blindly.");
        payload.put("relatedSubmissionMemories", entries);
        return "[Related Same-Problem Submission Memories]\n" + toJson(payload);
    }

    private int relatedSubmissionScore(
            AiConversationEntity candidate,
            String snippet,
            ServiceResolvedContext resolvedContext
    ) {
        int score = 0;
        if (isSubmissionAnalysisConversation(candidate)) {
            score += 6;
        } else if ("ai_tutor".equalsIgnoreCase(candidate.getSource())) {
            score += 2;
        }
        score += containsSummarySignal(snippet, resolvedContext.submissionContextSummary().get("language")) ? 2 : 0;
        score += containsSummarySignal(snippet, resolvedContext.submissionContextSummary().get("status")) ? 2 : 0;
        score += containsSummarySignal(snippet, resolvedContext.submissionContextSummary().get("rootCauseTags")) ? 2 : 0;
        score += containsSummarySignal(snippet, resolvedContext.problemContextSummary().get("tags")) ? 1 : 0;
        return score;
    }

    private double relatedMemoryConfidence(int score) {
        double confidence = 0.5 + Math.min(score, 9) * 0.05;
        return Math.round(confidence * 100.0) / 100.0;
    }

    private String relatedMemoryStrength(RelatedSubmissionMemory memory) {
        if (memory.score() <= 3) {
            return "stale";
        }
        return "suggested";
    }

    private boolean containsSummarySignal(String haystack, Object signal) {
        if (haystack == null || haystack.isBlank() || signal == null) {
            return false;
        }
        String normalized = haystack.toLowerCase();
        if (signal instanceof Iterable<?> values) {
            for (Object value : values) {
                if (containsSummarySignal(normalized, value)) {
                    return true;
                }
            }
            return false;
        }
        String text = signal.toString().trim().toLowerCase();
        return !text.isBlank() && normalized.contains(text);
    }

    private String relatedConversationSnippet(Long userId, AiConversationEntity conversation) {
        String summary = sanitizeRelatedHistory(conversation.getSummary());
        if (!summary.isBlank()) {
            return summary;
        }
        List<AiMessageEntity> messages = messageMapper.selectList(new QueryWrapper<AiMessageEntity>()
                .eq("user_id", userId)
                .eq("conversation_id", conversation.getId())
                .eq("role", "assistant")
                .eq("status", AiConversationService.MESSAGE_STATUS_COMPLETED)
                .orderByDesc("created_at")
                .last("LIMIT 1"));
        if (messages == null || messages.isEmpty()) {
            return "";
        }
        return sanitizeRelatedHistory(messages.get(0).getContent());
    }

    private String sanitizeRelatedHistory(String value) {
        if (value == null || value.isBlank()) {
            return "";
        }
        String sanitized = value
                .replaceAll("(?s)```.*?```", "[code omitted]")
                .replaceAll("(?m)^\\s*#include\\b.*$", "[code line omitted]")
                .replaceAll("(?m)^\\s*(using namespace|int main\\s*\\(|def\\s+|class\\s+).*$", "[code line omitted]");
        return truncate(sanitized, RELATED_SUBMISSION_SNIPPET_LIMIT);
    }

    private boolean isSubmissionAnalysisConversation(AiConversationEntity conversation) {
        if (conversation == null) {
            return false;
        }
        return "submission_analysis".equalsIgnoreCase(conversation.getSource())
                && "SUBMISSION".equalsIgnoreCase(conversation.getSourceRefType())
                && normalizeBlank(conversation.getSourceRefId()) != null;
    }

    private String normalizeBlank(String value) {
        return value == null || value.isBlank() ? null : value.trim();
    }

    private AiRetrievalService.AiRetrievalSearchContext retrievalSearchContext(
            AiChatRequest request,
            ServiceResolvedContext resolvedContext
    ) {
        Long problemId = firstLong(
                request == null ? null : request.problemId(),
                longFromMap(resolvedContext.problemContextSummary(), "problemId"),
                longFromMap(resolvedContext.submissionContextSummary(), "problemId")
        );
        Long submissionId = firstLong(
                request == null || request.submissionContext() == null ? null : request.submissionContext().submissionId(),
                longFromMap(resolvedContext.submissionContextSummary(), "submissionId")
        );
        Set<String> algorithmKeys = new HashSet<>();
        if (request != null && request.problemContext() != null && request.problemContext().tags() != null) {
            algorithmKeys.addAll(request.problemContext().tags());
        }
        addStrings(algorithmKeys, resolvedContext.problemContextSummary().get("tags"));
        addStrings(algorithmKeys, resolvedContext.submissionContextSummary().get("rootCauseTags"));
        Set<String> profileKeys = new HashSet<>(algorithmKeys);
        Object status = resolvedContext.submissionContextSummary().get("status");
        if (status != null) {
            profileKeys.add("status_" + status);
        }
        return new AiRetrievalService.AiRetrievalSearchContext(problemId, submissionId, algorithmKeys, profileKeys);
    }

    private AiRetrievalService.AiRetrievalChunkMetadata retrievalMetadata(AiChatRequest request, AiChatContext context) {
        Map<String, Object> submissionSummary = context == null || context.submissionContextSummary() == null
                ? Map.of()
                : context.submissionContextSummary();
        Long problemId = firstLong(
                request == null ? null : request.problemId(),
                longFromMap(submissionSummary, "problemId")
        );
        Long submissionId = firstLong(
                request == null || request.submissionContext() == null ? null : request.submissionContext().submissionId(),
                longFromMap(submissionSummary, "submissionId")
        );
        String algorithmKey = null;
        if (request != null && request.problemContext() != null && request.problemContext().tags() != null && !request.problemContext().tags().isEmpty()) {
            algorithmKey = request.problemContext().tags().get(0);
        }
        String profileKey = null;
        Object status = submissionSummary.get("status");
        if (status != null) {
            profileKey = "status_" + status;
        }
        return AiRetrievalService.AiRetrievalChunkMetadata.of(
                problemId,
                submissionId,
                longFromMap(submissionSummary, "contestId"),
                longFromMap(submissionSummary, "contestRunId"),
                longFromMap(submissionSummary, "contestProblemId"),
                algorithmKey,
                profileKey,
                metadata("source", "ai-chat-turn")
        );
    }

    private void addStrings(Set<String> target, Object value) {
        if (target == null || value == null) {
            return;
        }
        if (value instanceof Iterable<?> values) {
            for (Object item : values) {
                if (item != null && !item.toString().isBlank()) {
                    target.add(item.toString());
                }
            }
            return;
        }
        if (!value.toString().isBlank()) {
            target.add(value.toString());
        }
    }

    private Long firstLong(Long... values) {
        if (values == null) {
            return null;
        }
        for (Long value : values) {
            if (value != null) {
                return value;
            }
        }
        return null;
    }

    private Long longFromMap(Map<String, Object> source, String key) {
        if (source == null || key == null || !source.containsKey(key)) {
            return null;
        }
        Object value = source.get(key);
        if (value instanceof Number number) {
            return number.longValue();
        }
        try {
            return value == null || value.toString().isBlank() ? null : Long.parseLong(value.toString());
        } catch (NumberFormatException ex) {
            return null;
        }
    }

    /**
     * Registers/updates the conversation problem row for this turn. New rows get immutable
     * W1.7 ordinals (set_id = source user message id, set_ordinal per set, conversation_ordinal
     * per conversation); updates never overwrite previously assigned ordinals. Returns the
     * upserted row so the caller can move the problem-set focus, or null when the message
     * carries no problem signal.
     */
    private AiConversationProblemEntity upsertProblemContext(Long userId, AiConversationEntity conversation, AiChatRequest request, AiCompletion completion, Long userMessageId) {
        Long problemId = request.problemId();
        AiChatRequest.ProblemContext problemContext = request.problemContext();
        if (problemId == null && problemContext != null && problemContext.id() != null) {
            try {
                problemId = Long.parseLong(problemContext.id());
            } catch (NumberFormatException ignored) {
                problemId = null;
            }
        }
        boolean hasCode = request.codeContext() != null && request.codeContext().code() != null && !request.codeContext().code().isBlank();
        boolean messageLooksLikeCode = looksLikeCode(request.message());
        if (problemId == null && problemContext == null && !hasCode && !messageLooksLikeCode) {
            return null;
        }

        QueryWrapper<AiConversationProblemEntity> query = new QueryWrapper<AiConversationProblemEntity>()
                .eq("conversation_id", conversation.getId())
                .eq("user_id", userId);
        if (problemId == null) {
            query.isNull("problem_id");
        } else {
            query.eq("problem_id", problemId);
        }
        AiConversationProblemEntity entity = problemMapper.selectOne(query.last("LIMIT 1"));
        if (entity == null) {
            entity = new AiConversationProblemEntity();
            entity.setConversationId(conversation.getId());
            entity.setUserId(userId);
            entity.setProblemId(problemId);
        }
        if (problemContext != null) {
            entity.setTitle(nonBlank(problemContext.title(), entity.getTitle()));
            entity.setDifficulty(nonBlank(problemContext.difficulty(), entity.getDifficulty()));
            entity.setStatementSnapshot(nonBlank(problemContext.statement(), entity.getStatementSnapshot()));
            try {
                entity.setTags(problemContext.tags() == null ? entity.getTags() : objectMapper.writeValueAsString(problemContext.tags()));
            } catch (Exception ignored) {
                entity.setTags(entity.getTags());
            }
        }
        if (hasCode) {
            entity.setLatestLanguage(request.codeContext().language());
            entity.setLatestCode(request.codeContext().code());
        } else if (messageLooksLikeCode) {
            entity.setLatestLanguage(inferLanguage(request.message()));
            entity.setLatestCode(request.message());
        }
        entity.setAiSolutionSummary(truncate(completion.content(), 1200));
        entity.setUserFollowupsSummary(truncate(request.message(), 600));
        entity.setLastActiveAt(LocalDateTime.now());
        if (entity.getId() == null) {
            assignProblemOrdinals(conversation.getId(), userMessageId, entity);
            problemMapper.insert(entity);
        } else {
            problemMapper.updateById(entity);
        }
        if (problemId != null) {
            conversation.setRecentProblemId(problemId);
        }
        return entity;
    }

    /**
     * W1.7 ordinal assignment for brand-new problem rows only:
     * conversation_ordinal = current max + 1; all problems carried by one user message share
     * set_id = that message's id and are numbered by set_ordinal in appearance order (single
     * problem messages still create a set with set_ordinal = 1). Legacy rows keep their NULL
     * ordinals (no historical backfill) and updates never touch assigned ordinals.
     */
    private void assignProblemOrdinals(String conversationId, Long userMessageId, AiConversationProblemEntity entity) {
        if (entity.getId() != null || entity.getConversationOrdinal() != null) {
            return;
        }
        List<Object> rows = problemMapper.selectObjs(new QueryWrapper<AiConversationProblemEntity>()
                .select("MAX(conversation_ordinal)")
                .eq("conversation_id", conversationId));
        Object max = rows == null || rows.isEmpty() ? null : rows.get(0);
        int nextOrdinal = max instanceof Number number ? number.intValue() + 1 : 1;
        entity.setConversationOrdinal(nextOrdinal);
        String setId = userMessageId == null ? null : String.valueOf(userMessageId);
        entity.setSetId(setId);
        if (setId != null) {
            Long inSet = problemMapper.selectCount(new QueryWrapper<AiConversationProblemEntity>()
                    .eq("conversation_id", conversationId)
                    .eq("set_id", setId));
            entity.setSetOrdinal((inSet == null ? 0 : inSet.intValue()) + 1);
        }
    }

    private void updateConversationSummary(AiConversationEntity conversation, String userMessage, String assistantMessage) {
        String previous = conversation.getSummary();
        String next = (previous == null ? "" : previous + "\n")
                + "用户：" + truncate(userMessage, 280) + "\n"
                + "AI：" + truncate(assistantMessage, 420);
        if (next.length() > 2500) {
            next = next.substring(next.length() - 2500);
        }
        conversation.setSummary(next.trim());
        conversation.setSummaryUpdatedAt(LocalDateTime.now());
        conversation.setUpdatedAt(LocalDateTime.now());
        conversationMapper.updateById(conversation);
    }

    private String nonBlank(String value, String fallback) {
        return value == null || value.isBlank() ? fallback : value;
    }

    private String truncate(String value, int maxLength) {
        if (value == null) {
            return "";
        }
        String normalized = value.trim();
        return normalized.length() <= maxLength ? normalized : normalized.substring(0, maxLength) + "…";
    }

    private boolean looksLikeCode(String value) {
        if (value == null || value.isBlank()) {
            return false;
        }
        String text = value.trim();
        int score = 0;
        if (text.contains("{") && text.contains("}")) score++;
        if (text.contains(";")) score++;
        if (text.contains("def ") || text.contains("class ") || text.contains("#include")) score++;
        if (text.contains("for (") || text.contains("while (") || text.contains("if (") || text.contains("return ")) score++;
        if (text.lines().count() >= 4) score++;
        return score >= 2;
    }

    private String inferLanguage(String value) {
        if (value == null) {
            return "unknown";
        }
        String text = value.toLowerCase();
        if (text.contains("#include") || text.contains("using namespace std")) {
            return "cpp";
        }
        if (text.contains("public class") || text.contains("system.out")) {
            return "java";
        }
        if (text.contains("def ") || text.contains("import sys") || text.contains("print(")) {
            return "python";
        }
        return "unknown";
    }
}
