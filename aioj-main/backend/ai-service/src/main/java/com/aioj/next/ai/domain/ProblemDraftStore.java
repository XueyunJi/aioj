package com.aioj.next.ai.domain;

import com.aioj.next.ai.persistence.entity.ProblemDraftEntity;
import com.aioj.next.ai.persistence.entity.ProblemDraftTestcaseArtifactEntity;
import com.aioj.next.ai.persistence.mapper.ProblemDraftMapper;
import com.aioj.next.ai.domain.problem.ComplexityReport;
import com.aioj.next.ai.domain.problem.DraftSandboxClient;
import com.aioj.next.ai.domain.problem.CrossCheckReport;
import com.aioj.next.ai.domain.problem.DraftExecutionReport;
import com.aioj.next.ai.domain.problem.ProblemDraftAuditContext;
import com.aioj.next.ai.domain.problem.ProblemDraftDifficulty;
import com.aioj.next.ai.domain.problem.ProblemDraftProgressListener;
import com.aioj.next.ai.domain.problem.ProblemDraftRepairer;
import com.aioj.next.ai.domain.problem.ProblemDraftStaticValidator;
import com.aioj.next.ai.domain.problem.ProblemDraftStressGeneratorResult;
import com.aioj.next.ai.domain.problem.ReferenceCheckPolicy;
import com.aioj.next.ai.domain.problem.RepairTask;
import com.aioj.next.ai.domain.problem.VerificationError;
import com.aioj.next.ai.domain.problem.VerificationFailureClassifier;
import com.aioj.next.ai.domain.problem.VerificationOptions;
import com.aioj.next.ai.domain.problem.VerificationReport;
import com.aioj.next.ai.domain.problem.VerificationWarning;
import com.aioj.next.common.api.PageResponse;
import com.aioj.next.common.error.DomainException;
import com.aioj.next.common.error.ErrorCode;
import com.aioj.next.common.security.SecuritySupport;
import com.aioj.next.contract.ai.ProblemDraftApprovalRequest;
import com.aioj.next.contract.ai.ProblemDraftRefineRequest;
import com.aioj.next.contract.ai.ProblemDraftRegenerateRequest;
import com.aioj.next.contract.ai.ProblemDraftRequest;
import com.aioj.next.contract.ai.ProblemDraftResponse;
import com.aioj.next.contract.problem.ProblemVisibility;
import com.aioj.next.contract.problem.TestCaseDto;
import com.baomidou.mybatisplus.core.conditions.query.QueryWrapper;
import com.baomidou.mybatisplus.core.conditions.update.UpdateWrapper;
import com.baomidou.mybatisplus.core.toolkit.IdWorker;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionTemplate;

import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

@Service
public class ProblemDraftStore {
    private static final ZoneId ZONE = ZoneId.systemDefault();
    private static final String STATUS_PENDING_REVIEW = "PENDING_REVIEW";
    private static final String STATUS_APPROVED = "APPROVED";
    private static final String STATUS_REJECTED = "REJECTED";
    private static final String VERIFICATION_NOT_RUN = "NOT_RUN";
    private static final String VERIFICATION_STATIC_FAILED = "STATIC_FAILED";
    private static final String VERIFICATION_FAILED = "FAILED";
    private static final String VERIFICATION_EXECUTION_VERIFIED = "EXECUTION_VERIFIED";
    private static final String VERIFICATION_MANUAL_VERIFIED = "MANUAL_VERIFIED";
    private static final int DEFAULT_TIME_LIMIT_MILLIS = 1000;
    private static final int DEFAULT_MEMORY_LIMIT_KB = 262144;

    private final AiProvider aiProvider;
    private final AiQuotaService aiQuotaService;
    private final AiCapacityService aiCapacityService;
    private final ProblemDraftMapper problemDraftMapper;
    private final ProblemServiceClient problemServiceClient;
    private final ObjectMapper objectMapper;
    private final TransactionTemplate transactionTemplate;
    private final OperationAuditWriter auditWriter;
    private final ProblemDraftStaticValidator problemDraftStaticValidator;
    private final DraftSandboxClient draftSandboxClient;
    private final ProblemDraftRepairer problemDraftRepairer;
    private final VerificationFailureClassifier verificationFailureClassifier;
    private ProblemDraftAuditSnapshotService auditSnapshotService;
    private ProblemDraftTestcaseArtifactService testcaseArtifactService;

    public ProblemDraftStore(
            AiProvider aiProvider,
            AiQuotaService aiQuotaService,
            AiCapacityService aiCapacityService,
            ProblemDraftMapper problemDraftMapper,
            ProblemServiceClient problemServiceClient,
            ObjectMapper objectMapper,
            TransactionTemplate transactionTemplate,
            OperationAuditWriter auditWriter,
            ProblemDraftStaticValidator problemDraftStaticValidator,
            DraftSandboxClient draftSandboxClient,
            ProblemDraftRepairer problemDraftRepairer
    ) {
        this.aiProvider = aiProvider;
        this.aiQuotaService = aiQuotaService;
        this.aiCapacityService = aiCapacityService;
        this.problemDraftMapper = problemDraftMapper;
        this.problemServiceClient = problemServiceClient;
        this.objectMapper = objectMapper;
        this.transactionTemplate = transactionTemplate;
        this.auditWriter = auditWriter;
        this.problemDraftStaticValidator = problemDraftStaticValidator;
        this.draftSandboxClient = draftSandboxClient;
        this.problemDraftRepairer = problemDraftRepairer;
        this.verificationFailureClassifier = new VerificationFailureClassifier(objectMapper);
    }

    @Autowired(required = false)
    public void setProblemDraftAuditSnapshotService(ProblemDraftAuditSnapshotService auditSnapshotService) {
        this.auditSnapshotService = auditSnapshotService;
    }

    @Autowired(required = false)
    public void setProblemDraftTestcaseArtifactService(ProblemDraftTestcaseArtifactService testcaseArtifactService) {
        this.testcaseArtifactService = testcaseArtifactService;
    }

    @Transactional
    public ProblemDraftResponse generate(Long userId, ProblemDraftRequest request) {
        return generate(userId, request, ProblemDraftProgressListener.NOOP);
    }

    @Transactional
    public ProblemDraftResponse generate(Long userId, ProblemDraftRequest request, ProblemDraftProgressListener progressListener) {
        return generate(userId, request, progressListener, ProblemDraftAuditContext.NONE);
    }

    @Transactional
    public ProblemDraftResponse generate(Long userId, ProblemDraftRequest request, ProblemDraftProgressListener progressListener,
                                         ProblemDraftAuditContext auditContext) {
        ProblemDraftProgressListener progress = progressListener == null ? ProblemDraftProgressListener.NOOP : progressListener;
        ProblemDraftAuditContext audit = auditContext == null ? ProblemDraftAuditContext.NONE : auditContext;
        aiQuotaService.assertMonthlyAvailable(userId);
        Long id = IdWorker.getId();
        ProblemDraftResponse generated = null;
        try {
            recordRequirement(audit, userId, id, request);
            progress.onProgress("GENERATING", 1, 7, "Generating problem draft");
            generated = aiCapacityService.call(
                    AiCapacityService.AiWorkload.PROBLEM_DRAFT,
                    () -> aiProvider.generateProblemDraft(id, request)
            );
            ProblemDraftResponse raw = generated == null ? emptyDraft(id, request) : generated;
            Integer rawTime = raw.timeLimitMillis();
            Integer rawMem = raw.memoryLimitKb();
            ProblemDraftResponse response = new ProblemDraftResponse(
                    id,
                    STATUS_PENDING_REVIEW,
                    nonBlank(raw.title(), fallbackTitle(request)),
                    nonBlank(raw.difficulty(), fallbackDifficulty(request)),
                    raw.statement(),
                    nonBlank(raw.notes(), fallbackNotes(nonBlank(raw.title(), fallbackTitle(request)))),
                    nonBlank(raw.standardSolutionLanguage(), defaultSolutionLanguage(request.standardSolutionLanguage())),
                    raw.standardSolutionCode(),
                    defaultReferenceSolutionLanguage(raw.referenceSolutionLanguage(), raw.referenceSolutionCode(), raw.standardSolutionLanguage()),
                    raw.referenceSolutionCode(),
                    raw.testcaseGeneratorPython(),
                    raw.stressTestcaseGeneratorPython(),
                    nonBlank(raw.generationPlan(), fallbackGenerationPlan(request)),
                    raw.tags() == null ? List.of() : raw.tags(),
                    "VALID",
                    List.of(),
                    normalizeTestCases(raw.testCases()),
                    limitOrDefault(rawTime, DEFAULT_TIME_LIMIT_MILLIS),
                    limitOrDefault(rawMem, DEFAULT_MEMORY_LIMIT_KB),
                    raw.importedProblemId(),
                    nonBlank(raw.model(), aiProvider.model()),
                    Math.max(0, raw.promptTokens()),
                    Math.max(0, raw.completionTokens()),
                    raw.createdAt() == null ? Instant.now() : raw.createdAt(),
                    null,
                    null,
                    null,
                    null,
                    null,
                    VERIFICATION_NOT_RUN,
                    null,
                    0,
                    null
            );
            recordPlan(audit, userId, id, request, response);
            recordDraft(audit, userId, id, response);
            StressGeneratorOutcome stressGeneratorOutcome = generateStressGeneratorIfRequested(id, userId, request, response, progress, audit);
            response = stressGeneratorOutcome.response();
            progress.onProgress("STATIC_VERIFYING", 3, 7, "Running static validation");
            VerificationOptions verificationOptions = VerificationOptions.fromRequest(request, List.of());
            ProblemDraftResponse staticValidationTarget = referenceCheckEnabled(request) ? response : raw;
            VerificationReport staticReport = staticReport(staticValidationTarget, verificationOptions, raw.validationErrors(), rawTime, rawMem,
                    stressGeneratorOutcome.errors());
            response = withValidation(response, staticReport.errorMessages().isEmpty() ? "VALID" : "INVALID",
                    staticReport.errorMessages());
            progress.onProgress("SANDBOX_VERIFYING", 4, 7, "Running sandbox verification");
            VerificationOutcome verification = verifyFinalDraft(response, staticReport, verificationOptions);
            recordVerification(audit, userId, id, response, staticReport, verification);
            response = withVerification(response, verification);
            response = repairIfRequested(request, response, verification, verificationOptions, progress, userId, audit);
            progress.onProgress("PERSISTING", 6, 7, "Saving generated draft");
            persist(userId, response);
            aiQuotaService.record(
                    userId,
                    aiProvider.providerName(),
                    response.model(),
                    response.promptTokens(),
                    response.completionTokens(),
                    true
            );
            progress.onProgress("SUCCEEDED", 7, 7, "Problem draft generation completed");
            return response;
        } catch (RuntimeException ex) {
            recordFailure(audit, userId, id, planGateFailure(ex) ? "PLAN" : "DRAFT", request, ex);
            long promptTokens = generated == null ? 0 : generated.promptTokens();
            long completionTokens = generated == null ? 0 : generated.completionTokens();
            aiQuotaService.record(userId, aiProvider.providerName(), aiProvider.model(), promptTokens, completionTokens, false);
            throw ex;
        }
    }

