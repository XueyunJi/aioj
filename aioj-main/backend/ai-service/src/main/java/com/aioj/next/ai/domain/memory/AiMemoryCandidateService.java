package com.aioj.next.ai.domain.memory;

import com.aioj.next.ai.domain.AiCompletion;
import com.aioj.next.ai.domain.AiMemoryService;
import com.aioj.next.ai.domain.AiRetrievalService;
import com.aioj.next.ai.persistence.entity.AiMemoryCandidateEntity;
import com.aioj.next.ai.persistence.entity.AiMemoryClaimEntity;
import com.aioj.next.ai.persistence.entity.AiMemoryEvidenceEntity;
import com.aioj.next.ai.persistence.entity.AiMemoryVersionEntity;
import com.aioj.next.ai.persistence.entity.AiUserMemoryEntity;
import com.aioj.next.ai.persistence.mapper.AiMemoryCandidateMapper;
import com.aioj.next.ai.persistence.mapper.AiMemoryClaimMapper;
import com.aioj.next.ai.persistence.mapper.AiMemoryEvidenceMapper;
import com.aioj.next.ai.persistence.mapper.AiMemoryVersionMapper;
import com.aioj.next.ai.persistence.mapper.AiUserMemoryMapper;
import com.aioj.next.common.error.DomainException;
import com.aioj.next.common.error.ErrorCode;
import com.aioj.next.contract.ai.AiMemoryCandidateActionRequest;
import com.aioj.next.contract.ai.AiMemoryCandidateResponse;
import com.aioj.next.contract.ai.AiMemoryResponse;
import com.baomidou.mybatisplus.core.conditions.query.QueryWrapper;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

@Service
public class AiMemoryCandidateService {
    private static final ZoneId ZONE = ZoneId.systemDefault();
    private static final String STATUS_ACTIVE = "ACTIVE";
    private static final String STATUS_CANDIDATE = "CANDIDATE";
    private static final String STATUS_NEEDS_CONFIRMATION = "NEEDS_CONFIRMATION";
    private static final String STATUS_AWAITING_CLARIFICATION = "AWAITING_CLARIFICATION";
    private static final String STATUS_MERGE_QUEUED = AiMemoryMergeService.STATUS_MERGE_QUEUED;
    private static final String STATUS_REJECTED = "REJECTED";
    private static final String STATUS_RESOLVED = "RESOLVED";
    private static final String STATUS_SUPERSEDED = "SUPERSEDED";
    private static final String SOURCE_USER_CONFIRMED = "USER_CONFIRMED";
    private static final String CANDIDATE_KIND_WEAKNESS_RESOLUTION = "WEAKNESS_RESOLUTION";
    private static final Set<String> HARD_REJECT_FLAGS = Set.of(
            "empty_memory",
            "not_long_term",
            "problem_noise",
            "raw_sample",
            "code_noise",
            "temporary_preference",
            "problem_or_session_scoped",
            "privacy_sensitive",
            "hypothetical",
            "quoted_or_third_party"
    );

    private final AiMemoryCandidateMapper candidateMapper;
    private final AiMemoryEvidenceMapper evidenceMapper;
    private final AiMemoryClaimMapper claimMapper;
    private final AiMemoryVersionMapper versionMapper;
    private final AiUserMemoryMapper memoryMapper;
    private final AiRetrievalService retrievalService;
    private final AiMemoryMergeService mergeService;
    private final ObjectMapper objectMapper;

    public AiMemoryCandidateService(
            AiMemoryCandidateMapper candidateMapper,
            AiMemoryEvidenceMapper evidenceMapper,
            AiMemoryClaimMapper claimMapper,
            AiMemoryVersionMapper versionMapper,
            AiUserMemoryMapper memoryMapper,
            AiRetrievalService retrievalService,
            AiMemoryMergeService mergeService,
            ObjectMapper objectMapper
    ) {
        this.candidateMapper = candidateMapper;
        this.evidenceMapper = evidenceMapper;
        this.claimMapper = claimMapper;
        this.versionMapper = versionMapper;
        this.memoryMapper = memoryMapper;
        this.retrievalService = retrievalService;
        this.mergeService = mergeService;
        this.objectMapper = objectMapper;
    }

    public record ResolutionCandidateRequest(
            String plannerAction,
            Long targetMemoryId,
            Long targetClaimId,
            String memoryKey,
            String canonicalText,
            String evidenceText,
            String conversationId,
            Long messageId,
            double confidence,
            String reason
    ) {
    }

    public record ConfirmedMemoryCandidateRequest(
            String category,
            String memoryKey,
            String canonicalText,
            String valueJson,
            String scopeType,
            String scopeId,
            String evidenceType,
            String evidenceText,
            String reason,
            double confidence
    ) {
    }

