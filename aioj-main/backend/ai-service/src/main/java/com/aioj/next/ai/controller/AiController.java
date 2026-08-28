package com.aioj.next.ai.controller;

import com.aioj.next.ai.agent.AgentChatFacade;
import com.aioj.next.ai.domain.AiCompletion;
import com.aioj.next.ai.domain.AiChatContext;
import com.aioj.next.ai.domain.AiConversationService;
import com.aioj.next.ai.domain.AiContextService;
import com.aioj.next.ai.domain.AiMemoryService;
import com.aioj.next.ai.domain.AiLearningProfileService;
import com.aioj.next.ai.domain.AiProvider;
import com.aioj.next.ai.domain.AiQuotaService;
import com.aioj.next.ai.domain.AccountImportParseService;
import com.aioj.next.ai.domain.ProblemDraftGenerationJobService;
import com.aioj.next.ai.domain.ProblemDraftStore;
import com.aioj.next.ai.domain.memory.AiMemoryCandidateService;
import com.aioj.next.ai.domain.memory.AiMemoryDebugService;
import com.aioj.next.ai.domain.memory.AiMemoryReviewService;
import com.aioj.next.ai.domain.response.AiAssistantResponseNormalizer;
import com.aioj.next.ai.persistence.entity.AiConversationEntity;
import com.aioj.next.common.api.ApiResponse;
import com.aioj.next.common.api.PageResponse;
import com.aioj.next.common.error.DomainException;
import com.aioj.next.common.error.ErrorCode;
import com.aioj.next.common.security.SecuritySupport;
import com.aioj.next.contract.ai.AiChatMessageResponse;
import com.aioj.next.contract.ai.AiChatRequest;
import com.aioj.next.contract.ai.AiConversationBatchDeleteRequest;
import com.aioj.next.contract.ai.AiConversationCreateRequest;
import com.aioj.next.contract.ai.AiConversationContextDebugResponse;
import com.aioj.next.contract.ai.AiConversationResponse;
import com.aioj.next.contract.ai.AiConversationUpdateRequest;
import com.aioj.next.contract.ai.AiMemoryExportResponse;
import com.aioj.next.contract.ai.AiMemoryImportRequest;
import com.aioj.next.contract.ai.AiMemoryImportResponse;
import com.aioj.next.contract.ai.AiMemoryCandidateActionRequest;
import com.aioj.next.contract.ai.AiMemoryCandidateResponse;
import com.aioj.next.contract.ai.AiMemoryDebugResponse;
import com.aioj.next.contract.ai.AiMemoryResponse;
import com.aioj.next.contract.ai.AiMemoryReviewDetailResponse;
import com.aioj.next.contract.ai.AiMemoryUpsertRequest;
import com.aioj.next.contract.ai.AiLearningProfileEvidenceResponse;
import com.aioj.next.contract.ai.AiLearningProfileResponse;
import com.aioj.next.contract.ai.AiLearningProfileUpdateRequest;
import com.aioj.next.contract.ai.AiUsageResponse;
import com.aioj.next.contract.ai.AccountImportParseRequest;
import com.aioj.next.contract.ai.AccountImportParseResponse;
import com.aioj.next.contract.ai.DailyAiUsageStatsResponse;
import com.aioj.next.contract.ai.ProblemDraftApprovalRequest;
import com.aioj.next.contract.ai.ProblemDraftGenerationJobResponse;
import com.aioj.next.contract.ai.ProblemDraftRefineRequest;
import com.aioj.next.ai.domain.ContestAiUsageService;
import com.aioj.next.ai.domain.ContestAiAssistanceStatisticsService;
import com.aioj.next.contract.ai.ProblemDraftRegenerateRequest;
import com.aioj.next.contract.ai.ProblemDraftRequest;
import com.aioj.next.contract.ai.ProblemDraftResponse;
import com.aioj.next.contract.ai.AdminContestAiConversationSummary;
import com.aioj.next.contract.ai.AdminContestAiMessageResponse;
import com.aioj.next.contract.ai.AdminContestAiUsageSummary;
import com.aioj.next.contract.ai.AdminContestAiAssistanceSummary;
import com.aioj.next.contract.ai.ProblemDraftRejectRequest;
import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.validation.Valid;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.servlet.mvc.method.annotation.StreamingResponseBody;

import java.io.IOException;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.Executor;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

@RestController
public class AiController {
    private static final Logger log = LoggerFactory.getLogger(AiController.class);
    private static final long DRAFT_STREAM_HEARTBEAT_INTERVAL_MILLIS = 1_000L;

    private final AiProvider aiProvider;
    private final AgentChatFacade agentChatFacade;
    private final AiQuotaService aiQuotaService;
    private final AccountImportParseService accountImportParseService;
    private final AiConversationService aiConversationService;
    private final AiContextService aiContextService;
    private final AiMemoryService aiMemoryService;
    private final AiLearningProfileService aiLearningProfileService;
    private final AiMemoryCandidateService aiMemoryCandidateService;
    private final AiMemoryDebugService aiMemoryDebugService;
    private final AiMemoryReviewService aiMemoryReviewService;
    private final ProblemDraftStore problemDraftStore;
    private final AiAssistantResponseNormalizer responseNormalizer;
    private final ObjectMapper objectMapper;
    private final Executor problemDraftExecutor;
    private ProblemDraftGenerationJobService problemDraftGenerationJobService;
    private ContestAiUsageService contestAiUsageService;
    private ContestAiAssistanceStatisticsService contestAiAssistanceStatisticsService;
    private com.aioj.next.ai.agent.runtime.PseudoStreamReplayer pseudoStreamReplayer;

    AiController(
            AiProvider aiProvider,
            AgentChatFacade agentChatFacade,
            AiQuotaService aiQuotaService,
            AccountImportParseService accountImportParseService,
            AiConversationService aiConversationService,
            AiContextService aiContextService,
            AiMemoryService aiMemoryService,
            AiLearningProfileService aiLearningProfileService,
            AiMemoryCandidateService aiMemoryCandidateService,
            AiMemoryDebugService aiMemoryDebugService,
            AiMemoryReviewService aiMemoryReviewService,
            ProblemDraftStore problemDraftStore,
            AiAssistantResponseNormalizer responseNormalizer,
            ObjectMapper objectMapper
    ) {
        this(aiProvider, agentChatFacade, aiQuotaService, accountImportParseService, aiConversationService,
                aiContextService, aiMemoryService, aiLearningProfileService, aiMemoryCandidateService,
                aiMemoryDebugService, aiMemoryReviewService, problemDraftStore, responseNormalizer, objectMapper,
                Runnable::run);
    }