    private StressGeneratorOutcome generateStressGeneratorIfRequested(Long id, Long userId, ProblemDraftRequest request,
                                                                      ProblemDraftResponse response,
                                                                      ProblemDraftProgressListener progress,
                                                                      ProblemDraftAuditContext auditContext) {
        if (!referenceCheckEnabled(request)) {
            return new StressGeneratorOutcome(response, List.of());
        }
        return generateStressGenerator(id, userId, request, response, progress, auditContext);
    }

    private StressGeneratorOutcome generateStressGeneratorIfRequired(Long id, Long userId, ProblemDraftRequest request,
                                                                     ProblemDraftResponse response,
                                                                     ProblemDraftProgressListener progress,
                                                                     ProblemDraftAuditContext auditContext) {
        if (!referenceCheckRequired(response) || hasText(response.stressTestcaseGeneratorPython())) {
            return new StressGeneratorOutcome(response, List.of());
        }
        return generateStressGenerator(id, userId, request, response, progress, auditContext);
    }

    private StressGeneratorOutcome generateStressGenerator(Long id, Long userId, ProblemDraftRequest request,
                                                           ProblemDraftResponse response,
                                                           ProblemDraftProgressListener progress,
                                                           ProblemDraftAuditContext auditContext) {
        progress.onProgress("STRESS_GENERATING", 2, 7, "Generating reference stress tests");
        try {
            ProblemDraftResponse draftForGenerator = response;
            ProblemDraftStressGeneratorResult result = aiCapacityService.call(
                    AiCapacityService.AiWorkload.PROBLEM_DRAFT,
                    () -> aiProvider.generateProblemDraftStressGenerator(id, request, draftForGenerator)
            );
            if (result == null || result.stressTestcaseGeneratorPython() == null
                    || result.stressTestcaseGeneratorPython().isBlank()) {
                List<VerificationError> errors = List.of(new VerificationError(
                        "REFERENCE_GENERATOR_REQUIRED",
                        "stressTestcaseGeneratorPython was not generated for reference check",
                        "stressTestcaseGeneratorPython"
                ));
                recordStressGenerator(auditContext, userId, id, response, errors);
                return new StressGeneratorOutcome(response, errors);
            }
            ProblemDraftResponse withStress = withStressGenerator(response, result);
            recordStressGenerator(auditContext, userId, id, withStress, List.of());
            return new StressGeneratorOutcome(withStress, List.of());
        } catch (RuntimeException ex) {
            List<VerificationError> errors = List.of(new VerificationError(
                    "REFERENCE_GENERATOR_FAILED",
                    "stressTestcaseGeneratorPython generation failed: "
                            + nonBlank(ex.getMessage(), ex.getClass().getSimpleName()),
                    "stressTestcaseGeneratorPython"
            ));
            recordStressGenerator(auditContext, userId, id, response, errors);
            return new StressGeneratorOutcome(response, errors);
        }
    }

    private ProblemDraftResponse withStressGenerator(ProblemDraftResponse source,
                                                     ProblemDraftStressGeneratorResult result) {
        return new ProblemDraftResponse(source.id(), source.status(), source.title(), source.difficulty(), source.statement(),
                source.notes(), source.standardSolutionLanguage(), source.standardSolutionCode(),
                source.referenceSolutionLanguage(), source.referenceSolutionCode(),
                source.testcaseGeneratorPython(), result.stressTestcaseGeneratorPython(), source.generationPlan(),
                source.tags(), source.validationStatus(), source.validationErrors(), source.testCases(),
                source.timeLimitMillis(), source.memoryLimitKb(), source.importedProblemId(),
                nonBlank(result.model(), source.model()), source.promptTokens() + result.promptTokens(),
                source.completionTokens() + result.completionTokens(), source.createdAt(), source.archivedAt(),
                source.deletedAt(), source.deletedBy(), source.refinedFromDraftId(), source.refineNote(),
                source.verificationStatus(), source.verificationReportJson(), source.repairAttemptCount(),
                source.lastRepairReason());
    }

    private boolean referenceCheckEnabled(ProblemDraftRequest request) {
        return ReferenceCheckPolicy.enabled(request);
    }

    private boolean referenceCheckRequired(ProblemDraftResponse draft) {
        return draft != null && (hasText(draft.referenceSolutionCode()) || hasText(draft.stressTestcaseGeneratorPython()));
    }

    public PageResponse<ProblemDraftResponse> list(
            long page,
            long pageSize,
            String status,
            String validationStatus,
            Long creatorUserId,
            String sortDirection,
            String lifecycleStatus
    ) {
        long current = Math.max(1, page);
        long size = Math.min(Math.max(1, pageSize), 100);
        long offset = (current - 1) * size;
        QueryWrapper<ProblemDraftEntity> countQuery = new QueryWrapper<>();
        applyListFilters(countQuery, status, validationStatus, creatorUserId, lifecycleStatus);
        long total = problemDraftMapper.selectCount(countQuery);

        QueryWrapper<ProblemDraftEntity> pageQuery = new QueryWrapper<>();
        applyListFilters(pageQuery, status, validationStatus, creatorUserId, lifecycleStatus);
        if ("ASC".equalsIgnoreCase(sortDirection)) {
            pageQuery.orderByAsc("created_at");
        } else {
            pageQuery.orderByDesc("created_at");
        }
        pageQuery.last("LIMIT " + size + " OFFSET " + offset);
        List<ProblemDraftResponse> records = problemDraftMapper.selectList(pageQuery)
                .stream()
                .map(this::toResponse)
                .toList();
        return new PageResponse<>(records, total, current, size);
    }

    private void applyListFilters(
            QueryWrapper<ProblemDraftEntity> query,
            String status,
            String validationStatus,
            Long creatorUserId,
            String lifecycleStatus
    ) {
        query.isNull("deleted_at");
        if ("ARCHIVED".equalsIgnoreCase(lifecycleStatus)) {
            query.isNotNull("archived_at");
        } else if (!"ALL".equalsIgnoreCase(lifecycleStatus)) {
            query.isNull("archived_at");
        }
        if (status != null && !status.isBlank()) {
            query.eq("status", status);
        }
        if (validationStatus != null && !validationStatus.isBlank()) {
            query.eq("validation_status", validationStatus);
        }
        if (creatorUserId != null) {
            query.eq("creator_user_id", creatorUserId);
        }
    }

    public ProblemDraftResponse get(Long id) {
        return toResponse(requireVisibleDraft(id));
    }