    @Transactional
    public AiMemoryCandidateEntity recordExtraction(
            Long userId,
            String conversationId,
            Long sourceMessageId,
            AiCompletion.MemorySignal signal,
            MemoryQualityGate.MemoryCandidate candidate,
            MemoryQualityGate.GateResult gate,
            String evidenceText
    ) {
        LocalDateTime now = LocalDateTime.now();
        AiMemoryCandidateEntity entity = new AiMemoryCandidateEntity();
        boolean hardRejected = hardRejected(gate);
        entity.userId = userId;
        entity.category = gate.normalizedCategory();
        entity.memoryKey = gate.normalizedKey();
        entity.canonicalText = hardRejected ? rejectedCandidateText(gate) : normalize(candidate.canonicalText());
        entity.valueJson = hardRejected ? "{}" : candidate.valueJson();
        entity.scopeType = gate.scopeType();
        entity.scopeId = gate.scopeId();
        entity.evidenceType = normalize(signal.evidenceType()).toUpperCase(Locale.ROOT);
        entity.extractionConfidence = decimal(signal.confidence());
        entity.writeScore = decimal(gate.writeScore());
        entity.isLongTerm = candidate.longTerm();
        entity.isProblemSpecific = candidate.problemSpecific();
        entity.isHypothetical = candidate.hypothetical();
        entity.isQuoted = candidate.quoted();
        entity.needsConfirmation = gate.needsConfirmation();
        entity.qualityFlags = toJson(gate.qualityFlags());
        entity.ambiguityFlags = toJson(gate.ambiguityFlags());
        entity.sourceConversationId = conversationId;
        entity.sourceMessageId = sourceMessageId;
        entity.status = gate.status();
        entity.rejectedReason = gate.rejectedReason();
        entity.createdAt = now;
        entity.updatedAt = now;
        AiMemoryCandidateEntity existing = existingExtraction(entity);
        if (existing != null) {
            return existing;
        }
        candidateMapper.insert(entity);
        String safeEvidenceText = hardRejected ? entity.canonicalText : evidenceText;
        String safeReason = hardRejected ? entity.rejectedReason : signal.reason();
        insertEvidence(userId, null, entity.id, conversationId, sourceMessageId, entity.evidenceType,
                safeEvidenceText, signal.confidence(), safeReason, now);
        return entity;
    }

    private boolean hardRejected(MemoryQualityGate.GateResult gate) {
        if (gate == null || !STATUS_REJECTED.equals(gate.status())) {
            return false;
        }
        return gate.qualityFlags().stream().anyMatch(HARD_REJECT_FLAGS::contains)
                || gate.ambiguityFlags().stream().anyMatch(HARD_REJECT_FLAGS::contains);
    }

    private String rejectedCandidateText(MemoryQualityGate.GateResult gate) {
        String reason = normalize(gate.rejectedReason()).isBlank() ? "rejected" : normalize(gate.rejectedReason());
        return "[memory candidate rejected: " + truncate(reason, 96) + "]";
    }

    private AiMemoryCandidateEntity existingExtraction(AiMemoryCandidateEntity entity) {
        if (entity == null || entity.userId == null || entity.sourceMessageId == null) {
            return null;
        }
        QueryWrapper<AiMemoryCandidateEntity> query = new QueryWrapper<AiMemoryCandidateEntity>()
                .eq("user_id", entity.userId)
                .eq("source_message_id", entity.sourceMessageId)
                .eq("category", entity.category)
                .eq("memory_key", entity.memoryKey)
                .eq("canonical_text", entity.canonicalText)
                .eq("evidence_type", entity.evidenceType);
        if (entity.sourceConversationId == null) {
            query.isNull("source_conversation_id");
        } else {
            query.eq("source_conversation_id", entity.sourceConversationId);
        }
        if (entity.scopeType == null) {
            query.isNull("scope_type");
        } else {
            query.eq("scope_type", entity.scopeType);
        }
        if (entity.scopeId == null) {
            query.isNull("scope_id");
        } else {
            query.eq("scope_id", entity.scopeId);
        }
        return candidateMapper.selectOne(query.last("LIMIT 1"));
    }

    @Transactional
    public AiMemoryMergeService.MergeEnqueueResult enqueueConfirmedMemory(
            Long userId,
            ConfirmedMemoryCandidateRequest request
    ) {
        if (userId == null) {
            throw new DomainException(ErrorCode.VALIDATION_FAILED, "User id is required");
        }
        String canonicalText = truncate(normalize(request.canonicalText()), 4000);
        if (canonicalText.isBlank()) {
            throw new DomainException(ErrorCode.VALIDATION_FAILED, "Memory candidate content is required");
        }
        String category = normalize(request.category()).isBlank()
                ? "MEMORY"
                : normalize(request.category()).toUpperCase(Locale.ROOT);
        String memoryKey = normalizeKey(request.memoryKey());
        String scopeType = normalize(request.scopeType()).isBlank()
                ? "GLOBAL"
                : normalize(request.scopeType()).toUpperCase(Locale.ROOT);
        String scopeId = normalize(request.scopeId()).isBlank() ? null : normalize(request.scopeId());
        String evidenceType = normalize(request.evidenceType()).isBlank()
                ? SOURCE_USER_CONFIRMED
                : normalize(request.evidenceType()).toUpperCase(Locale.ROOT);

        AiMemoryCandidateEntity entity = existingConfirmedCandidate(userId, category, memoryKey, scopeType, scopeId, evidenceType);
        LocalDateTime now = LocalDateTime.now();
        if (entity == null) {
            entity = new AiMemoryCandidateEntity();
            entity.userId = userId;
            entity.category = category;
            entity.memoryKey = memoryKey;
            entity.scopeType = scopeType;
            entity.scopeId = scopeId;
            entity.evidenceType = evidenceType;
            entity.extractionConfidence = decimal(request.confidence());
            entity.writeScore = decimal(request.confidence());
            entity.isLongTerm = Boolean.TRUE;
            entity.isProblemSpecific = Boolean.FALSE;
            entity.isHypothetical = Boolean.FALSE;
            entity.isQuoted = Boolean.FALSE;
            entity.needsConfirmation = Boolean.FALSE;
            entity.qualityFlags = toJson(List.of("confirmed_signal"));
            entity.ambiguityFlags = toJson(List.of());
            entity.sourceConversationId = null;
            entity.sourceMessageId = null;
            // canonical_text, value_json, status and updated_at are NOT NULL in
            // ai_memory_candidates. Populate the final confirmed state before
            // the first insert; updating it afterwards is too late for MySQL.
            entity.canonicalText = canonicalText;
            entity.valueJson = normalize(request.valueJson()).isBlank() ? "{}" : request.valueJson();
            entity.status = STATUS_NEEDS_CONFIRMATION;
            entity.rejectedReason = truncate(normalize(request.reason()), 500);
            entity.createdAt = now;
            entity.updatedAt = now;
            candidateMapper.insert(entity);
            insertEvidence(userId, null, entity.id, null, null, evidenceType,
                    request.evidenceText(), request.confidence(), request.reason(), now);
        } else {
            entity.canonicalText = canonicalText;
            entity.valueJson = normalize(request.valueJson()).isBlank() ? "{}" : request.valueJson();
            entity.status = STATUS_NEEDS_CONFIRMATION;
            entity.rejectedReason = truncate(normalize(request.reason()), 500);
            entity.updatedAt = now;
            candidateMapper.updateById(entity);
        }
        return mergeService.enqueueCandidateMerge(
                userId,
                entity.id,
                new AiMemoryCandidateActionRequest(null, null, null, canonicalText, request.reason()),
                null,
                null,
                evidenceType,
                request.reason()
        );
    }