    @Autowired
    public AiController(
            AiProvider aiProvider,
            AgentChatFacade agentChatFacade,
            AiQuotaService aiQuotaService,
            AccountImportParseService accountImportParseService,
            AiConversationService aiConversationService,
            AiContextService aiContextService,
            AiMemoryService aiMemoryService,
            AiLearningProfileService aiLearningProfileService,
            AiMemoryCandidateService aiMemoryCandidateService,
            AiMemoryDebugService aiMemoryDebugService,
            AiMemoryReviewService aiMemoryReviewService,
            ProblemDraftStore problemDraftStore,
            AiAssistantResponseNormalizer responseNormalizer,
            ObjectMapper objectMapper,
            @Qualifier("aiProblemDraftExecutor") Executor problemDraftExecutor
    ) {
        this.aiProvider = aiProvider;
        this.agentChatFacade = agentChatFacade;
        this.aiQuotaService = aiQuotaService;
        this.accountImportParseService = accountImportParseService;
        this.aiConversationService = aiConversationService;
        this.aiContextService = aiContextService;
        this.aiMemoryService = aiMemoryService;
        this.aiLearningProfileService = aiLearningProfileService;
        this.aiMemoryCandidateService = aiMemoryCandidateService;
        this.aiMemoryDebugService = aiMemoryDebugService;
        this.aiMemoryReviewService = aiMemoryReviewService;
        this.problemDraftStore = problemDraftStore;
        this.responseNormalizer = responseNormalizer;
        this.objectMapper = objectMapper;
        this.problemDraftExecutor = problemDraftExecutor;
    }

    @Autowired(required = false)
    public void setProblemDraftGenerationJobService(ProblemDraftGenerationJobService problemDraftGenerationJobService) {
        this.problemDraftGenerationJobService = problemDraftGenerationJobService;
    }

    @Autowired(required = false)
    public void setContestAiUsageService(ContestAiUsageService contestAiUsageService) {
        this.contestAiUsageService = contestAiUsageService;
    }

    @Autowired(required = false)
    public void setContestAiAssistanceStatisticsService(
            ContestAiAssistanceStatisticsService contestAiAssistanceStatisticsService
    ) {
        this.contestAiAssistanceStatisticsService = contestAiAssistanceStatisticsService;
    }

    /**
     * P3-5: optional pseudo-stream replay for L4-verified restricted turns.
     * Setter-injected so the controller constructor surface stays unchanged.
     */
    @Autowired(required = false)
    public void setPseudoStreamReplayer(com.aioj.next.ai.agent.runtime.PseudoStreamReplayer pseudoStreamReplayer) {
        this.pseudoStreamReplayer = pseudoStreamReplayer;
    }

    private ContestAiUsageService requireContestAiUsageService() {
        if (contestAiUsageService == null) {
            throw new DomainException(ErrorCode.INTERNAL_ERROR, "Contest AI usage records are unavailable");
        }
        return contestAiUsageService;
    }

    private ContestAiAssistanceStatisticsService requireContestAiAssistanceStatisticsService() {
        if (contestAiAssistanceStatisticsService == null) {
            throw new DomainException(ErrorCode.INTERNAL_ERROR, "Contest AI assistance statistics are unavailable");
        }
        return contestAiAssistanceStatisticsService;
    }

    @PostMapping("/ai/chat/send")
    public ApiResponse<AiChatMessageResponse> send(@RequestBody @Valid AiChatRequest request) {
        Long userId = SecuritySupport.currentUserId();
        AgentChatFacade.TurnHandle turn = agentChatFacade.start(userId, request);
        AgentChatFacade.TurnResult result = awaitTurn(turn);
        return ApiResponse.ok(result.assistant());
    }

    @PostMapping(value = "/ai/chat/stream", produces = MediaType.TEXT_EVENT_STREAM_VALUE)
    public ResponseEntity<StreamingResponseBody> stream(@RequestBody @Valid AiChatRequest request,
                                                        @RequestParam(required = false) String resumeTurnId) {
        Long userId = SecuritySupport.currentUserId();
        // A resume request reattaches to the existing turn by id and never regenerates;
        // without it, start() still deduplicates by clientMessageId via ai_turns.
        AgentChatFacade.TurnHandle turn = resumeTurnId == null || resumeTurnId.isBlank()
                ? agentChatFacade.start(userId, request)
                : agentChatFacade.resume(userId, resumeTurnId.trim(), request);
        AiConversationEntity conversation = turn.conversation();
        AiChatContext context = turn.context();
        AiChatMessageResponse user = turn.user();

        StreamingResponseBody body = output -> {
            try {
                writeSseEvent(output, "meta", objectMapper.writeValueAsString(metaPayload(conversation.getId(), request, user, turn.turnId())));
                Map<String, Object> problemContext = problemContextPayload(request);
                Map<String, Object> submissionContext = submissionContextPayload(context, request);
                Map<String, Object> renderHints = defaultRenderHints(problemContext);
                if (context.hasContent() || !problemContext.isEmpty() || !submissionContext.isEmpty()) {
                    writeSseEvent(output, "context", objectMapper.writeValueAsString(contextPayload(context, problemContext, submissionContext, renderHints)));
                }
                AgentChatFacade.TurnResult result = awaitTurn(turn);
                AiCompletion completion = result.completion();
                // P3-5 (§5.4, Q2): restricted turns were buffered for the L4 output
                // guard; replay the verified content as delta slices before the full
                // message event. Non-restricted turns are byte-for-byte unchanged.
                if (result.pseudoStream() && pseudoStreamReplayer != null) {
                    for (String deltaPayload : pseudoStreamReplayer.deltaPayloads(completion.content())) {
                        writeSseEvent(output, "delta", deltaPayload);
                    }
                }
                Map<String, Object> messagePayload = assistantMessagePayload(result.normalized(), problemContext, submissionContext, renderHints, conversation.getId(), request, user, result.assistant());
                writeSseEvent(output, "message", objectMapper.writeValueAsString(messagePayload));
                if (completion.hasClarification()) {
                    writeSseEvent(output, "clarification", objectMapper.writeValueAsString(completion.clarification()));
                }
                if (result.memoryCount() > 0) {
                    writeSseEvent(output, "memory", "{\"saved\":" + result.memoryCount() + "}");
                }
                writeSseEvent(output, "done", objectMapper.writeValueAsString(donePayload(conversation.getId(), result.conversationMode(), request, user, result.assistant(), turn.turnId())));
            } catch (IOException ex) {
                // The browser left the page. The background turn keeps running and will be visible in history.
            } catch (RuntimeException ex) {
                sendStreamError(output, ex);
            }
        };
        return ResponseEntity.ok()
                .contentType(MediaType.TEXT_EVENT_STREAM)
                .header(HttpHeaders.CACHE_CONTROL, "no-cache")
                .header("X-Accel-Buffering", "no")
                .body(body);
    }