    @Transactional
    public ProblemDraftResponse refine(Long id, Long userId, ProblemDraftRefineRequest request, String authorization) {
        if (request == null) {
            throw new DomainException(ErrorCode.BAD_REQUEST, "Refinement request is required");
        }
        ProblemDraftEntity draft = requireMutableDraft(id);
        DraftPayload currentPayload = readPayload(draft.getDraftJson());
        int timeLimitMillis = limitOrDefault(currentPayload.timeLimitMillis(), DEFAULT_TIME_LIMIT_MILLIS);
        int memoryLimitKb = limitOrDefault(currentPayload.memoryLimitKb(), DEFAULT_MEMORY_LIMIT_KB);
        ProblemDraftResponse updated = new ProblemDraftResponse(
                draft.getId(),
                draft.getStatus(),
                nonBlank(request.title(), draft.getTitle()),
                nonBlank(request.difficulty(), draft.getDifficulty()),
                request.statement() == null ? currentPayload.statement() : request.statement(),
                request.notes() == null ? currentPayload.notes() : request.notes(),
                request.standardSolutionLanguage() == null ? currentPayload.standardSolutionLanguage() : request.standardSolutionLanguage(),
                request.standardSolutionCode() == null ? currentPayload.standardSolutionCode() : request.standardSolutionCode(),
                request.referenceSolutionLanguage() == null ? currentPayload.referenceSolutionLanguage() : request.referenceSolutionLanguage(),
                request.referenceSolutionCode() == null ? currentPayload.referenceSolutionCode() : request.referenceSolutionCode(),
                request.testcaseGeneratorPython() == null ? currentPayload.testcaseGeneratorPython() : request.testcaseGeneratorPython(),
                request.stressTestcaseGeneratorPython() == null ? currentPayload.stressTestcaseGeneratorPython() : request.stressTestcaseGeneratorPython(),
                request.generationPlan() == null ? currentPayload.generationPlan() : request.generationPlan(),
                request.tags() == null ? currentPayload.tags() : request.tags(),
                "VALID",
                List.of(),
                normalizeTestCases(request.testCases() == null ? currentPayload.testCases() : request.testCases()),
                limitOrDefault(request.timeLimitMillis(), timeLimitMillis),
                limitOrDefault(request.memoryLimitKb(), memoryLimitKb),
                draft.getImportedProblemId(),
                nonBlank(draft.getModel(), aiProvider.model()),
                currentPayload.promptTokens(),
                currentPayload.completionTokens(),
                draft.getCreatedAt() == null ? Instant.now() : draft.getCreatedAt().atZone(ZONE).toInstant(),
                toInstant(draft.getArchivedAt()),
                toInstant(draft.getDeletedAt()),
                draft.getDeletedBy(),
                draft.getRefinedFromDraftId(),
                request.refineNote(),
                VERIFICATION_NOT_RUN,
                null,
                repairAttemptCount(draft.getRepairAttemptCount()),
                draft.getLastRepairReason()
        );
        VerificationOptions verificationOptions = verificationOptionsForDraft(updated);
        VerificationReport staticReport = staticReport(updated, verificationOptions, List.of(),
                updated.timeLimitMillis(), updated.memoryLimitKb(), List.of());
        List<String> validationErrors = new ArrayList<>(staticReport.errorMessages());
        ProblemDraftResponse finalDraft = withValidation(updated, validationErrors.isEmpty() ? "VALID" : "INVALID", validationErrors);
        finalDraft = withVerification(finalDraft, verifyFinalDraft(finalDraft, staticReport, verificationOptions));
        if (draft.getImportedProblemId() != null && !"VALID".equals(finalDraft.validationStatus())) {
            throw new DomainException(ErrorCode.VALIDATION_FAILED,
                    "Cannot update an imported draft with invalid content. Please fix the validation errors first.");
        }
        if (draft.getImportedProblemId() != null
                && !VERIFICATION_EXECUTION_VERIFIED.equals(finalDraft.verificationStatus())) {
            throw new DomainException(ErrorCode.VALIDATION_FAILED,
                    "Cannot update an imported draft without successful execution verification. Please fix it first.");
        }
        if (draft.getImportedProblemId() != null) {
            // Regeneration keeps the imported problem's existing visibility.
            problemServiceClient.updateProblem(draft.getImportedProblemId(), finalDraft, authorization, null);
        }
        applyUpdate(draft, finalDraft, userId);
        problemDraftMapper.updateById(draft);
        return toResponse(draft);
    }

    public ProblemDraftResponse regenerate(Long parentId, Long userId, ProblemDraftRegenerateRequest request) {
        return regenerate(parentId, userId, request, ProblemDraftProgressListener.NOOP, ProblemDraftAuditContext.NONE);
    }

    ProblemDraftResponse regenerationSource(Long id) {
        return toResponse(requireMutableDraft(id));
    }

    public ProblemDraftResponse regenerate(Long parentId, Long userId, ProblemDraftRegenerateRequest request,
                                           ProblemDraftProgressListener progressListener,
                                           ProblemDraftAuditContext auditContext) {
        if (request == null) {
            throw new DomainException(ErrorCode.BAD_REQUEST, "Regeneration request is required");
        }
        ProblemDraftProgressListener progress = progressListener == null ? ProblemDraftProgressListener.NOOP : progressListener;
        ProblemDraftAuditContext audit = auditContext == null ? ProblemDraftAuditContext.NONE : auditContext;
        ProblemDraftEntity parent = requireMutableDraft(parentId);
        ProblemDraftResponse parentDraft = toResponse(parent);
        Long draftId = IdWorker.getId();
        ProblemDraftRequest repairRequest = regenerationRepairRequest(parentDraft);
        if (shouldOnlyReverify(parentDraft, request.feedback())) {
            progress.onProgress("SANDBOX_VERIFYING", 2, 7, "Re-running draft verification without content changes");
            ProblemDraftResponse verified = verifyAndPersistRegenerated(
                    userId,
                    copyForRegeneration(draftId, parentDraft, request.feedback()),
                    repairRequest,
                    progress,
                    audit,
                    false
            );
            return verified;
        }
        aiQuotaService.assertMonthlyAvailable(userId);
        ProblemDraftResponse generated = null;
        boolean providerCalled = false;
        try {
            ProblemDraftResponse draftToVerify;
            if (shouldRepairFromCurrentReport(parentDraft, request.feedback())) {
                progress.onProgress("REPAIRING", 2, 7, "Repairing draft from current verification report");
                ProblemDraftResponse repairBase = copyForRegeneration(draftId, parentDraft, request.feedback());
                int maxAttempts = maxAutoRepairAttempts();
                providerCalled = true;
                draftToVerify = aiCapacityService.call(
                        AiCapacityService.AiWorkload.PROBLEM_DRAFT,
                        () -> problemDraftRepairer.repair(repairBase, repairRequest,
                                parentDraft.verificationReportJson(), 1, Math.max(1, maxAttempts))
                );
            } else {
                progress.onProgress("REGENERATING", 1, 7, "Regenerating draft with scoped field merge");
                providerCalled = true;
                generated = aiCapacityService.call(
                        AiCapacityService.AiWorkload.PROBLEM_DRAFT,
                        () -> aiProvider.regenerateProblemDraft(draftId, parentDraft, request.feedback())
                );
                if (generated == null) {
                    throw new DomainException(ErrorCode.INTERNAL_ERROR, "Provider returned no draft");
                }
                if (isInvalidRegeneratedDraft(generated)) {
                    throw new DomainException(ErrorCode.INTERNAL_ERROR,
                            "AI provider returned invalid regenerated draft JSON; rewrite was not applied");
                }
                draftToVerify = mergeRegeneratedDraft(draftId, parentDraft, generated, request.feedback());
            }
            ProblemDraftResponse verifiedDraft = verifyAndPersistRegenerated(userId, draftToVerify, repairRequest,
                    progress, audit, true);
            aiQuotaService.record(userId, aiProvider.providerName(), verifiedDraft.model(),
                    verifiedDraft.promptTokens(), verifiedDraft.completionTokens(), true);
            return verifiedDraft;
        } catch (RuntimeException ex) {
            recordFailure(audit, userId, draftId, "REGENERATE", repairRequest, ex);
            if (providerCalled) {
                long promptTokens = generated == null ? 0 : generated.promptTokens();
                long completionTokens = generated == null ? 0 : generated.completionTokens();
                aiQuotaService.record(userId, aiProvider.providerName(), aiProvider.model(), promptTokens, completionTokens, false);
            }
            throw ex;
        }
    }