    private AiMemoryCandidateEntity existingConfirmedCandidate(
            Long userId,
            String category,
            String memoryKey,
            String scopeType,
            String scopeId,
            String evidenceType
    ) {
        QueryWrapper<AiMemoryCandidateEntity> query = new QueryWrapper<AiMemoryCandidateEntity>()
                .eq("user_id", userId)
                .eq("category", category)
                .eq("memory_key", memoryKey)
                .eq("scope_type", scopeType)
                .eq("evidence_type", evidenceType);
        if (scopeId == null) {
            query.isNull("scope_id");
        } else {
            query.eq("scope_id", scopeId);
        }
        return candidateMapper.selectOne(query.orderByDesc("created_at").last("LIMIT 1"));
    }

    @Transactional
    public AiMemoryCandidateEntity createPlannerResolutionCandidate(Long userId, ResolutionCandidateRequest request) {
        if (request == null || request.targetMemoryId() == null) {
            throw new DomainException(ErrorCode.VALIDATION_FAILED, "Resolution candidate target memory is required");
        }
        LocalDateTime now = LocalDateTime.now();
        String action = plannerAction(request.plannerAction());
        Map<String, Object> value = new LinkedHashMap<>();
        value.put("candidateKind", CANDIDATE_KIND_WEAKNESS_RESOLUTION);
        value.put("plannerAction", action);
        value.put("targetMemoryId", String.valueOf(request.targetMemoryId()));
        if (request.targetClaimId() != null) {
            value.put("targetClaimId", String.valueOf(request.targetClaimId()));
        }

        AiMemoryCandidateEntity entity = new AiMemoryCandidateEntity();
        entity.userId = userId;
        entity.category = "WEAKNESS";
        entity.memoryKey = normalizeKey(request.memoryKey());
        entity.canonicalText = truncate(normalize(request.canonicalText()), 4000);
        entity.valueJson = toJson(value);
        entity.scopeType = "GLOBAL";
        entity.scopeId = null;
        entity.evidenceType = action;
        entity.extractionConfidence = decimal(request.confidence());
        entity.writeScore = decimal(request.confidence());
        entity.isLongTerm = Boolean.TRUE;
        entity.isProblemSpecific = Boolean.FALSE;
        entity.isHypothetical = Boolean.FALSE;
        entity.isQuoted = Boolean.FALSE;
        entity.needsConfirmation = Boolean.TRUE;
        entity.qualityFlags = toJson(List.of("planner_resolution"));
        entity.ambiguityFlags = toJson(List.of());
        entity.sourceConversationId = request.conversationId();
        entity.sourceMessageId = request.messageId();
        entity.status = STATUS_NEEDS_CONFIRMATION;
        entity.rejectedReason = truncate(normalize(request.reason()), 500);
        entity.createdAt = now;
        entity.updatedAt = now;
        candidateMapper.insert(entity);
        insertEvidence(userId, null, entity.id, request.conversationId(), request.messageId(), action,
                request.evidenceText(), request.confidence(), request.reason(), now);
        return entity;
    }

    @Transactional
    public AiMemoryResponse applyPlannerStatus(
            Long userId,
            Long memoryId,
            Long claimId,
            String plannerAction,
            String evidenceText,
            String reason,
            String conversationId,
            Long messageId
    ) {
        return applyPlannerStatus(userId, memoryId, claimId, statusForPlannerAction(plannerAction),
                evidenceText, reason, conversationId, messageId, null);
    }

    public List<AiMemoryCandidateResponse> list(Long userId, String status) {
        QueryWrapper<AiMemoryCandidateEntity> query = new QueryWrapper<AiMemoryCandidateEntity>()
                .eq("user_id", userId)
                .orderByDesc("created_at")
                .last("LIMIT 200");
        String normalizedStatus = normalize(status).toUpperCase(Locale.ROOT);
        if (!normalizedStatus.isBlank()) {
            query.eq("status", normalizedStatus);
        } else {
            query.in("status", List.of(STATUS_CANDIDATE, STATUS_NEEDS_CONFIRMATION, STATUS_AWAITING_CLARIFICATION, STATUS_MERGE_QUEUED));
        }
        return candidateMapper.selectList(query).stream().map(this::toResponse).toList();
    }