    @GetMapping("/ai/conversations")
    public ApiResponse<PageResponse<AiConversationResponse>> conversations(
            @RequestParam(defaultValue = "1") long page,
            @RequestParam(defaultValue = "20") long pageSize,
            @RequestParam(required = false) Long problemId,
            @RequestParam(required = false) String source,
            @RequestParam(required = false) String sourceRefType,
            @RequestParam(required = false) String sourceRefId,
            @RequestParam(required = false) String keyword,
            @RequestParam(defaultValue = "false") boolean includeDeleted
    ) {
        return ApiResponse.ok(aiConversationService.list(SecuritySupport.currentUserId(), page, pageSize, problemId,
                source, sourceRefType, sourceRefId, keyword, includeDeleted));
    }

    @PostMapping("/ai/conversations")
    public ApiResponse<AiConversationResponse> createConversation(
            @RequestBody(required = false) @Valid AiConversationCreateRequest request
    ) {
        return ApiResponse.ok(aiConversationService.create(SecuritySupport.currentUserId(), request));
    }

    @PatchMapping("/ai/conversations/{id}")
    public ApiResponse<AiConversationResponse> updateConversation(
            @PathVariable String id,
            @RequestBody @Valid AiConversationUpdateRequest request
    ) {
        return ApiResponse.ok(aiConversationService.update(SecuritySupport.currentUserId(), id, request));
    }

    @GetMapping("/ai/conversations/{id}/messages")
    public ApiResponse<List<AiChatMessageResponse>> messages(@PathVariable String id) {
        return ApiResponse.ok(aiConversationService.messages(SecuritySupport.currentUserId(), id));
    }

    @GetMapping("/ai/conversations/{id}/context-debug")
    public ApiResponse<AiConversationContextDebugResponse> contextDebug(@PathVariable String id) {
        Long userId = SecuritySupport.currentUserId();
        aiConversationService.ensureOwner(id, userId);
        return ApiResponse.ok(aiContextService.contextDebug(userId, id));
    }

    @DeleteMapping("/ai/conversations/{id}")
    public ApiResponse<Void> deleteConversation(@PathVariable String id) {
        aiConversationService.delete(SecuritySupport.currentUserId(), id);
        return ApiResponse.ok(null);
    }

    @PostMapping("/ai/conversations/batch-delete")
    public ApiResponse<Void> batchDeleteConversations(@RequestBody @Valid AiConversationBatchDeleteRequest request) {
        aiConversationService.batchDelete(SecuritySupport.currentUserId(), request.conversationIds());
        return ApiResponse.ok(null);
    }

    @GetMapping("/ai/memories")
    public ApiResponse<List<AiMemoryResponse>> memories(
            @RequestParam(required = false) String category,
            @RequestParam(required = false) String status,
            @RequestParam(required = false) String keyword
    ) {
        return ApiResponse.ok(aiMemoryService.list(SecuritySupport.currentUserId(), category, status, keyword));
    }

    @GetMapping("/ai/memories/export")
    public ApiResponse<AiMemoryExportResponse> exportMemories() {
        return ApiResponse.ok(aiMemoryService.exportMarkdown(SecuritySupport.currentUserId()));
    }

    @PostMapping("/ai/memories/import")
    public ApiResponse<AiMemoryImportResponse> importMemories(@RequestBody @Valid AiMemoryImportRequest request) {
        return ApiResponse.ok(aiMemoryService.importMarkdown(SecuritySupport.currentUserId(), request));
    }

    @GetMapping("/ai/memory-candidates")
    public ApiResponse<List<AiMemoryCandidateResponse>> memoryCandidates(
            @RequestParam(required = false) String status
    ) {
        return ApiResponse.ok(aiMemoryCandidateService.list(SecuritySupport.currentUserId(), status));
    }

    @GetMapping("/ai/memory-candidates/{id}")
    public ApiResponse<AiMemoryReviewDetailResponse> memoryCandidateDetail(@PathVariable Long id) {
        return ApiResponse.ok(aiMemoryReviewService.detailForUser(SecuritySupport.currentUserId(), id));
    }

    @PostMapping("/ai/memory-candidates/{id}/accept")
    public ApiResponse<AiMemoryResponse> acceptMemoryCandidate(
            @PathVariable Long id,
            @RequestBody(required = false) @Valid AiMemoryCandidateActionRequest request
    ) {
        return ApiResponse.ok(aiMemoryCandidateService.accept(SecuritySupport.currentUserId(), id, request));
    }

    @PostMapping("/ai/memory-candidates/{id}/accept-with-edit")
    public ApiResponse<AiMemoryResponse> acceptMemoryCandidateWithEdit(
            @PathVariable Long id,
            @RequestBody @Valid AiMemoryCandidateActionRequest request
    ) {
        return ApiResponse.ok(aiMemoryCandidateService.accept(SecuritySupport.currentUserId(), id, request));
    }

    @PostMapping("/ai/memory-candidates/{id}/reject")
    public ApiResponse<AiMemoryCandidateResponse> rejectMemoryCandidate(
            @PathVariable Long id,
            @RequestBody(required = false) AiMemoryCandidateActionRequest request
    ) {
        return ApiResponse.ok(aiMemoryCandidateService.reject(SecuritySupport.currentUserId(), id, request == null ? null : request.reason()));
    }

    @GetMapping("/ai/memory-debug")
    public ApiResponse<AiMemoryDebugResponse> memoryDebug(
            @RequestParam String query,
            @RequestParam(required = false) Long problemId,
            @RequestParam(required = false) List<String> problemTags,
            @RequestParam(required = false) String mode
    ) {
        return ApiResponse.ok(aiMemoryDebugService.debug(SecuritySupport.currentUserId(), query, problemId, problemTags, mode));
    }