    public ProblemDraftResponse approve(Long id, Long reviewerUserId, ProblemDraftApprovalRequest request, String authorization) {
        ProblemDraftEntity draft = requireMutableDraft(id);
        boolean importProblem = request != null && Boolean.TRUE.equals(request.importProblem());
        if (importProblem && !STATUS_APPROVED.equals(draft.getStatus())) {
            throw new DomainException(ErrorCode.CONFLICT, "Problem draft must be approved before import");
        }
        if (importProblem && !"VALID".equals(draft.getValidationStatus())) {
            throw new DomainException(ErrorCode.VALIDATION_FAILED,
                    "Cannot import an invalid draft. Please regenerate or fix the validation errors first.");
        }
        if (importProblem && !VERIFICATION_EXECUTION_VERIFIED.equals(draft.getVerificationStatus())
                && !VERIFICATION_MANUAL_VERIFIED.equals(draft.getVerificationStatus())) {
            throw new DomainException(ErrorCode.VALIDATION_FAILED,
                    "Cannot import a draft without successful execution verification. Please regenerate or fix it first.");
        }
        ProblemDraftTestcaseArtifactEntity testcaseArtifact = null;
        Path testcaseArtifactPath = null;
        if (importProblem) {
            boolean manualVerification = VERIFICATION_MANUAL_VERIFIED.equals(draft.getVerificationStatus());
            testcaseArtifact = manualVerification && testcaseArtifactService != null
                    ? testcaseArtifactService.latestReady(draft.getId())
                    : requireReadyTestcaseArtifact(draft.getId());
            if (testcaseArtifact != null) {
                testcaseArtifactPath = testcaseArtifactService.resolvePath(testcaseArtifact);
            }
        }
        Long importedProblemId = draft.getImportedProblemId();
        ProblemVisibility requestedVisibility = request == null ? null : request.visibility();
        if (importProblem && importedProblemId == null) {
            importedProblemId = problemServiceClient.createProblem(toResponse(draft), authorization, requestedVisibility);
        } else if (importProblem) {
            importedProblemId = problemServiceClient.updateProblem(importedProblemId, toResponse(draft), authorization, requestedVisibility);
        }
        if (importProblem && testcaseArtifact != null) {
            Long packageId = problemServiceClient.uploadAndActivateTestcasePackage(importedProblemId, testcaseArtifact,
                    testcaseArtifactPath, authorization);
            testcaseArtifactService.markImported(testcaseArtifact.getId(), importedProblemId, packageId);
        }
        return finalizeApproval(id, reviewerUserId, importedProblemId);
    }

    public ProblemDraftResponse manualReview(Long id, Long reviewerUserId) {
        ProblemDraftEntity draft = requireMutableDraft(id);
        if (draft.getImportedProblemId() != null) {
            throw new DomainException(ErrorCode.CONFLICT, "Problem draft is already imported");
        }
        LocalDateTime now = LocalDateTime.now();
        draft.setStatus(STATUS_APPROVED);
        draft.setReviewedAt(now);
        draft.setReviewedBy(reviewerUserId);
        draft.setValidationStatus("VALID");
        draft.setValidationErrors(toJson(List.of()));
        draft.setVerificationStatus(VERIFICATION_MANUAL_VERIFIED);
        draft.setVerificationReportJson(manualVerificationReportJson(reviewerUserId, now));
        draft.setLastRepairReason(null);
        problemDraftMapper.updateById(draft);
        return toResponse(draft);
    }

    private String manualVerificationReportJson(Long reviewerUserId, LocalDateTime reviewedAt) {
        Map<String, Object> manualReview = new LinkedHashMap<>();
        manualReview.put("reviewerUserId", reviewerUserId);
        manualReview.put("reviewedAt", reviewedAt.atZone(ZONE).toInstant().toString());
        manualReview.put("note", "Manual review passed; automated verification results were superseded.");
        Map<String, Object> report = new LinkedHashMap<>();
        report.put("status", "MANUAL_REVIEW_PASSED");
        report.put("manualReview", manualReview);
        return toJson(report);
    }

    private ProblemDraftTestcaseArtifactEntity requireReadyTestcaseArtifact(Long draftId) {
        if (testcaseArtifactService == null) {
            throw new DomainException(ErrorCode.INTERNAL_ERROR, "Problem draft testcase artifact service is unavailable");
        }
        ProblemDraftTestcaseArtifactEntity artifact = testcaseArtifactService.latestReady(draftId);
        if (artifact == null) {
            throw new DomainException(ErrorCode.VALIDATION_FAILED,
                    "Cannot import without a verified official testcase package. Please rerun verification.");
        }
        return artifact;
    }

    private ProblemDraftResponse finalizeApproval(Long id, Long reviewerUserId, Long importedProblemId) {
        ProblemDraftResponse response = transactionTemplate.execute(status -> {
            ProblemDraftEntity draft = problemDraftMapper.selectById(id);
            if (draft == null || draft.getDeletedAt() != null || draft.getArchivedAt() != null) {
                throw new DomainException(ErrorCode.NOT_FOUND, "Problem draft not found");
            }
            if (importedProblemId != null) {
                draft.setImportedProblemId(importedProblemId);
            }
            draft.setStatus(STATUS_APPROVED);
            draft.setReviewedAt(LocalDateTime.now());
            draft.setReviewedBy(reviewerUserId);
            problemDraftMapper.updateById(draft);
            return toResponse(draft);
        });
        if (response == null) {
            throw new DomainException(ErrorCode.INTERNAL_ERROR, "Problem draft approval failed");
        }
        return response;
    }

    @Transactional
    public ProblemDraftResponse reject(Long id, Long reviewerUserId, String reasonNote) {
        ProblemDraftEntity draft = requireMutableDraft(id);
        if (STATUS_APPROVED.equals(draft.getStatus()) && draft.getImportedProblemId() != null) {
            throw new DomainException(ErrorCode.CONFLICT, "Cannot reject an already-imported draft");
        }
        draft.setStatus(STATUS_REJECTED);
        draft.setReviewedAt(LocalDateTime.now());
        draft.setReviewedBy(reviewerUserId);
        if (reasonNote != null && !reasonNote.isBlank()) {
            draft.setValidationErrors(toJson(List.of("REJECT_REASON: " + reasonNote.trim())));
        }
        problemDraftMapper.updateById(draft);
        return toResponse(draft);
    }

    @Transactional
    public void delete(Long id) {
        ProblemDraftEntity draft = requireVisibleDraft(id);
        if (draft.getArchivedAt() == null) {
            throw new DomainException(ErrorCode.BAD_REQUEST, "Only archived problem drafts can be deleted");
        }
        draft.setDeletedAt(LocalDateTime.now());
        draft.setDeletedBy(SecuritySupport.currentUserId());
        problemDraftMapper.updateById(draft);
        auditWriter.record("SOFT_DELETE", "PROBLEM_DRAFT", draft.getId(), "SUCCESS",
                java.util.Map.of("title", draft.getTitle()));
    }

    @Transactional
    public ProblemDraftResponse archive(Long id) {
        ProblemDraftEntity draft = requireVisibleDraft(id);
        if (draft.getArchivedAt() == null) {
            draft.setArchivedAt(LocalDateTime.now());
            problemDraftMapper.updateById(draft);
        }
        auditWriter.record("ARCHIVE", "PROBLEM_DRAFT", draft.getId(), "SUCCESS",
                java.util.Map.of("title", draft.getTitle()));
        return toResponse(draft);
    }

    @Transactional
    public ProblemDraftResponse restore(Long id) {
        ProblemDraftEntity draft = requireVisibleDraft(id);
        if (draft.getArchivedAt() == null) {
            throw new DomainException(ErrorCode.BAD_REQUEST, "Only archived problem drafts can be restored");
        }
        problemDraftMapper.update(new ProblemDraftEntity(), new UpdateWrapper<ProblemDraftEntity>()
                .eq("id", draft.getId())
                .set("archived_at", null));
        draft.setArchivedAt(null);
        auditWriter.record("RESTORE", "PROBLEM_DRAFT", draft.getId(), "SUCCESS",
                java.util.Map.of("title", draft.getTitle()));
        return toResponse(draft);
    }

    private void persist(Long userId, ProblemDraftResponse response) {
        ProblemDraftEntity entity = new ProblemDraftEntity();
        entity.setId(response.id());
        entity.setCreatorUserId(userId);
        entity.setTitle(response.title());
        entity.setDifficulty(response.difficulty());
        entity.setDraftJson(toJson(new DraftPayload(
                response.statement(),
                response.notes(),
                response.standardSolutionLanguage(),
                response.standardSolutionCode(),
                response.referenceSolutionLanguage(),
                response.referenceSolutionCode(),
                response.testcaseGeneratorPython(),
                response.stressTestcaseGeneratorPython(),
                response.generationPlan(),
                response.tags(),
                response.testCases(),
                response.timeLimitMillis(),
                response.memoryLimitKb(),
                response.promptTokens(),
                response.completionTokens()
        )));
        entity.setValidationStatus(response.validationStatus());
        entity.setValidationErrors(toJson(response.validationErrors()));
        entity.setVerificationStatus(nonBlank(response.verificationStatus(), VERIFICATION_NOT_RUN));
        entity.setVerificationReportJson(response.verificationReportJson());
        entity.setRepairAttemptCount(repairAttemptCount(response.repairAttemptCount()));
        entity.setLastRepairReason(response.lastRepairReason());
        entity.setModel(response.model());
        entity.setStatus(STATUS_PENDING_REVIEW);
        entity.setImportedProblemId(response.importedProblemId());
        entity.setRefinedFromDraftId(response.refinedFromDraftId());
        entity.setRefineNote(response.refineNote());
        entity.setCreatedAt(LocalDateTime.now());
        problemDraftMapper.insert(entity);
    }