    public Optional<AiMemoryCandidateEntity> nextClarificationCandidate(Long userId) {
        List<AiMemoryCandidateEntity> candidates = candidateMapper.selectList(new QueryWrapper<AiMemoryCandidateEntity>()
                .eq("user_id", userId)
                .eq("status", STATUS_NEEDS_CONFIRMATION)
                .orderByDesc("write_score")
                .orderByDesc("created_at")
                .last("LIMIT 20"));
        return candidates.stream()
                .filter(candidate -> !hasClarificationMetadata(candidate))
                .findFirst();
    }

    public Optional<AiMemoryCandidateEntity> findByClarificationRequest(Long userId, String requestId) {
        String normalized = normalize(requestId);
        if (normalized.isBlank()) {
            return Optional.empty();
        }
        List<AiMemoryCandidateEntity> candidates = candidateMapper.selectList(new QueryWrapper<AiMemoryCandidateEntity>()
                .eq("user_id", userId)
                .in("status", List.of(STATUS_AWAITING_CLARIFICATION, STATUS_NEEDS_CONFIRMATION))
                .like("value_json", normalized)
                .orderByDesc("updated_at")
                .last("LIMIT 20"));
        return candidates.stream()
                .filter(candidate -> normalized.equals(clarificationRequestId(candidate)))
                .findFirst();
    }

    @Transactional
    public AiMemoryCandidateEntity markAwaitingClarification(
            Long userId,
            Long candidateId,
            String requestId,
            String conversationId,
            Long assistantMessageId
    ) {
        AiMemoryCandidateEntity candidate = requireOwned(userId, candidateId);
        if (!STATUS_NEEDS_CONFIRMATION.equals(candidate.status)) {
            return candidate;
        }
        LocalDateTime now = LocalDateTime.now();
        candidate.valueJson = withClarificationMetadata(candidate.valueJson, Map.of(
                "requestId", normalize(requestId),
                "askedAt", now.toString(),
                "conversationId", normalize(conversationId),
                "assistantMessageId", assistantMessageId == null ? "" : String.valueOf(assistantMessageId),
                "attemptCount", 1
        ));
        candidate.status = STATUS_AWAITING_CLARIFICATION;
        candidate.updatedAt = now;
        candidateMapper.updateById(candidate);
        return candidate;
    }

    @Transactional
    public AiMemoryCandidateEntity returnToNeedsConfirmation(
            Long userId,
            Long candidateId,
            String answerStatus,
            String reason
    ) {
        AiMemoryCandidateEntity candidate = requireOwned(userId, candidateId);
        LocalDateTime now = LocalDateTime.now();
        candidate.valueJson = withClarificationMetadata(candidate.valueJson, Map.of(
                "answerStatus", normalize(answerStatus),
                "answeredAt", now.toString()
        ));
        candidate.status = STATUS_NEEDS_CONFIRMATION;
        candidate.rejectedReason = normalize(reason).isBlank() ? candidate.rejectedReason : truncate(normalize(reason), 500);
        candidate.updatedAt = now;
        candidateMapper.updateById(candidate);
        return candidate;
    }

    @Transactional
    public AiMemoryResponse accept(Long userId, Long candidateId, AiMemoryCandidateActionRequest request) {
        AiMemoryCandidateEntity candidate = requireOwned(userId, candidateId);
        AiMemoryResponse nonReviewableResponse = responseForNonReviewableAccept(candidate);
        if (nonReviewableResponse != null) {
            return nonReviewableResponse;
        }
        Map<String, Object> plannerMetadata = readObjectMap(candidate.valueJson);
        if (CANDIDATE_KIND_WEAKNESS_RESOLUTION.equals(stringValue(plannerMetadata.get("candidateKind")))) {
            return acceptPlannerResolutionCandidate(userId, candidate, plannerMetadata, request);
        }
        String reason = request == null ? "candidate_accepted" : request.reason();
        String mergeAction = "auto_memory_extraction".equals(normalize(reason)) ? "AUTO_MEMORY_EXTRACTION" : "ACCEPT";
        AiMemoryMergeService.MergeEnqueueResult result = mergeService.enqueueCandidateMerge(
                userId,
                candidateId,
                request,
                null,
                null,
                mergeAction,
                reason
        );
        return mergeService.pendingResponse(result.candidate(), result.targetMemory());
    }

    @Transactional
    public AiMemoryCandidateResponse reject(Long userId, Long candidateId, String reason) {
        AiMemoryCandidateEntity candidate = requireOwned(userId, candidateId);
        ensureCandidateReviewable(candidate);
        candidate.status = STATUS_REJECTED;
        candidate.rejectedReason = normalize(reason).isBlank() ? "user_rejected" : normalize(reason);
        candidate.updatedAt = LocalDateTime.now();
        candidateMapper.updateById(candidate);
        return toResponse(candidate);
    }

    private AiMemoryResponse acceptPlannerResolutionCandidate(
            Long userId,
            AiMemoryCandidateEntity candidate,
            Map<String, Object> metadata,
            AiMemoryCandidateActionRequest request
    ) {
        String plannerAction = stringValue(metadata.get("plannerAction"));
        Long targetMemoryId = readLong(metadata.get("targetMemoryId"));
        Long targetClaimId = readLong(metadata.get("targetClaimId"));
        String reason = request == null || normalize(request.reason()).isBlank()
                ? "user_accepted_planner_resolution"
                : normalize(request.reason());
        AiMemoryMergeService.MergeEnqueueResult result = mergeService.enqueueCandidateMerge(
                userId,
                candidate.id,
                request,
                targetMemoryId,
                targetClaimId,
                plannerAction,
                reason
        );
        return mergeService.pendingResponse(result.candidate(), result.targetMemory());
    }