    @PostMapping("/ai/memories")
    public ApiResponse<AiMemoryResponse> createMemory(@RequestBody @Valid AiMemoryUpsertRequest request) {
        return ApiResponse.ok(aiMemoryService.create(SecuritySupport.currentUserId(), request));
    }

    @PatchMapping("/ai/memories/{id}")
    public ApiResponse<AiMemoryResponse> updateMemory(
            @PathVariable Long id,
            @RequestBody @Valid AiMemoryUpsertRequest request
    ) {
        return ApiResponse.ok(aiMemoryService.update(SecuritySupport.currentUserId(), id, request));
    }

    @DeleteMapping("/ai/memories/{id}")
    public ApiResponse<Void> deleteMemory(@PathVariable Long id) {
        aiMemoryService.delete(SecuritySupport.currentUserId(), id);
        return ApiResponse.ok(null);
    }

    @PostMapping("/ai/memories/{id}/disable")
    public ApiResponse<AiMemoryResponse> disableMemory(@PathVariable Long id) {
        return ApiResponse.ok(aiMemoryService.disable(SecuritySupport.currentUserId(), id));
    }

    @PostMapping("/ai/memories/{id}/enable")
    public ApiResponse<AiMemoryResponse> enableMemory(@PathVariable Long id) {
        return ApiResponse.ok(aiMemoryService.enable(SecuritySupport.currentUserId(), id));
    }

    @GetMapping("/ai/learning-profile")
    public ApiResponse<List<AiLearningProfileResponse>> learningProfiles(
            @RequestParam(required = false) String category,
            @RequestParam(required = false) String state
    ) {
        return ApiResponse.ok(aiLearningProfileService.list(SecuritySupport.currentUserId(), category, state));
    }

    @GetMapping("/ai/learning-profile/{id}/evidence")
    public ApiResponse<List<AiLearningProfileEvidenceResponse>> learningProfileEvidence(@PathVariable Long id) {
        return ApiResponse.ok(aiLearningProfileService.evidence(SecuritySupport.currentUserId(), id));
    }

    @PatchMapping("/ai/learning-profile/{id}")
    public ApiResponse<AiLearningProfileResponse> updateLearningProfile(
            @PathVariable Long id,
            @RequestBody @Valid AiLearningProfileUpdateRequest request
    ) {
        return ApiResponse.ok(aiLearningProfileService.update(SecuritySupport.currentUserId(), id, request));
    }

    @PostMapping("/ai/learning-profile/{id}/mark-mastered")
    public ApiResponse<AiLearningProfileResponse> markLearningProfileMastered(@PathVariable Long id) {
        return ApiResponse.ok(aiLearningProfileService.markMastered(SecuritySupport.currentUserId(), id));
    }

    @PostMapping("/ai/learning-profile/{id}/disable")
    public ApiResponse<AiLearningProfileResponse> disableLearningProfile(@PathVariable Long id) {
        return ApiResponse.ok(aiLearningProfileService.disable(SecuritySupport.currentUserId(), id));
    }

    @DeleteMapping("/ai/learning-profile/{id}")
    public ApiResponse<Void> deleteLearningProfile(@PathVariable Long id) {
        aiLearningProfileService.delete(SecuritySupport.currentUserId(), id);
        return ApiResponse.ok(null);
    }

    @PostMapping("/ai/problem-drafts/generate")
    @PreAuthorize("hasAnyRole('TEACHER','ADMIN')")
    public ApiResponse<ProblemDraftResponse> generateDraft(@RequestBody @Valid ProblemDraftRequest request) {
        return ApiResponse.ok(problemDraftStore.generate(SecuritySupport.currentUserId(), request));
    }

    @PostMapping(value = "/ai/problem-drafts/generate/stream", produces = MediaType.TEXT_EVENT_STREAM_VALUE)
    @PreAuthorize("hasAnyRole('TEACHER','ADMIN')")
    public ResponseEntity<StreamingResponseBody> generateDraftStream(@RequestBody @Valid ProblemDraftRequest request) {
        Long userId = SecuritySupport.currentUserId();
        StreamingResponseBody body = output -> {
            long startedAt = System.currentTimeMillis();
            CompletableFuture<ProblemDraftResponse> draftFuture = null;
            try {
                writeSseEvent(output, "meta", objectMapper.writeValueAsString(Map.of(
                        "heartbeatIntervalMillis", DRAFT_STREAM_HEARTBEAT_INTERVAL_MILLIS
                )));
                try {
                    draftFuture = CompletableFuture.supplyAsync(
                            () -> problemDraftStore.generate(userId, request),
                            problemDraftExecutor
                    );
                } catch (RejectedExecutionException ex) {
                    sendDraftStreamError(output, new DomainException(ErrorCode.TOO_MANY_REQUESTS, "AI service is busy; please try again later"), userId, startedAt);
                    return;
                }
                while (true) {
                    try {
                        ProblemDraftResponse draft = draftFuture.get(DRAFT_STREAM_HEARTBEAT_INTERVAL_MILLIS, TimeUnit.MILLISECONDS);
                        writeSseEvent(output, "draft", objectMapper.writeValueAsString(draft));
                        writeSseEvent(output, "done", "[DONE]");
                        return;
                    } catch (TimeoutException ignored) {
                        writeSseEvent(output, "heartbeat", objectMapper.writeValueAsString(Map.of(
                                "running", true,
                                "elapsedMillis", Math.max(0L, System.currentTimeMillis() - startedAt)
                        )));
                    } catch (InterruptedException ex) {
                        Thread.currentThread().interrupt();
                        draftFuture.cancel(true);
                        sendDraftStreamError(output, new DomainException(ErrorCode.INTERNAL_ERROR, "Problem draft generation was interrupted"), userId, startedAt);
                        return;
                    } catch (ExecutionException ex) {
                        Throwable cause = ex.getCause();
                        if (cause instanceof RuntimeException runtimeException) {
                            sendDraftStreamError(output, providerFailure(runtimeException), userId, startedAt);
                        } else {
                            sendDraftStreamError(output, new DomainException(ErrorCode.INTERNAL_ERROR, "Problem draft generation failed"), userId, startedAt);
                        }
                        return;
                    }
                }
            } catch (IOException ex) {
                if (draftFuture != null) {
                    draftFuture.cancel(true);
                }
            } catch (RuntimeException ex) {
                sendDraftStreamError(output, providerFailure(ex), userId, startedAt);
            }
        };
        return ResponseEntity.ok()
                .contentType(MediaType.TEXT_EVENT_STREAM)
                .header(HttpHeaders.CACHE_CONTROL, "no-cache")
                .header("X-Accel-Buffering", "no")
                .body(body);
    }