    private void applyUpdate(ProblemDraftEntity entity, ProblemDraftResponse response, Long userId) {
        if (entity.getCreatorUserId() == null) {
            entity.setCreatorUserId(userId);
        }
        entity.setTitle(response.title());
        entity.setDifficulty(response.difficulty());
        entity.setDraftJson(toJson(new DraftPayload(
                response.statement(),
                response.notes(),
                response.standardSolutionLanguage(),
                response.standardSolutionCode(),
                response.referenceSolutionLanguage(),
                response.referenceSolutionCode(),
                response.testcaseGeneratorPython(),
                response.stressTestcaseGeneratorPython(),
                response.generationPlan(),
                response.tags(),
                response.testCases(),
                response.timeLimitMillis(),
                response.memoryLimitKb(),
                response.promptTokens(),
                response.completionTokens()
        )));
        entity.setValidationStatus(response.validationStatus());
        entity.setValidationErrors(toJson(response.validationErrors()));
        entity.setVerificationStatus(nonBlank(response.verificationStatus(), VERIFICATION_NOT_RUN));
        entity.setVerificationReportJson(response.verificationReportJson());
        entity.setRepairAttemptCount(repairAttemptCount(response.repairAttemptCount()));
        entity.setLastRepairReason(response.lastRepairReason());
        entity.setModel(response.model());
        entity.setRefineNote(response.refineNote());
        if (entity.getImportedProblemId() == null) {
            entity.setStatus(STATUS_PENDING_REVIEW);
            entity.setReviewedAt(null);
            entity.setReviewedBy(null);
        } else {
            entity.setStatus(STATUS_APPROVED);
        }
    }

    private ProblemDraftResponse verifyAndPersistRegenerated(Long userId, ProblemDraftResponse draft,
                                                             ProblemDraftRequest repairRequest,
                                                             ProblemDraftProgressListener progress,
                                                             ProblemDraftAuditContext auditContext,
                                                             boolean requireVerified) {
        StressGeneratorOutcome stressGeneratorOutcome = generateStressGeneratorIfRequired(draft.id(), userId, repairRequest,
                draft, progress, auditContext);
        if (!stressGeneratorOutcome.errors().isEmpty()) {
            VerificationError error = stressGeneratorOutcome.errors().get(0);
            throw new DomainException(ErrorCode.INTERNAL_ERROR,
                    error.code() + ": " + nonBlank(error.message(), "stressTestcaseGeneratorPython was not generated"));
        }
        draft = stressGeneratorOutcome.response();
        VerificationOptions verificationOptions = verificationOptionsForDraft(draft);
        progress.onProgress("STATIC_VERIFYING", 3, 7, "Running static validation");
        VerificationReport staticReport = staticReport(draft, verificationOptions, draft.validationErrors(),
                draft.timeLimitMillis(), draft.memoryLimitKb(), List.of());
        ProblemDraftResponse withValidation = withValidation(draft,
                staticReport.errorMessages().isEmpty() ? "VALID" : "INVALID",
                staticReport.errorMessages());
        progress.onProgress("SANDBOX_VERIFYING", 4, 7, "Running sandbox verification");
        VerificationOutcome verification = verifyFinalDraft(withValidation, staticReport, verificationOptions);
        ProblemDraftResponse verified = withVerification(withValidation, verification);
        recordVerification(auditContext, userId, verified.id(), verified, staticReport, verification);
        if (requireVerified) {
            verified = repairIfRequested(repairRequest, verified, verification, verificationOptions, progress, userId, auditContext);
            if (!VERIFICATION_EXECUTION_VERIFIED.equals(verified.verificationStatus())) {
                throw new DomainException(ErrorCode.VALIDATION_FAILED, rewriteVerificationFailedMessage(verified));
            }
        }
        progress.onProgress("PERSISTING", 6, 7, "Saving rewritten draft");
        persist(userId, verified);
        progress.onProgress("SUCCEEDED", 7, 7, "Problem draft rewrite completed");
        return verified;
    }

    private ProblemDraftResponse copyForRegeneration(Long id, ProblemDraftResponse parent, String feedback) {
        return new ProblemDraftResponse(
                id,
                STATUS_PENDING_REVIEW,
                parent.title(),
                parent.difficulty(),
                parent.statement(),
                parent.notes(),
                parent.standardSolutionLanguage(),
                parent.standardSolutionCode(),
                parent.referenceSolutionLanguage(),
                parent.referenceSolutionCode(),
                parent.testcaseGeneratorPython(),
                parent.stressTestcaseGeneratorPython(),
                regenerationPlan(parent.generationPlan(), feedback),
                parent.tags(),
                "VALID",
                List.of(),
                parent.testCases(),
                parent.timeLimitMillis(),
                parent.memoryLimitKb(),
                null,
                nonBlank(parent.model(), aiProvider.model()),
                0,
                0,
                Instant.now(),
                null,
                null,
                null,
                parent.id(),
                feedback,
                VERIFICATION_NOT_RUN,
                null,
                0,
                null
        );
    }

    private ProblemDraftResponse mergeRegeneratedDraft(Long id, ProblemDraftResponse parent, ProblemDraftResponse raw,
                                                       String feedback) {
        FieldMergePolicy policy = fieldMergePolicy(feedback);
        Integer rawTime = raw.timeLimitMillis();
        Integer rawMem = raw.memoryLimitKb();
        return new ProblemDraftResponse(
                id,
                STATUS_PENDING_REVIEW,
                policy.title() ? nonBlank(raw.title(), parent.title()) : parent.title(),
                policy.difficulty() ? nonBlank(raw.difficulty(), parent.difficulty()) : parent.difficulty(),
                policy.statement() ? nonBlank(raw.statement(), parent.statement()) : parent.statement(),
                policy.notes() ? nonBlank(raw.notes(), parent.notes()) : parent.notes(),
                policy.standardSolution() ? nonBlank(raw.standardSolutionLanguage(), parent.standardSolutionLanguage()) : parent.standardSolutionLanguage(),
                policy.standardSolution() ? nonBlank(raw.standardSolutionCode(), parent.standardSolutionCode()) : parent.standardSolutionCode(),
                policy.referenceSolution() ? defaultReferenceSolutionLanguage(raw.referenceSolutionLanguage(), raw.referenceSolutionCode(),
                        nonBlank(raw.standardSolutionLanguage(), parent.standardSolutionLanguage())) : parent.referenceSolutionLanguage(),
                policy.referenceSolution() ? raw.referenceSolutionCode() : parent.referenceSolutionCode(),
                policy.testcaseGenerator() ? nonBlank(raw.testcaseGeneratorPython(), parent.testcaseGeneratorPython()) : parent.testcaseGeneratorPython(),
                policy.stressGenerator() ? raw.stressTestcaseGeneratorPython() : parent.stressTestcaseGeneratorPython(),
                policy.generationPlan() ? nonBlank(raw.generationPlan(), regenerationPlan(parent.generationPlan(), feedback))
                        : regenerationPlan(parent.generationPlan(), feedback),
                policy.tags() ? (raw.tags() == null ? List.of() : raw.tags()) : parent.tags(),
                "VALID",
                raw.validationErrors() == null ? List.of() : raw.validationErrors(),
                policy.testCases() ? normalizeTestCases(raw.testCases()) : parent.testCases(),
                policy.limits() ? limitOrDefault(rawTime, DEFAULT_TIME_LIMIT_MILLIS) : parent.timeLimitMillis(),
                policy.limits() ? limitOrDefault(rawMem, DEFAULT_MEMORY_LIMIT_KB) : parent.memoryLimitKb(),
                null,
                nonBlank(raw.model(), aiProvider.model()),
                Math.max(0, raw.promptTokens()),
                Math.max(0, raw.completionTokens()),
                Instant.now(),
                null,
                null,
                null,
                parent.id(),
                feedback,
                VERIFICATION_NOT_RUN,
                null,
                0,
                null
        );
    }

    private ProblemDraftRequest regenerationRepairRequest(ProblemDraftResponse draft) {
        return new ProblemDraftRequest(
                nonBlank(draft.title(), "AI rewrite"),
                draft.difficulty(),
                null,
                null,
                null,
                draft.tags(),
                null,
                null,
                null,
                null,
                draft.standardSolutionLanguage(),
                null,
                null,
                null,
                draft.testcaseGeneratorPython() == null || draft.testcaseGeneratorPython().isBlank() ? null : 3,
                null,
                null,
                true,
                hasText(draft.referenceSolutionCode()) || hasText(draft.stressTestcaseGeneratorPython())
        );
    }

    private boolean shouldOnlyReverify(ProblemDraftResponse parent, String feedback) {
        if (hasVerificationCode(parent, "SANDBOX_UNAVAILABLE") && containsAnyNormalized(feedback,
                "sandbox", "SANDBOX_UNAVAILABLE", "沙箱", "验证", "校验", "重跑", "重新")) {
            return true;
        }
        return containsAnyNormalized(feedback, "重跑", "重新跑", "再跑", "只跑", "验证", "校验", "静态校验", "样例校验",
                "官方隐藏点", "随机对拍", "复杂度审计")
                && !containsThemeChange(feedback)
                && !containsAnyNormalized(feedback, "修复", "改正", "解决", "处理")
                && !containsAnyNormalized(feedback, "修复生成器", "修复题面", "修复标程", "修复样例", "修复数据范围");
    }