    private AiMemoryResponse responseForNonReviewableAccept(AiMemoryCandidateEntity candidate) {
        if (candidate == null || isCandidateReviewable(candidate.status)) {
            return null;
        }
        if (STATUS_MERGE_QUEUED.equals(normalizeStatus(candidate.status))) {
            return mergeService.pendingResponse(candidate, null);
        }
        throw new DomainException(ErrorCode.VALIDATION_FAILED, "AI memory candidate has already been processed");
    }

    private void ensureCandidateReviewable(AiMemoryCandidateEntity candidate) {
        if (candidate != null && isCandidateReviewable(candidate.status)) {
            return;
        }
        if (candidate != null && STATUS_MERGE_QUEUED.equals(normalizeStatus(candidate.status))) {
            throw new DomainException(ErrorCode.VALIDATION_FAILED, "AI memory candidate is already being merged");
        }
        throw new DomainException(ErrorCode.VALIDATION_FAILED, "AI memory candidate has already been processed");
    }

    private boolean isCandidateReviewable(String status) {
        return Set.of(STATUS_CANDIDATE, STATUS_NEEDS_CONFIRMATION, STATUS_AWAITING_CLARIFICATION)
                .contains(normalizeStatus(status));
    }

    private String normalizeStatus(String status) {
        return normalize(status).toUpperCase(Locale.ROOT);
    }

    private AiMemoryResponse applyPlannerStatus(
            Long userId,
            Long memoryId,
            Long claimId,
            String targetStatus,
            String evidenceText,
            String reason,
            String conversationId,
            Long messageId,
            Long candidateId
    ) {
        LocalDateTime now = LocalDateTime.now();
        AiUserMemoryEntity memory = memoryId == null ? null : memoryMapper.selectById(memoryId);
        if (memoryId != null && memory == null) {
            throw new DomainException(ErrorCode.NOT_FOUND, "Target AI memory not found");
        }
        if (memory != null && !userId.equals(memory.getUserId())) {
            throw new DomainException(ErrorCode.FORBIDDEN, "Target AI memory belongs to another user");
        }
        AiMemoryClaimEntity claim = claimId == null ? null : claimMapper.selectById(claimId);
        if (claimId != null && claim == null) {
            throw new DomainException(ErrorCode.NOT_FOUND, "Target AI memory claim not found");
        }
        if (claim != null && !userId.equals(claim.userId)) {
            throw new DomainException(ErrorCode.FORBIDDEN, "Target AI memory claim belongs to another user");
        }
        if (claim == null && memory != null) {
            claim = claimMapper.selectOne(new QueryWrapper<AiMemoryClaimEntity>()
                    .eq("user_id", userId)
                    .eq("legacy_memory_id", memory.getId())
                    .last("LIMIT 1"));
        }
        if (memory == null && claim != null && claim.legacyMemoryId != null) {
            memory = memoryMapper.selectById(claim.legacyMemoryId);
            if (memory != null && !userId.equals(memory.getUserId())) {
                throw new DomainException(ErrorCode.FORBIDDEN, "Target AI memory belongs to another user");
            }
        }
        if (memory == null) {
            throw new DomainException(ErrorCode.NOT_FOUND, "Target AI memory not found");
        }

        retrievalService.deleteOwner(userId, "memory", String.valueOf(memory.getId()));
        memory.setStatus(targetStatus);
        memory.setUpdatedAt(now);
        memoryMapper.updateById(memory);

        if (claim != null) {
            claim.status = targetStatus;
            claim.version = Math.max(1, claim.version == null ? 1 : claim.version + 1);
            claim.updatedAt = now;
            claimMapper.updateById(claim);
            insertVersion(userId, claim, candidateId, reason, now);
            insertEvidence(userId, claim.id, candidateId, conversationId, messageId,
                    "USER_ACCEPTED_" + targetStatus, evidenceText, 1.0, reason, now);
        }
        return toMemoryResponse(memory);
    }

    @Transactional
    public void syncClaimFromMemory(
            AiUserMemoryEntity memory,
            AiCompletion.MemorySignal signal,
            MemoryQualityGate.GateResult gate,
            Long candidateId
    ) {
        if (memory == null || memory.getId() == null || !STATUS_ACTIVE.equals(memory.getStatus())) {
            return;
        }
        AiMemoryCandidateEntity candidate = candidateId == null ? null : candidateMapper.selectById(candidateId);
        AiMemoryClaimEntity claim = syncClaimFromMemory(memory, candidate, "auto_memory_extraction");
        insertEvidence(memory.getUserId(), claim.id, candidateId, memory.getSourceConversationId(), memory.getSourceMessageId(),
                signal == null ? "UNKNOWN" : normalize(signal.evidenceType()).toUpperCase(Locale.ROOT),
                memory.getContent(), memory.getConfidence() == null ? 0 : memory.getConfidence().doubleValue(),
                signal == null ? "" : signal.reason(), LocalDateTime.now());
    }