    @PostMapping("/ai/problem-drafts/generation-jobs")
    @PreAuthorize("hasAnyRole('TEACHER','ADMIN')")
    public ApiResponse<ProblemDraftGenerationJobResponse> createProblemDraftGenerationJob(
            @RequestBody @Valid ProblemDraftRequest request
    ) {
        return ApiResponse.ok(requireProblemDraftGenerationJobService().create(SecuritySupport.currentUserId(), request));
    }

    @GetMapping("/admin/problem-draft-generation-jobs")
    @PreAuthorize("hasAnyRole('TEACHER','ADMIN')")
    public ApiResponse<PageResponse<ProblemDraftGenerationJobResponse>> listProblemDraftGenerationJobs(
            @RequestParam(defaultValue = "1") long page,
            @RequestParam(defaultValue = "20") long pageSize,
            @RequestParam(required = false) String status,
            @RequestParam(required = false) Long creatorUserId
    ) {
        return ApiResponse.ok(requireProblemDraftGenerationJobService().list(page, pageSize, status, creatorUserId));
    }

    @GetMapping("/admin/problem-draft-generation-jobs/{id}")
    @PreAuthorize("hasAnyRole('TEACHER','ADMIN')")
    public ApiResponse<ProblemDraftGenerationJobResponse> problemDraftGenerationJob(@PathVariable Long id) {
        return ApiResponse.ok(requireProblemDraftGenerationJobService().get(id));
    }

    @PostMapping("/admin/problem-drafts/{id}/regeneration-job")
    @PreAuthorize("hasAnyRole('TEACHER','ADMIN')")
    public ApiResponse<ProblemDraftGenerationJobResponse> createProblemDraftRegenerationJob(
            @PathVariable Long id,
            @RequestBody @Valid ProblemDraftRegenerateRequest request
    ) {
        return ApiResponse.ok(requireProblemDraftGenerationJobService()
                .createRegeneration(SecuritySupport.currentUserId(), id, request));
    }

    @GetMapping("/admin/problem-drafts")
    @PreAuthorize("hasAnyRole('TEACHER','ADMIN')")
    public ApiResponse<PageResponse<ProblemDraftResponse>> listDrafts(
            @RequestParam(defaultValue = "1") long page,
            @RequestParam(defaultValue = "20") long pageSize,
            @RequestParam(required = false) String status,
            @RequestParam(required = false) String validationStatus,
            @RequestParam(required = false) Long creatorUserId,
            @RequestParam(required = false, defaultValue = "newest") String sort,
            @RequestParam(required = false) String lifecycleStatus
    ) {
        String direction = "oldest".equalsIgnoreCase(sort) ? "ASC" : "DESC";
        return ApiResponse.ok(problemDraftStore.list(page, pageSize, status, validationStatus, creatorUserId, direction, lifecycleStatus));
    }

    @GetMapping("/admin/problem-drafts/{id}")
    @PreAuthorize("hasAnyRole('TEACHER','ADMIN')")
    public ApiResponse<ProblemDraftResponse> draft(@PathVariable Long id) {
        return ApiResponse.ok(problemDraftStore.get(id));
    }

    @PostMapping("/admin/problem-drafts/{id}/refine")
    @PreAuthorize("hasAnyRole('TEACHER','ADMIN')")
    public ApiResponse<ProblemDraftResponse> refineDraft(
            @PathVariable Long id,
            @RequestBody @Valid ProblemDraftRefineRequest request,
            @RequestHeader(value = HttpHeaders.AUTHORIZATION, required = false) String authorization
    ) {
        return ApiResponse.ok(problemDraftStore.refine(id, SecuritySupport.currentUserId(), request, authorization));
    }

    @PostMapping("/admin/problem-drafts/{id}/regenerate")
    @PreAuthorize("hasAnyRole('TEACHER','ADMIN')")
    public ApiResponse<ProblemDraftResponse> regenerateDraft(
            @PathVariable Long id,
            @RequestBody @Valid ProblemDraftRegenerateRequest request
    ) {
        return ApiResponse.ok(problemDraftStore.regenerate(id, SecuritySupport.currentUserId(), request));
    }

    @PostMapping("/admin/problem-drafts/{id}/approve")
    @PreAuthorize("hasAnyRole('TEACHER','ADMIN')")
    public ApiResponse<ProblemDraftResponse> approveDraft(
            @PathVariable Long id,
            @RequestBody(required = false) ProblemDraftApprovalRequest request,
            @RequestHeader(value = HttpHeaders.AUTHORIZATION, required = false) String authorization
    ) {
        return ApiResponse.ok(problemDraftStore.approve(id, SecuritySupport.currentUserId(), request, authorization));
    }

    @PostMapping("/admin/problem-drafts/{id}/manual-review")
    @PreAuthorize("hasAnyRole('TEACHER','ADMIN')")
    public ApiResponse<ProblemDraftResponse> manualReviewDraft(@PathVariable Long id) {
        return ApiResponse.ok(problemDraftStore.manualReview(id, SecuritySupport.currentUserId()));
    }

    @PostMapping("/admin/problem-drafts/{id}/reject")
    @PreAuthorize("hasAnyRole('TEACHER','ADMIN')")
    public ApiResponse<ProblemDraftResponse> rejectDraft(
            @PathVariable Long id,
            @RequestBody(required = false) @Valid ProblemDraftRejectRequest request
    ) {
        String reason = request == null ? null : request.reasonNote();
        return ApiResponse.ok(problemDraftStore.reject(id, SecuritySupport.currentUserId(), reason));
    }

    @PostMapping("/admin/problem-drafts/{id}/archive")
    @PreAuthorize("hasAnyRole('TEACHER','ADMIN')")
    public ApiResponse<ProblemDraftResponse> archiveDraft(@PathVariable Long id) {
        return ApiResponse.ok(problemDraftStore.archive(id));
    }

    @PostMapping("/admin/problem-drafts/{id}/restore")
    @PreAuthorize("hasAnyRole('TEACHER','ADMIN')")
    public ApiResponse<ProblemDraftResponse> restoreDraft(@PathVariable Long id) {
        return ApiResponse.ok(problemDraftStore.restore(id));
    }

    @DeleteMapping("/admin/problem-drafts/{id}")
    @PreAuthorize("hasAnyRole('TEACHER','ADMIN')")
    public ApiResponse<Void> deleteDraft(@PathVariable Long id) {
        problemDraftStore.delete(id);
        return ApiResponse.ok(null);
    }