    private boolean shouldRepairFromCurrentReport(ProblemDraftResponse parent, String feedback) {
        if (problemDraftRepairer == null || parent.verificationReportJson() == null || parent.verificationReportJson().isBlank()) {
            return false;
        }
        if (hasVerificationCode(parent, "SANDBOX_UNAVAILABLE") || containsThemeChange(feedback)) {
            return false;
        }
        RepairTask task = verificationFailureClassifier.classify(parent.verificationReportJson());
        return !task.allowedFields().isEmpty()
                && containsAnyNormalized(feedback, "修复", "改正", "处理", "解决", "完善验证", "修一下", "重新验证");
    }

    private int maxAutoRepairAttempts() {
        return problemDraftRepairer == null ? 0 : problemDraftRepairer.maxRepairAttempts();
    }

    private String rewriteVerificationFailedMessage(ProblemDraftResponse draft) {
        int attempts = repairAttemptCount(draft.repairAttemptCount());
        RepairTask task = verificationFailureClassifier.classify(draft.verificationReportJson());
        String evidence = task.evidence() == null || task.evidence().isEmpty()
                ? nonBlank(draft.verificationStatus(), "verification failed")
                : String.join("; ", task.evidence());
        return "Problem draft rewrite did not pass verification after " + attempts
                + " auto repair attempts: " + truncate(evidence, 500);
    }

    private FieldMergePolicy fieldMergePolicy(String feedback) {
        boolean theme = containsThemeChange(feedback);
        boolean statement = theme || containsAnyNormalized(feedback, "题面", "描述", "输入", "输出", "数据范围", "约束", "规格");
        boolean samples = statement || containsAnyNormalized(feedback, "样例", "样例说明");
        boolean officialGenerator = containsAnyNormalized(feedback, "隐藏点", "官方", "生成器", "测试点", "testcaseGeneratorPython");
        boolean stress = containsAnyNormalized(feedback, "随机对拍", "对拍", "reference", "stress");
        boolean solution = statement || officialGenerator || stress || containsAnyNormalized(feedback, "标程", "代码", "复杂度", "解法");
        boolean limits = containsAnyNormalized(feedback, "时限", "内存", "复杂度", "数据范围", "约束");
        boolean broad = theme || !(statement || samples || officialGenerator || stress || solution || limits);
        return new FieldMergePolicy(
                theme,
                theme,
                broad || statement,
                broad || statement || samples,
                broad || solution,
                stress,
                broad || officialGenerator,
                stress,
                broad || statement || officialGenerator || stress || solution,
                theme,
                broad || samples,
                broad || limits
        );
    }

    private boolean containsThemeChange(String feedback) {
        if (feedback == null || feedback.isBlank()) {
            return false;
        }
        if (containsAnyNormalized(feedback, "换题", "改成另一题")) {
            return true;
        }
        if (containsProtectedThemeContext(feedback)
                && !containsAnyNormalized(feedback, "改为", "改成", "换成", "调整为", "变更为")) {
            return false;
        }
        return containsAnyNormalized(feedback,
                "修改主题", "更改主题", "调整主题", "主题改",
                "修改标签", "更改标签", "调整标签", "标签改",
                "修改算法", "更改算法", "调整算法", "算法改",
                "修改难度", "更改难度", "调整难度", "难度改",
                "修改标题", "更改标题", "调整标题", "标题改",
                "核心考点");
    }

    private boolean containsProtectedThemeContext(String feedback) {
        return containsAnyNormalized(feedback, "不修改", "不要修改", "不得修改", "不能修改",
                "不改", "不要改", "不得改", "不能改", "保持", "保留", "不改变", "不变")
                && containsAnyNormalized(feedback, "主题", "标签", "算法", "难度", "标题", "核心考点");
    }

    private boolean isInvalidRegeneratedDraft(ProblemDraftResponse draft) {
        if (draft == null || !"INVALID".equalsIgnoreCase(draft.validationStatus())) {
            return false;
        }
        if (draft.validationErrors() == null || draft.validationErrors().isEmpty()) {
            return true;
        }
        return draft.validationErrors().stream()
                .anyMatch(error -> error != null
                        && error.toLowerCase(Locale.ROOT).contains("invalid regenerated draft json"));
    }

    private boolean hasVerificationCode(ProblemDraftResponse draft, String code) {
        return draft != null
                && draft.verificationReportJson() != null
                && draft.verificationReportJson().toUpperCase(Locale.ROOT).contains(code.toUpperCase(Locale.ROOT));
    }

    private boolean containsAnyNormalized(String value, String... needles) {
        if (value == null || value.isBlank()) {
            return false;
        }
        String normalized = value.toLowerCase(Locale.ROOT);
        for (String needle : needles) {
            if (needle != null && !needle.isBlank() && normalized.contains(needle.toLowerCase(Locale.ROOT))) {
                return true;
            }
        }
        return false;
    }

    private String regenerationPlan(String currentPlan, String feedback) {
        String note = "AI rewrite request: " + nonBlank(feedback, "no feedback");
        if (currentPlan == null || currentPlan.isBlank()) {
            return note;
        }
        return currentPlan + "\n\n" + note;
    }

    private ProblemDraftResponse withValidation(ProblemDraftResponse source, String status, List<String> errors) {
        return new ProblemDraftResponse(source.id(), source.status(), source.title(), source.difficulty(), source.statement(),
                source.notes(), source.standardSolutionLanguage(), source.standardSolutionCode(),
                source.referenceSolutionLanguage(), source.referenceSolutionCode(),
                source.testcaseGeneratorPython(), source.stressTestcaseGeneratorPython(), source.generationPlan(), source.tags(), status, errors,
                source.testCases(), source.timeLimitMillis(), source.memoryLimitKb(),
                source.importedProblemId(), source.model(), source.promptTokens(), source.completionTokens(),
                source.createdAt(), source.archivedAt(), source.deletedAt(), source.deletedBy(),
                source.refinedFromDraftId(), source.refineNote(), source.verificationStatus(),
                source.verificationReportJson(), source.repairAttemptCount(), source.lastRepairReason());
    }

    private ProblemDraftResponse withVerification(ProblemDraftResponse source, VerificationOutcome verification) {
        return new ProblemDraftResponse(source.id(), source.status(), source.title(), source.difficulty(), source.statement(),
                source.notes(), source.standardSolutionLanguage(), source.standardSolutionCode(),
                source.referenceSolutionLanguage(), source.referenceSolutionCode(),
                source.testcaseGeneratorPython(), source.stressTestcaseGeneratorPython(), source.generationPlan(), source.tags(), source.validationStatus(),
                source.validationErrors(), source.testCases(), source.timeLimitMillis(), source.memoryLimitKb(),
                source.importedProblemId(), source.model(), source.promptTokens(), source.completionTokens(),
                source.createdAt(), source.archivedAt(), source.deletedAt(), source.deletedBy(),
                source.refinedFromDraftId(), source.refineNote(), verification.status(),
                verification.reportJson(), source.repairAttemptCount(), source.lastRepairReason());
    }

    private ProblemDraftResponse repairIfRequested(ProblemDraftRequest request, ProblemDraftResponse response,
                                                   VerificationOutcome verification, VerificationOptions options) {
        return repairIfRequested(request, response, verification, options, ProblemDraftProgressListener.NOOP);
    }

    private ProblemDraftResponse repairIfRequested(ProblemDraftRequest request, ProblemDraftResponse response,
                                                   VerificationOutcome verification, VerificationOptions options,
                                                   ProblemDraftProgressListener progress) {
        return repairIfRequested(request, response, verification, options, progress, null, ProblemDraftAuditContext.NONE);
    }

    private ProblemDraftResponse repairIfRequested(ProblemDraftRequest request, ProblemDraftResponse response,
                                                   VerificationOutcome verification, VerificationOptions options,
                                                   ProblemDraftProgressListener progress, Long userId,
                                                   ProblemDraftAuditContext auditContext) {
        if (!Boolean.TRUE.equals(request.enableAutoRepair()) || verification.passed()) {
            return response;
        }
        int maxAttempts = problemDraftRepairer == null ? 0 : problemDraftRepairer.maxRepairAttempts();
        if (maxAttempts <= 0) {
            return response;
        }
        ProblemDraftResponse current = response;
        VerificationOutcome currentVerification = verification;
        int attempts = repairAttemptCount(current.repairAttemptCount());
        while (!currentVerification.passed() && attempts < maxAttempts) {
            int attempt = attempts + 1;
            ProblemDraftResponse draftToRepair = current;
            String reportJson = currentVerification.reportJson();
            try {
                progress.onProgress("REPAIRING", 5, 7,
                        "Running auto repair attempt " + attempt + " of " + maxAttempts);
                ProblemDraftResponse repaired = aiCapacityService.call(
                        AiCapacityService.AiWorkload.PROBLEM_DRAFT,
                        () -> problemDraftRepairer.repair(draftToRepair, request, reportJson, attempt, maxAttempts)
                );
                VerificationReport staticReport = staticReport(repaired, request, null,
                        repaired.timeLimitMillis(), repaired.memoryLimitKb());
                repaired = withValidation(repaired, staticReport.errorMessages().isEmpty() ? "VALID" : "INVALID",
                        staticReport.errorMessages());
                progress.onProgress("SANDBOX_VERIFYING", 5, 7,
                        "Verifying repaired draft attempt " + attempt);
                currentVerification = verifyFinalDraft(repaired, staticReport, options);
                current = withRepairMetadata(withVerification(repaired, currentVerification), attempt,
                        repaired.lastRepairReason());
                recordRepair(auditContext, userId, current.id(), attempt, current, currentVerification);
                attempts = attempt;
            } catch (RuntimeException ex) {
                recordFailure(auditContext, userId, current.id(), "REPAIR", request, ex);
                return withRepairMetadata(current, attempt,
                        "Auto repair attempt " + attempt + " failed: " + nonBlank(ex.getMessage(), ex.getClass().getSimpleName()));
            }
        }
        return current;
    }