    @Transactional
    public void syncManualClaim(AiUserMemoryEntity memory, String reason) {
        if (memory == null || memory.getId() == null || !STATUS_ACTIVE.equals(memory.getStatus())) {
            return;
        }
        AiMemoryClaimEntity claim = syncClaimFromMemory(memory, null, reason);
        insertEvidence(memory.getUserId(), claim.id, null, memory.getSourceConversationId(), memory.getSourceMessageId(),
                "USER_MANUAL", memory.getContent(), memory.getConfidence() == null ? 1 : memory.getConfidence().doubleValue(),
                reason, LocalDateTime.now());
    }

    @Transactional
    public void markClaimStatus(AiUserMemoryEntity memory, String status, String reason) {
        if (memory == null || memory.getId() == null) {
            return;
        }
        AiMemoryClaimEntity claim = claimMapper.selectOne(new QueryWrapper<AiMemoryClaimEntity>()
                .eq("user_id", memory.getUserId())
                .eq("legacy_memory_id", memory.getId())
                .last("LIMIT 1"));
        if (claim == null) {
            return;
        }
        claim.status = normalize(status).isBlank() ? "DISABLED" : normalize(status).toUpperCase(Locale.ROOT);
        // V3 P2-7: user distrust via manual disable lowers claim confidence (×0.85, mirroring
        // merge WEAKEN adjustedConfidence semantics) so the AI pays less attention to it.
        // DELETED and other transitions keep the stored confidence untouched.
        if ("DISABLED".equals(claim.status) && "manual_disable".equals(normalize(reason)) && claim.confidence != null) {
            claim.confidence = claim.confidence.multiply(new BigDecimal("0.85")).setScale(4, RoundingMode.HALF_UP);
        }
        claim.version = Math.max(1, claim.version == null ? 1 : claim.version + 1);
        claim.updatedAt = LocalDateTime.now();
        claimMapper.updateById(claim);
        insertVersion(memory.getUserId(), claim, null, reason, claim.updatedAt);
    }

    private AiMemoryClaimEntity syncClaimFromMemory(AiUserMemoryEntity memory, AiMemoryCandidateEntity candidate, String reason) {
        LocalDateTime now = LocalDateTime.now();
        String category = candidate == null ? v2Category(memory.getCategory(), memory.getMemoryType()) : candidate.category;
        String key = candidate == null ? normalizeKey(memory.getMemoryType()) : candidate.memoryKey;
        String scopeType = candidate == null ? "GLOBAL" : candidate.scopeType;
        String scopeId = candidate == null ? null : candidate.scopeId;
        QueryWrapper<AiMemoryClaimEntity> query = new QueryWrapper<AiMemoryClaimEntity>()
                .eq("user_id", memory.getUserId())
                .eq("scope_type", scopeType)
                .eq("category", category)
                .eq("memory_key", key);
        if (scopeId == null) {
            query.isNull("scope_id");
        } else {
            query.eq("scope_id", scopeId);
        }
        AiMemoryClaimEntity claim = claimMapper.selectOne(query.last("LIMIT 1"));
        if (claim == null) {
            claim = new AiMemoryClaimEntity();
            claim.userId = memory.getUserId();
            claim.legacyMemoryId = memory.getId();
            claim.scopeType = scopeType;
            claim.scopeId = scopeId;
            claim.category = category;
            claim.memoryKey = key;
            claim.valueJson = candidate == null ? null : candidate.valueJson;
            claim.canonicalText = memory.getContent();
            claim.confidence = normalizeConfidence(memory.getConfidence());
            claim.stabilityScore = new BigDecimal("0.7500");
            claim.supportCount = 1;
            claim.contradictionCount = 0;
            claim.sourceMode = memory.getSource() == null ? "USER_MANUAL" : memory.getSource();
            claim.status = STATUS_ACTIVE;
            claim.sensitivityLevel = "LOW";
            claim.ambiguityLevel = "LOW";
            claim.firstSeenAt = now;
            claim.lastSeenAt = now;
            claim.pinned = Boolean.FALSE;
            claim.version = 1;
            claim.createdAt = now;
            claim.updatedAt = now;
            claimMapper.insert(claim);
            insertVersion(memory.getUserId(), claim, candidate == null ? null : candidate.id, reason, now);
            return claim;
        }
        // V3 P2-7: reactivating a user-DISABLED claim here means an explicit user action
        // (manual enable/create/update/import — all callers are user-driven) cleared the
        // distrust; apply a small confidence boost (old value, null treated as 0.5, cap 1.0).
        boolean distrustRevival = "DISABLED".equals(normalize(claim.status).toUpperCase(Locale.ROOT));
        BigDecimal previousConfidence = claim.confidence;
        claim.legacyMemoryId = memory.getId();
        claim.canonicalText = memory.getContent();
        claim.confidence = claim.confidence == null ? normalizeConfidence(memory.getConfidence()) : claim.confidence.max(normalizeConfidence(memory.getConfidence())).min(BigDecimal.ONE);
        claim.supportCount = Math.max(1, claim.supportCount == null ? 1 : claim.supportCount + 1);
        claim.lastSeenAt = now;
        claim.status = STATUS_ACTIVE;
        if (distrustRevival) {
            double base = previousConfidence == null ? 0.5 : previousConfidence.doubleValue();
            claim.confidence = BigDecimal.valueOf(Math.min(1.0, base + 0.1)).setScale(4, RoundingMode.HALF_UP);
        }
        claim.version = Math.max(1, claim.version == null ? 1 : claim.version + 1);
        claim.updatedAt = now;
        claimMapper.updateById(claim);
        insertVersion(memory.getUserId(), claim, candidate == null ? null : candidate.id, reason, now);
        return claim;
    }