    @GetMapping("/ai/usage/me")
    public ApiResponse<AiUsageResponse> usage() {
        return ApiResponse.ok(aiQuotaService.usage(SecuritySupport.currentUserId()));
    }

    @PostMapping("/admin/ai/account-import/parse")
    @PreAuthorize("hasRole('ADMIN')")
    public ApiResponse<AccountImportParseResponse> parseAccountImport(
            @RequestBody @Valid AccountImportParseRequest request
    ) {
        return ApiResponse.ok(accountImportParseService.parse(SecuritySupport.currentUserId(), request));
    }

    @GetMapping("/admin/ai/analytics/usage")
    @PreAuthorize("hasAnyRole('TEACHER','ADMIN')")
    public ApiResponse<List<DailyAiUsageStatsResponse>> dailyAiUsage(@RequestParam(defaultValue = "14") int days) {
        return ApiResponse.ok(aiQuotaService.dailyUsage(days));
    }

    @GetMapping("/admin/ai/contests/{contestId}/usage")
    @PreAuthorize("hasAnyRole('TEACHER','ADMIN')")
    public ApiResponse<List<AdminContestAiUsageSummary>> contestAiUsage(
            @PathVariable Long contestId,
            @RequestParam(required = false) Long contestRunId
    ) {
        return ApiResponse.ok(requireContestAiUsageService().summaries(contestId, contestRunId));
    }

    @GetMapping("/admin/ai/contests/{contestId}/usage/{userId}/conversations")
    @PreAuthorize("hasAnyRole('TEACHER','ADMIN')")
    public ApiResponse<List<AdminContestAiConversationSummary>> contestAiUsageConversations(
            @PathVariable Long contestId,
            @PathVariable Long userId,
            @RequestParam(required = false) Long contestRunId
    ) {
        return ApiResponse.ok(requireContestAiUsageService().conversations(contestId, contestRunId, userId));
    }

    @GetMapping("/admin/ai/contests/{contestId}/usage/{userId}/conversations/{conversationId}/messages")
    @PreAuthorize("hasAnyRole('TEACHER','ADMIN')")
    public ApiResponse<List<AdminContestAiMessageResponse>> contestAiUsageMessages(
            @PathVariable Long contestId,
            @PathVariable Long userId,
            @PathVariable String conversationId
    ) {
        return ApiResponse.ok(requireContestAiUsageService()
                .messages(contestId, userId, conversationId, SecuritySupport.currentUserId()));
    }

    /** V3-authoritative contest assistance ledger. The legacy usage route remains for compatibility. */
    @GetMapping("/admin/ai/contests/{contestId}/assistance-statistics")
    @PreAuthorize("hasAnyRole('TEACHER','ADMIN')")
    public ApiResponse<List<AdminContestAiAssistanceSummary>> contestAiAssistanceStatistics(
            @PathVariable Long contestId,
            @RequestParam(required = false) Long contestRunId
    ) {
        return ApiResponse.ok(requireContestAiAssistanceStatisticsService().summaries(contestId, contestRunId));
    }

    @GetMapping("/admin/ai/contests/{contestId}/assistance-statistics/{userId}/conversations")
    @PreAuthorize("hasAnyRole('TEACHER','ADMIN')")
    public ApiResponse<List<AdminContestAiConversationSummary>> contestAiAssistanceStatisticsConversations(
            @PathVariable Long contestId,
            @PathVariable Long userId,
            @RequestParam(required = false) Long contestRunId
    ) {
        return ApiResponse.ok(requireContestAiAssistanceStatisticsService()
                .conversations(contestId, contestRunId, userId));
    }

    @GetMapping("/admin/ai/contests/{contestId}/assistance-statistics/{userId}/conversations/{conversationId}/messages")
    @PreAuthorize("hasAnyRole('TEACHER','ADMIN')")
    public ApiResponse<List<AdminContestAiMessageResponse>> contestAiAssistanceStatisticsMessages(
            @PathVariable Long contestId,
            @PathVariable Long userId,
            @PathVariable String conversationId,
            @RequestParam(required = false) Long contestRunId
    ) {
        return ApiResponse.ok(requireContestAiAssistanceStatisticsService()
                .messages(contestId, contestRunId, userId, conversationId, SecuritySupport.currentUserId()));
    }

    private ProblemDraftGenerationJobService requireProblemDraftGenerationJobService() {
        if (problemDraftGenerationJobService == null) {
            throw new DomainException(ErrorCode.INTERNAL_ERROR, "Problem draft generation jobs are unavailable");
        }
        return problemDraftGenerationJobService;
    }

    private DomainException providerFailure(RuntimeException ex) {
        if (ex instanceof DomainException domainException) {
            return domainException;
        }
        return new DomainException(ErrorCode.INTERNAL_ERROR, "AI provider call failed: " + ex.getMessage());
    }

    private AgentChatFacade.TurnResult awaitTurn(AgentChatFacade.TurnHandle turn) {
        try {
            return turn.result().join();
        } catch (CompletionException ex) {
            Throwable cause = ex.getCause();
            if (cause instanceof RuntimeException runtimeException) {
                throw providerFailure(runtimeException);
            }
            throw new DomainException(ErrorCode.INTERNAL_ERROR, "AI provider call failed");
        }
    }

    private void sendStreamError(OutputStream output, Exception ex) {
        try {
            writeSseEvent(output, "error", ex.getMessage() == null ? "AI provider call failed" : ex.getMessage());
            writeSseEvent(output, "done", "[DONE]");
        } catch (IOException ignored) {
            // Client already left; the response stream can be closed quietly.
        }
    }

    private void sendDraftStreamError(OutputStream output, Exception ex, Long userId, long startedAt) {
        DomainException failure = ex instanceof DomainException domainException
                ? domainException
                : new DomainException(ErrorCode.INTERNAL_ERROR, "Problem draft generation failed");
        long elapsedMillis = Math.max(0L, System.currentTimeMillis() - startedAt);
        String message = safeStreamErrorMessage(failure.getMessage());
        log.warn("Problem draft stream failed userId={} elapsedMillis={} errorType={} message={}",
                userId, elapsedMillis, ex.getClass().getSimpleName(), message);
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("code", failure.errorCode().code());
        payload.put("message", message);
        payload.put("errorKey", streamErrorKey(failure.errorCode()));
        payload.put("elapsedMillis", elapsedMillis);
        try {
            writeSseEvent(output, "error", objectMapper.writeValueAsString(payload));
            writeSseEvent(output, "done", "[DONE]");
        } catch (IOException ignored) {
            // Client already left; the response stream can be closed quietly.
        }
    }