    private void recordRequirement(ProblemDraftAuditContext auditContext, Long userId, Long draftId,
                                   ProblemDraftRequest request) {
        if (auditSnapshotService != null) {
            auditSnapshotService.recordRequirement(auditContext, userId, draftId, request);
        }
    }

    private void recordPlan(ProblemDraftAuditContext auditContext, Long userId, Long draftId,
                            ProblemDraftRequest request, ProblemDraftResponse response) {
        if (auditSnapshotService != null) {
            auditSnapshotService.recordPlan(auditContext, userId, draftId, request, response);
        }
    }

    private void recordDraft(ProblemDraftAuditContext auditContext, Long userId, Long draftId,
                             ProblemDraftResponse response) {
        if (auditSnapshotService != null) {
            auditSnapshotService.recordDraft(auditContext, userId, draftId, response);
        }
    }

    private void recordStressGenerator(ProblemDraftAuditContext auditContext, Long userId, Long draftId,
                                       ProblemDraftResponse response, List<VerificationError> errors) {
        if (auditSnapshotService != null) {
            auditSnapshotService.recordStressGenerator(auditContext, userId, draftId, response, errors);
        }
    }

    private void recordVerification(ProblemDraftAuditContext auditContext, Long userId, Long draftId,
                                    ProblemDraftResponse response, VerificationReport staticReport,
                                    VerificationOutcome verification) {
        if (auditSnapshotService != null) {
            auditSnapshotService.recordVerification(auditContext, userId, draftId, response, staticReport,
                    verification == null ? null : verification.status(),
                    verification == null ? null : verification.reportJson());
        }
    }

    private void recordRepair(ProblemDraftAuditContext auditContext, Long userId, Long draftId, int attempt,
                              ProblemDraftResponse response, VerificationOutcome verification) {
        if (auditSnapshotService != null) {
            auditSnapshotService.recordRepair(auditContext, userId, draftId, attempt, response,
                    verification == null ? null : verification.status(),
                    verification == null ? null : verification.reportJson());
        }
    }

    private void recordFailure(ProblemDraftAuditContext auditContext, Long userId, Long draftId, String stage,
                               ProblemDraftRequest request, RuntimeException error) {
        if (auditSnapshotService != null) {
            auditSnapshotService.recordFailure(auditContext, userId, draftId, stage, request, error);
        }
    }

    private boolean planGateFailure(RuntimeException ex) {
        return ex instanceof DomainException
                && ex.getMessage() != null
                && ex.getMessage().contains("Problem design plan gate failed");
    }

    private ProblemDraftResponse withRepairMetadata(ProblemDraftResponse source, int repairAttemptCount,
                                                    String lastRepairReason) {
        return new ProblemDraftResponse(source.id(), source.status(), source.title(), source.difficulty(), source.statement(),
                source.notes(), source.standardSolutionLanguage(), source.standardSolutionCode(),
                source.referenceSolutionLanguage(), source.referenceSolutionCode(),
                source.testcaseGeneratorPython(), source.stressTestcaseGeneratorPython(), source.generationPlan(), source.tags(), source.validationStatus(),
                source.validationErrors(), source.testCases(), source.timeLimitMillis(), source.memoryLimitKb(),
                source.importedProblemId(), source.model(), source.promptTokens(), source.completionTokens(),
                source.createdAt(), source.archivedAt(), source.deletedAt(), source.deletedBy(),
                source.refinedFromDraftId(), source.refineNote(), source.verificationStatus(),
                source.verificationReportJson(), repairAttemptCount(repairAttemptCount), truncate(lastRepairReason, 1000));
    }

    private ProblemDraftResponse toResponse(ProblemDraftEntity entity) {
        DraftPayload payload = readPayload(entity.getDraftJson());
        return new ProblemDraftResponse(
                entity.getId(),
                entity.getStatus(),
                entity.getTitle(),
                entity.getDifficulty(),
                payload.statement(),
                payload.notes(),
                payload.standardSolutionLanguage(),
                payload.standardSolutionCode(),
                payload.referenceSolutionLanguage(),
                payload.referenceSolutionCode(),
                payload.testcaseGeneratorPython(),
                payload.stressTestcaseGeneratorPython(),
                payload.generationPlan(),
                payload.tags(),
                entity.getValidationStatus(),
                readErrors(entity.getValidationErrors()),
                normalizeTestCases(payload.testCases()),
                limitOrDefault(payload.timeLimitMillis(), DEFAULT_TIME_LIMIT_MILLIS),
                limitOrDefault(payload.memoryLimitKb(), DEFAULT_MEMORY_LIMIT_KB),
                entity.getImportedProblemId(),
                entity.getModel(),
                payload.promptTokens(),
                payload.completionTokens(),
                entity.getCreatedAt().atZone(ZONE).toInstant(),
                toInstant(entity.getArchivedAt()),
                toInstant(entity.getDeletedAt()),
                entity.getDeletedBy(),
                entity.getRefinedFromDraftId(),
                entity.getRefineNote(),
                nonBlank(entity.getVerificationStatus(), VERIFICATION_NOT_RUN),
                entity.getVerificationReportJson(),
                repairAttemptCount(entity.getRepairAttemptCount()),
                entity.getLastRepairReason()
        );
    }

    private ProblemDraftEntity requireVisibleDraft(Long id) {
        ProblemDraftEntity draft = problemDraftMapper.selectById(id);
        if (draft == null || draft.getDeletedAt() != null) {
            throw new DomainException(ErrorCode.NOT_FOUND, "Problem draft not found");
        }
        return draft;
    }

    private ProblemDraftEntity requireMutableDraft(Long id) {
        ProblemDraftEntity draft = requireVisibleDraft(id);
        if (draft.getArchivedAt() != null) {
            throw new DomainException(ErrorCode.CONFLICT, "Archived problem drafts cannot be modified");
        }
        return draft;
    }

    private Instant toInstant(LocalDateTime value) {
        return value == null ? null : value.atZone(ZONE).toInstant();
    }

    private VerificationReport staticReport(ProblemDraftResponse response, ProblemDraftRequest request,
                                            List<String> providerErrors, Integer rawTime, Integer rawMem) {
        VerificationOptions options = request == null
                ? VerificationOptions.defaults(List.of())
                : VerificationOptions.fromRequest(request, List.of());
        return staticReport(response, options, providerErrors, rawTime, rawMem, List.of());
    }

    private VerificationReport staticReport(ProblemDraftResponse response, VerificationOptions options,
                                            List<String> providerErrors, Integer rawTime, Integer rawMem,
                                            List<VerificationError> additionalErrors) {
        List<VerificationError> errors = new ArrayList<>();
        List<VerificationWarning> warnings = new ArrayList<>();
        if (providerErrors != null) {
            providerErrors.stream()
                    .filter(error -> error != null && !error.isBlank())
                    .map(error -> new VerificationError("PROVIDER_VALIDATION_ERROR", error, null))
                    .forEach(errors::add);
        }
        if (additionalErrors != null) {
            errors.addAll(additionalErrors.stream()
                    .filter(error -> error != null)
                    .toList());
        }
        VerificationReport report = problemDraftStaticValidator.verify(response,
                options == null ? VerificationOptions.defaults(List.of()) : options);
        errors.addAll(report.errors());
        warnings.addAll(report.warnings());
        if (rawTime == null || rawTime <= 0) {
            errors.add(new VerificationError("TIME_LIMIT_DEFAULTED",
                    "timeLimitMillis missing — defaulted to " + DEFAULT_TIME_LIMIT_MILLIS,
                    "timeLimitMillis"));
        }
        if (rawMem == null || rawMem <= 0) {
            errors.add(new VerificationError("MEMORY_LIMIT_DEFAULTED",
                    "memoryLimitKb missing — defaulted to " + DEFAULT_MEMORY_LIMIT_KB,
                    "memoryLimitKb"));
        }
        return new VerificationReport(errors.isEmpty() ? "PASSED" : "FAILED", errors, warnings);
    }