    private void insertVersion(Long userId, AiMemoryClaimEntity claim, Long candidateId, String reason, LocalDateTime now) {
        AiMemoryVersionEntity version = new AiMemoryVersionEntity();
        version.userId = userId;
        version.claimId = claim.id;
        version.version = claim.version;
        version.canonicalText = claim.canonicalText;
        version.valueJson = claim.valueJson;
        version.status = claim.status;
        version.changeReason = truncate(normalize(reason), 500);
        version.sourceCandidateId = candidateId;
        version.createdAt = now;
        versionMapper.insert(version);
    }

    private void insertEvidence(
            Long userId,
            Long claimId,
            Long candidateId,
            String conversationId,
            Long messageId,
            String evidenceType,
            String evidenceText,
            double confidence,
            String reason,
            LocalDateTime now
    ) {
        AiMemoryEvidenceEntity evidence = new AiMemoryEvidenceEntity();
        evidence.userId = userId;
        evidence.claimId = claimId;
        evidence.candidateId = candidateId;
        evidence.conversationId = conversationId;
        evidence.messageId = messageId;
        evidence.evidenceType = normalize(evidenceType).isBlank() ? "UNKNOWN" : normalize(evidenceType);
        evidence.evidenceText = truncate(normalize(evidenceText), 4000);
        evidence.confidence = decimal(confidence);
        evidence.reason = truncate(normalize(reason), 500);
        evidence.createdAt = now;
        evidenceMapper.insert(evidence);
    }

    private AiMemoryCandidateEntity requireOwned(Long userId, Long id) {
        AiMemoryCandidateEntity candidate = candidateMapper.selectById(id);
        if (candidate == null) {
            throw new DomainException(ErrorCode.NOT_FOUND, "AI memory candidate not found");
        }
        if (!userId.equals(candidate.userId)) {
            throw new DomainException(ErrorCode.FORBIDDEN, "AI memory candidate belongs to another user");
        }
        return candidate;
    }

    private void index(AiUserMemoryEntity memory) {
        if (AiMemoryService.isRecallableMemory(memory.getMemoryType(), memory.getContent())) {
            retrievalService.deleteOwner(memory.getUserId(), "memory", String.valueOf(memory.getId()));
            retrievalService.indexChunk(memory.getUserId(), "memory", String.valueOf(memory.getId()),
                    "长期记忆：" + normalize(memory.getMemoryType()) + " - " + normalize(memory.getContent()));
        }
    }

    private AiMemoryCandidateResponse toResponse(AiMemoryCandidateEntity item) {
        Map<String, Object> plannerMetadata = readObjectMap(item.valueJson);
        return new AiMemoryCandidateResponse(
                item.id,
                item.category,
                item.memoryKey,
                item.canonicalText,
                item.scopeType,
                item.scopeId,
                item.evidenceType,
                item.extractionConfidence,
                item.writeScore,
                Boolean.TRUE.equals(item.isLongTerm),
                Boolean.TRUE.equals(item.isProblemSpecific),
                Boolean.TRUE.equals(item.isHypothetical),
                Boolean.TRUE.equals(item.isQuoted),
                Boolean.TRUE.equals(item.needsConfirmation),
                readStringList(item.qualityFlags),
                readStringList(item.ambiguityFlags),
                item.status,
                item.rejectedReason,
                item.sourceConversationId,
                item.sourceMessageId,
                item.createdAt == null ? null : item.createdAt.atZone(ZONE).toInstant(),
                item.updatedAt == null ? null : item.updatedAt.atZone(ZONE).toInstant(),
                stringValue(plannerMetadata.get("candidateKind")),
                stringValue(plannerMetadata.get("plannerAction")),
                readLong(plannerMetadata.get("targetMemoryId")),
                readLong(plannerMetadata.get("targetClaimId"))
        );
    }

    private AiMemoryResponse toMemoryResponse(AiUserMemoryEntity memory) {
        return new AiMemoryResponse(
                memory.getId(),
                memory.getCategory(),
                memory.getTitle(),
                memory.getMemoryType(),
                memory.getContent(),
                memory.getConfidence(),
                memory.getSource(),
                memory.getStatus(),
                memory.getCreatedAt() == null ? null : memory.getCreatedAt().atZone(ZONE).toInstant(),
                memory.getUpdatedAt() == null ? null : memory.getUpdatedAt().atZone(ZONE).toInstant(),
                memory.getLastUsedAt() == null ? null : memory.getLastUsedAt().atZone(ZONE).toInstant()
        );
    }

    private List<String> readStringList(String json) {
        if (json == null || json.isBlank()) {
            return List.of();
        }
        try {
            return objectMapper.readValue(json, objectMapper.getTypeFactory().constructCollectionType(List.class, String.class));
        } catch (Exception ignored) {
            return List.of();
        }
    }

    @SuppressWarnings("unchecked")
    private Map<String, Object> readObjectMap(String json) {
        if (json == null || json.isBlank()) {
            return Map.of();
        }
        try {
            Object value = objectMapper.readValue(json, Map.class);
            return value instanceof Map<?, ?> map ? (Map<String, Object>) map : Map.of();
        } catch (Exception ignored) {
            return Map.of();
        }
    }

    @SuppressWarnings("unchecked")
    private boolean hasClarificationMetadata(AiMemoryCandidateEntity candidate) {
        return !mapValue(readObjectMap(candidate == null ? null : candidate.valueJson).get("clarification")).isEmpty();
    }