    private String streamErrorKey(ErrorCode errorCode) {
        return switch (errorCode) {
            case TOO_MANY_REQUESTS -> "request.tooMany";
            case SERVICE_UNAVAILABLE -> "service.unavailable";
            default -> "system.internal";
        };
    }

    private String safeStreamErrorMessage(String message) {
        String normalized = message == null || message.isBlank()
                ? "Problem draft generation failed"
                : message.strip();
        normalized = normalized.replaceAll("(?i)(api[-_ ]?key|token|secret|password)\\s*[:=]\\s*\\S+", "$1=***");
        normalized = normalized.replaceAll("sk-[A-Za-z0-9_-]{8,}", "sk-***");
        return normalized.length() <= 800 ? normalized : normalized.substring(0, 800);
    }

    private void writeSseEvent(OutputStream output, String event, String data) throws IOException {
        output.write(("event: " + event + "\n").getBytes(StandardCharsets.UTF_8));
        String payload = data == null ? "" : data;
        for (String line : payload.split("\\R", -1)) {
            output.write(("data: " + line + "\n").getBytes(StandardCharsets.UTF_8));
        }
        output.write('\n');
        output.flush();
    }

    private Map<String, Object> assistantMessagePayload(
            AiAssistantResponseNormalizer.NormalizedResponse normalized,
            Map<String, Object> problemContext,
            Map<String, Object> submissionContext,
            Map<String, Object> defaultRenderHints,
            String conversationId,
            AiChatRequest request,
            AiChatMessageResponse user,
            AiChatMessageResponse assistant
    ) {
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("messageId", String.valueOf(assistant.id()));
        payload.put("assistantMessageId", String.valueOf(assistant.id()));
        payload.put("userMessageId", String.valueOf(user.id()));
        payload.put("conversationId", conversationId);
        putIfPresent(payload, "clientMessageId", assistant.clientMessageId());
        putIfPresent(payload, "requestClientMessageId", clientMessageId(request));
        putIfPresent(payload, "status", assistant.status());
        putIfPresent(payload, "completedAt", assistant.completedAt());
        payload.put("contentMarkdown", normalized.completion().content());
        payload.put("parseWarnings", normalized.parseWarnings());
        Map<String, Object> hints = new LinkedHashMap<>(defaultRenderHints);
        hints.putAll(normalized.renderHints());
        payload.put("renderHints", hints);
        if (!problemContext.isEmpty()) {
            payload.put("problemContext", problemContext);
        }
        if (!submissionContext.isEmpty()) {
            payload.put("submissionContext", submissionContext);
        }
        return payload;
    }

    private Map<String, Object> metaPayload(String conversationId, AiChatRequest request, AiChatMessageResponse user, String turnId) {
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("conversationId", conversationId);
        payload.put("userMessageId", String.valueOf(user.id()));
        putIfPresent(payload, "clientMessageId", clientMessageId(request));
        putIfPresent(payload, "turnId", turnId);
        return payload;
    }