    private VerificationOutcome verifyFinalDraft(ProblemDraftResponse response, VerificationReport staticReport,
                                                 VerificationOptions options) {
        VerificationReport sandboxReport = null;
        CrossCheckReport crossCheckReport = null;
        ComplexityReport complexityReport = null;
        com.aioj.next.ai.domain.problem.OfficialTestcasePackageReport officialPackageReport = null;
        String status = VERIFICATION_STATIC_FAILED;
        if (staticReport.passed()) {
            try {
                DraftExecutionReport executionReport = draftSandboxClient.verifyDraftDetailed(response, options);
                if (executionReport == null) {
                    sandboxReport = draftSandboxClient.verifyDraft(response, options);
                } else {
                    sandboxReport = executionReport.sandboxReport();
                    crossCheckReport = executionReport.crossCheckReport();
                    complexityReport = executionReport.complexityReport();
                    officialPackageReport = executionReport.officialPackageReport();
                }
            } catch (RuntimeException ex) {
                sandboxReport = new VerificationReport(VERIFICATION_FAILED, List.of(new VerificationError(
                        "SANDBOX_UNAVAILABLE",
                        "sandbox verification failed: " + nonBlank(ex.getMessage(), ex.getClass().getSimpleName()),
                        null
                )), List.of());
            }
            if (sandboxReport == null) {
                sandboxReport = new VerificationReport(VERIFICATION_FAILED, List.of(new VerificationError(
                        "SANDBOX_NO_REPORT",
                        "sandbox verification returned no report",
                        null
                )), List.of());
            }
            boolean executionPassed = sandboxReport.passed()
                    && (crossCheckReport == null || crossCheckReport.passed())
                    && (complexityReport == null || complexityReport.passed());
            status = executionPassed ? VERIFICATION_EXECUTION_VERIFIED : VERIFICATION_FAILED;
        }
        boolean passed = staticReport.passed()
                && sandboxReport != null
                && sandboxReport.passed()
                && (crossCheckReport == null || crossCheckReport.passed())
                && (complexityReport == null || complexityReport.passed());
        StoredVerificationReport report = new StoredVerificationReport(status, staticReport, sandboxReport,
                crossCheckReport, complexityReport, officialPackageReport, null);
        RepairTask failureClassification = passed ? null : verificationFailureClassifier.classify(toJson(report));
        return new VerificationOutcome(status, toJson(new StoredVerificationReport(status, staticReport, sandboxReport,
                crossCheckReport, complexityReport, officialPackageReport, failureClassification)), passed);
    }

    private VerificationOptions verificationOptionsForDraft(ProblemDraftResponse draft) {
        boolean enableReferenceCheck = draft != null
                && (hasText(draft.referenceSolutionCode()) || hasText(draft.stressTestcaseGeneratorPython()));
        return new VerificationOptions(List.of(), draft == null ? null : draft.difficulty(), null, null, enableReferenceCheck);
    }

    private int repairAttemptCount(Integer value) {
        return value == null || value < 0 ? 0 : value;
    }

    private DraftPayload readPayload(String json) {
        try {
            DraftPayload payload = objectMapper.readValue(json, DraftPayload.class);
            return new DraftPayload(
                    payload.statement(),
                    payload.notes(),
                    defaultSolutionLanguage(payload.standardSolutionLanguage()),
                    payload.standardSolutionCode() == null ? "" : payload.standardSolutionCode(),
                    defaultReferenceSolutionLanguage(payload.referenceSolutionLanguage(), payload.referenceSolutionCode(), payload.standardSolutionLanguage()),
                    payload.referenceSolutionCode(),
                    payload.testcaseGeneratorPython() == null ? "" : payload.testcaseGeneratorPython(),
                    payload.stressTestcaseGeneratorPython(),
                    payload.generationPlan() == null ? "" : payload.generationPlan(),
                    payload.tags() == null ? List.of() : payload.tags(),
                    normalizeTestCases(payload.testCases()),
                    limitOrDefault(payload.timeLimitMillis(), DEFAULT_TIME_LIMIT_MILLIS),
                    limitOrDefault(payload.memoryLimitKb(), DEFAULT_MEMORY_LIMIT_KB),
                    Math.max(0, payload.promptTokens()),
                    Math.max(0, payload.completionTokens())
            );
        } catch (Exception ex) {
            return new DraftPayload("", null, "cpp", "", null, null, "", null, "", List.of(), List.of(),
                    DEFAULT_TIME_LIMIT_MILLIS, DEFAULT_MEMORY_LIMIT_KB, 0, 0);
        }
    }

    private List<String> readErrors(String json) {
        if (json == null || json.isBlank()) {
            return List.of();
        }
        try {
            return objectMapper.readValue(json, new TypeReference<>() {
            });
        } catch (Exception ex) {
            return List.of("Unable to parse validation errors");
        }
    }

    private String toJson(Object value) {
        try {
            return objectMapper.writeValueAsString(value);
        } catch (JsonProcessingException ex) {
            throw new DomainException(ErrorCode.INTERNAL_ERROR, "Unable to serialize problem draft");
        }
    }

    private String fallbackTitle(ProblemDraftRequest request) {
        return request.topic() + " practice";
    }

    private String fallbackDifficulty(ProblemDraftRequest request) {
        return ProblemDraftDifficulty.effective(request.difficulty(), request.cfRating());
    }

    private String fallbackNotes(String title) {
        return "本题由 AI 生成，建议教师在导入前检查题面、样例和隐藏测试点覆盖。"
                + "说明：重点关注 " + (title == null || title.isBlank() ? "本题" : title.trim()) + " 的边界情况、输入规模和输出格式。";
    }

    private ProblemDraftResponse emptyDraft(Long id, ProblemDraftRequest request) {
        return new ProblemDraftResponse(
                id,
                STATUS_PENDING_REVIEW,
                fallbackTitle(request),
                fallbackDifficulty(request),
                "",
                fallbackNotes(fallbackTitle(request)),
                defaultSolutionLanguage(request.standardSolutionLanguage()),
                "",
                null,
                null,
                "",
                null,
                fallbackGenerationPlan(request),
                List.of(),
                "INVALID",
                List.of("Provider returned no draft"),
                List.of(),
                DEFAULT_TIME_LIMIT_MILLIS,
                DEFAULT_MEMORY_LIMIT_KB,
                null,
                aiProvider.model(),
                0,
                0,
                Instant.now(),
                null,
                null,
                null,
                null,
                null,
                VERIFICATION_NOT_RUN,
                null,
                0,
                null
        );
    }

    private String nonBlank(String value, String fallback) {
        return value == null || value.isBlank() ? fallback : value;
    }

    private boolean hasText(String value) {
        return value != null && !value.isBlank();
    }

    private String truncate(String value, int maxLength) {
        if (value == null || value.length() <= maxLength) {
            return value;
        }
        return value.substring(0, maxLength);
    }

    private List<TestCaseDto> normalizeTestCases(List<TestCaseDto> testCases) {
        return testCases == null ? List.of() : testCases;
    }

    private int limitOrDefault(Integer value, int fallback) {
        return value == null || value <= 0 ? fallback : value;
    }

    private String defaultSolutionLanguage(String value) {
        if (value == null || value.isBlank()) {
            return "cpp";
        }
        String normalized = value.trim().toLowerCase();
        if ("c++".equals(normalized) || "c++17".equals(normalized) || "cpp17".equals(normalized)) {
            return "cpp";
        }
        if ("py".equals(normalized) || "python3".equals(normalized)) {
            return "python";
        }
        return normalized;
    }

    private String defaultReferenceSolutionLanguage(String language, String code, String standardLanguage) {
        if (code == null || code.isBlank()) {
            return null;
        }
        return defaultSolutionLanguage(nonBlank(language, standardLanguage));
    }

    private String fallbackGenerationPlan(ProblemDraftRequest request) {
        return "生成流程：题目信息规划 -> 测试点与 Python 生成脚本 -> 标程 -> 题面、样例和说明合成。主题："
                + (request.topic() == null ? "" : request.topic().trim());
    }

    private record DraftPayload(
            String statement,
            String notes,
            String standardSolutionLanguage,
            String standardSolutionCode,
            String referenceSolutionLanguage,
            String referenceSolutionCode,
            String testcaseGeneratorPython,
            String stressTestcaseGeneratorPython,
            String generationPlan,
            List<String> tags,
            List<TestCaseDto> testCases,
            Integer timeLimitMillis,
            Integer memoryLimitKb,
            long promptTokens,
            long completionTokens
    ) {
    }

    private record StoredVerificationReport(
            String status,
            VerificationReport staticReport,
            VerificationReport sandboxReport,
            CrossCheckReport crossCheckReport,
            ComplexityReport complexityReport,
            com.aioj.next.ai.domain.problem.OfficialTestcasePackageReport officialPackageReport,
            RepairTask failureClassification
    ) {
    }

    private record VerificationOutcome(
            String status,
            String reportJson,
            boolean passed
    ) {
    }

    private record StressGeneratorOutcome(
            ProblemDraftResponse response,
            List<VerificationError> errors
    ) {
    }

    private record FieldMergePolicy(
            boolean title,
            boolean difficulty,
            boolean statement,
            boolean notes,
            boolean standardSolution,
            boolean referenceSolution,
            boolean testcaseGenerator,
            boolean stressGenerator,
            boolean generationPlan,
            boolean tags,
            boolean testCases,
            boolean limits
    ) {
    }
}