    private String clarificationRequestId(AiMemoryCandidateEntity candidate) {
        return stringValue(mapValue(readObjectMap(candidate == null ? null : candidate.valueJson).get("clarification")).get("requestId"));
    }

    @SuppressWarnings("unchecked")
    private Map<String, Object> mapValue(Object value) {
        if (value instanceof Map<?, ?> map) {
            Map<String, Object> copy = new LinkedHashMap<>();
            for (Map.Entry<?, ?> entry : map.entrySet()) {
                copy.put(String.valueOf(entry.getKey()), entry.getValue());
            }
            return copy;
        }
        return Map.of();
    }

    private String withClarificationMetadata(String json, Map<String, Object> updates) {
        Map<String, Object> value = new LinkedHashMap<>(readObjectMap(json));
        Map<String, Object> clarification = new LinkedHashMap<>(mapValue(value.get("clarification")));
        clarification.putAll(updates);
        value.put("clarification", clarification);
        return toJson(value);
    }

    private String toJson(Object value) {
        try {
            return objectMapper.writeValueAsString(value);
        } catch (Exception ignored) {
            return "[]";
        }
    }

    private String plannerAction(String value) {
        String normalized = normalize(value).toUpperCase(Locale.ROOT);
        return "SUPERSEDE".equals(normalized) ? "SUPERSEDE" : "RESOLVE";
    }

    private String statusForPlannerAction(String value) {
        return "SUPERSEDE".equals(plannerAction(value)) ? STATUS_SUPERSEDED : STATUS_RESOLVED;
    }

    private String stringValue(Object value) {
        return value == null ? null : normalize(String.valueOf(value));
    }

    private Long readLong(Object value) {
        if (value == null) {
            return null;
        }
        try {
            String normalized = normalize(String.valueOf(value));
            return normalized.isBlank() ? null : Long.parseLong(normalized);
        } catch (NumberFormatException ignored) {
            return null;
        }
    }

    private String legacyCategory(String requested, String v2Category) {
        String normalized = normalize(requested).toLowerCase(Locale.ROOT);
        if (AiMemoryService.MEMORY_CATEGORIES.contains(normalized)) {
            return normalized;
        }
        return switch (normalize(v2Category).toUpperCase(Locale.ROOT)) {
            case "RULE" -> "rule";
            case "PREFERENCE" -> "preference";
            case "HABIT" -> "habit";
            case "WEAKNESS" -> "weakness";
            default -> "memory";
        };
    }

    private String legacyMemoryType(String requested, String v2Category, String key) {
        String normalized = normalize(requested).toLowerCase(Locale.ROOT);
        if (!normalized.isBlank()) {
            return truncate(normalized, 48);
        }
        return switch (normalize(v2Category).toUpperCase(Locale.ROOT)) {
            case "RULE" -> "rule";
            case "PREFERENCE" -> "guidance_preference";
            case "HABIT" -> "habit";
            case "WEAKNESS" -> "weakness";
            case "PROFILE" -> "name_preference";
            case "GOAL" -> "learning_direction";
            default -> truncate(normalizeKey(key), 48);
        };
    }

    private String v2Category(String legacyCategory, String memoryType) {
        return switch (normalize(memoryType).toLowerCase(Locale.ROOT)) {
            case "rule" -> "RULE";
            case "preferred_language", "guidance_preference", "pace_preference", "teaching_style" -> "PREFERENCE";
            case "weakness" -> "WEAKNESS";
            case "code_style", "debugging_preference", "habit" -> "HABIT";
            case "name_preference", "skill_level" -> "PROFILE";
            case "learning_direction" -> "GOAL";
            default -> switch (normalize(legacyCategory).toLowerCase(Locale.ROOT)) {
                case "rule" -> "RULE";
                case "preference", "teaching_style" -> "PREFERENCE";
                case "habit" -> "HABIT";
                case "weakness" -> "WEAKNESS";
                default -> "MANUAL_NOTE";
            };
        };
    }

    private String titleFor(String category, String content) {
        String prefix = switch (normalize(category).toUpperCase(Locale.ROOT)) {
            case "RULE" -> "规则";
            case "PREFERENCE" -> "偏好";
            case "HABIT" -> "习惯";
            case "WEAKNESS" -> "薄弱点";
            case "PROFILE" -> "画像";
            case "GOAL" -> "学习目标";
            default -> "记忆";
        };
        return truncate(prefix + "：" + content.replaceAll("\\s+", " "), 160);
    }

    private String normalizeKey(String value) {
        String normalized = normalize(value).toLowerCase(Locale.ROOT)
                .replaceAll("[^a-z0-9_\\u4e00-\\u9fa5]+", "_")
                .replaceAll("_+", "_")
                .replaceAll("^_|_$", "");
        return normalized.isBlank() ? "manual_note" : truncate(normalized, 96);
    }

    private BigDecimal normalizeConfidence(BigDecimal value) {
        return value == null ? BigDecimal.ONE : value.max(BigDecimal.ZERO).min(BigDecimal.ONE).setScale(4, RoundingMode.HALF_UP);
    }

    private BigDecimal decimal(double value) {
        return BigDecimal.valueOf(Math.min(1, Math.max(0, value))).setScale(4, RoundingMode.HALF_UP);
    }

    private String normalize(String value) {
        return value == null ? "" : value.replace("\r\n", "\n").replace('\r', '\n').trim();
    }

    private String truncate(String value, int max) {
        String normalized = normalize(value);
        return normalized.length() <= max ? normalized : normalized.substring(0, max);
    }
}