    private Map<String, Object> donePayload(
            String conversationId,
            String conversationMode,
            AiChatRequest request,
            AiChatMessageResponse user,
            AiChatMessageResponse assistant,
            String turnId
    ) {
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("conversationId", conversationId);
        putIfPresent(payload, "conversationMode", conversationMode);
        payload.put("userMessageId", String.valueOf(user.id()));
        payload.put("assistantMessageId", String.valueOf(assistant.id()));
        putIfPresent(payload, "clientMessageId", clientMessageId(request));
        putIfPresent(payload, "assistantClientMessageId", assistant.clientMessageId());
        putIfPresent(payload, "turnId", turnId);
        return payload;
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

    private Map<String, Object> contextPayload(
            AiChatContext context,
            Map<String, Object> problemContext,
            Map<String, Object> submissionContext,
            Map<String, Object> renderHints
    ) {
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("userMemory", safeClientContextText(context.userMemory()));
        payload.put("conversationSummary", safeClientContextText(context.conversationSummary()));
        payload.put("currentProblems", safeClientContextText(context.currentProblems()));
        payload.put("retrievedHistory", safeClientContextText(context.retrievedHistory()));
        payload.put("conversationContextPack", safeClientContextText(context.conversationContextPack()));
        payload.put("renderHints", renderHints);
        if (context.contextBuildReport() != null && context.contextBuildReport().hasSections()) {
            payload.put("contextBuildReport", context.contextBuildReport());
        }
        if (!problemContext.isEmpty()) {
            payload.put("problemContext", problemContext);
        }
        if (!submissionContext.isEmpty()) {
            payload.put("submissionContext", submissionContext);
        }
        return payload;
    }

    private String safeClientContextText(String value) {
        if (value == null || value.isBlank()) {
            return "";
        }
        String normalized = value
                .replace("\r\n", "\n")
                .replace('\r', '\n')
                .replaceAll("(?is)<CURRENT_SUBMISSION_CODE\\b[^>]*>.*?</CURRENT_SUBMISSION_CODE>", "[submission code omitted]")
                .replaceAll("(?is)```.*?```", "[code block omitted]");
        StringBuilder kept = new StringBuilder();
        boolean skippingRawOutput = false;
        for (String rawLine : normalized.split("\n")) {
            String line = rawLine.trim();
            if (isRawOutputLabel(line)) {
                appendSafeClientLine(kept, "[raw output omitted]");
                skippingRawOutput = true;
                continue;
            }
            if (skippingRawOutput) {
                if (isClientContextBoundary(line) || isKnownClientContextKey(line)) {
                    skippingRawOutput = false;
                } else {
                    continue;
                }
            }
            if (looksLikeClientCodeLine(line)) {
                appendSafeClientLine(kept, "[code line omitted]");
                continue;
            }
            appendSafeClientLine(kept, redactClientSecrets(rawLine));
        }
        String preview = kept.toString().replaceAll("\\n{3,}", "\n\n").trim();
        return preview.length() <= 5000 ? preview : preview.substring(0, 5000) + "...";
    }

    private void appendSafeClientLine(StringBuilder kept, String line) {
        if (line == null || line.isBlank()) {
            return;
        }
        if (!kept.isEmpty()) {
            kept.append('\n');
        }
        kept.append(line.trim());
    }

    private String redactClientSecrets(String value) {
        if (value == null || value.isBlank()) {
            return "";
        }
        return value
                .replaceAll("(?i)\"(codeText|stdoutExcerpt|stderrExcerpt)\"\\s*:\\s*\"(?:\\\\.|[^\"\\\\])*\"", "\"$1\":\"[omitted]\"")
                .replaceAll("(?i)(codeText|stdoutExcerpt|stderrExcerpt)\\s*[:=].*", "$1=[omitted]")
                .replaceAll("(?i)(token|secret|password|key)\\s*[:=]\\s*\\S+", "$1=***")
                .replaceAll("sk-[A-Za-z0-9_-]{8,}", "sk-***")
                .replaceAll("sk_live_[A-Za-z0-9_-]{8,}", "sk_live_***");
    }

    private boolean isRawOutputLabel(String line) {
        if (line == null) {
            return false;
        }
        String lower = line.toLowerCase();
        return lower.startsWith("stdoutexcerpt:")
                || lower.startsWith("stderrexcerpt:")
                || lower.startsWith("stdout:")
                || lower.startsWith("stderr:");
    }

    private boolean isClientContextBoundary(String line) {
        return line != null && (line.startsWith("[") || line.startsWith("<"));
    }

    private boolean isKnownClientContextKey(String line) {
        if (line == null || !line.contains(":")) {
            return false;
        }
        String key = line.substring(0, line.indexOf(':')).trim();
        return List.of(
                "submissionId",
                "problemId",
                "scope",
                "contestActive",
                "language",
                "status",
                "judgeMessage",
                "runtimeMs",
                "memoryKb",
                "score",
                "maxScore",
                "codeHash",
                "codeAllowedToModel",
                "policyMessage",
                "caseResults",
                "source"
        ).contains(key);
    }

    private boolean looksLikeClientCodeLine(String line) {
        if (line == null || line.isBlank() || looksLikeStructuredSummaryLine(line)) {
            return false;
        }
        String lower = line.toLowerCase();
        return lower.startsWith("#include")
                || lower.startsWith("using namespace")
                || lower.startsWith("public class")
                || lower.startsWith("public static void main")
                || lower.startsWith("class solution")
                || lower.startsWith("def main")
                || lower.startsWith("import sys")
                || lower.startsWith("from sys")
                || lower.startsWith("if __name__")
                || lower.contains("cin >>")
                || lower.contains("cout <<")
                || lower.contains("sys.stdin")
                || lower.contains("int main(")
                || lower.matches(".*[{};]\\s*$");
    }

    private boolean looksLikeStructuredSummaryLine(String line) {
        if (line == null) {
            return false;
        }
        String trimmed = line.trim();
        return trimmed.startsWith("{")
                && trimmed.endsWith("}")
                && (trimmed.contains("\":") || trimmed.contains("="));
    }

    private Map<String, Object> defaultRenderHints(Map<String, Object> problemContext) {
        if (problemContext.isEmpty()) {
            return Map.of();
        }
        return Map.of(
                "showProblemContext", "compact",
                "problemRefs", List.of("title", "constraints", "tags")
        );
    }

    private Map<String, Object> problemContextPayload(AiChatRequest request) {
        Map<String, Object> payload = new LinkedHashMap<>();
        AiChatRequest.ProblemContext problem = request.problemContext();
        if (problem == null && request.problemId() == null) {
            return payload;
        }
        if (problem == null) {
            payload.put("problemId", String.valueOf(request.problemId()));
            payload.put("source", "problemId");
            return payload;
        }
        putIfPresent(payload, "problemId", problem.id());
        putIfPresent(payload, "title", problem.title());
        putIfPresent(payload, "difficulty", problem.difficulty());
        if (problem.tags() != null && !problem.tags().isEmpty()) {
            payload.put("tags", problem.tags());
        }
        List<String> constraints = constraintsFrom(problem.statement());
        if (!constraints.isEmpty()) {
            payload.put("constraints", constraints);
        }
        putIfPresent(payload, "statementSummary", summarize(problem.statement(), 220));
        putIfPresent(payload, "notesSummary", summarize(problem.notes(), 160));
        if (problem.timeLimitMillis() != null) {
            payload.put("timeLimitMillis", problem.timeLimitMillis());
        }
        if (problem.memoryLimitKb() != null) {
            payload.put("memoryLimitKb", problem.memoryLimitKb());
        }
        payload.put("source", "request.problemContext");
        return payload;
    }

    private Map<String, Object> submissionContextPayload(AiChatRequest request) {
        Map<String, Object> payload = new LinkedHashMap<>();
        AiChatRequest.SubmissionContext submission = request.submissionContext();
        if (submission == null || submission.submissionId() == null) {
            return payload;
        }
        payload.put("submissionId", String.valueOf(submission.submissionId()));
        putIfPresent(payload, "intent", submission.intent());
        payload.put("userSelected", Boolean.TRUE.equals(submission.userSelected()));
        putIfPresent(payload, "note", summarize(submission.note(), 160));
        payload.put("source", "request.submissionContext");
        return payload;
    }

    private Map<String, Object> submissionContextPayload(AiChatContext context, AiChatRequest request) {
        if (context != null && context.submissionContextSummary() != null && !context.submissionContextSummary().isEmpty()) {
            Map<String, Object> payload = new LinkedHashMap<>(context.submissionContextSummary());
            payload.remove("codeText");
            payload.remove("stdoutExcerpt");
            payload.remove("stderrExcerpt");
            payload.remove("problemContext");
            payload.remove("statement");
            payload.remove("problemStatement");
            return payload;
        }
        return submissionContextPayload(request);
    }

    private void putIfPresent(Map<String, Object> payload, String key, Object value) {
        if (value != null && !value.toString().isBlank()) {
            payload.put(key, value);
        }
    }

    private List<String> constraintsFrom(String statement) {
        if (statement == null || statement.isBlank()) {
            return List.of();
        }
        return statement.lines()
                .map(String::trim)
                .filter(line -> !line.isBlank())
                .filter(line -> line.contains("<=") || line.contains("≤")
                        || line.contains("数据范围") || line.toLowerCase().contains("constraint"))
                .limit(5)
                .toList();
    }

    private String summarize(String value, int maxLength) {
        if (value == null || value.isBlank()) {
            return "";
        }
        String normalized = value.replaceAll("\\s+", " ").trim();
        return normalized.length() <= maxLength ? normalized : normalized.substring(0, maxLength);
    }
}
