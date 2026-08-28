package com.aioj.next.ai.domain.memory;

import com.aioj.next.ai.domain.AiMemoryService;
import com.aioj.next.ai.domain.OperationAuditWriter;
import com.aioj.next.ai.persistence.entity.AiLearningProfileEntity;
import com.aioj.next.ai.persistence.entity.AiMemoryCandidateEntity;
import com.aioj.next.ai.persistence.entity.AiMemoryClaimEntity;
import com.aioj.next.ai.persistence.entity.AiMemoryEvidenceEntity;
import com.aioj.next.ai.persistence.entity.AiUserMemoryEntity;
import com.aioj.next.ai.persistence.mapper.AiLearningProfileMapper;
import com.aioj.next.ai.persistence.mapper.AiMemoryCandidateMapper;
import com.aioj.next.ai.persistence.mapper.AiMemoryClaimMapper;
import com.aioj.next.ai.persistence.mapper.AiMemoryEvidenceMapper;
import com.aioj.next.ai.persistence.mapper.AiUserMemoryMapper;
import com.aioj.next.common.api.PageResponse;
import com.aioj.next.common.error.DomainException;
import com.aioj.next.common.error.ErrorCode;
import com.aioj.next.contract.ai.AiMemoryCandidateActionRequest;
import com.aioj.next.contract.ai.AiMemoryReviewActionRequest;
import com.aioj.next.contract.ai.AiMemoryReviewDetailResponse;
import com.aioj.next.contract.ai.AiMemoryReviewEvidenceResponse;
import com.aioj.next.contract.ai.AiMemoryReviewListItemResponse;
import com.aioj.next.contract.ai.AiMemoryReviewRelatedMemoryResponse;
import com.aioj.next.contract.ai.AiMemoryReviewRelatedProfileResponse;
import com.aioj.next.contract.ai.AiMemoryUpsertRequest;
import com.baomidou.mybatisplus.core.conditions.query.QueryWrapper;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

@Service
public class AiMemoryReviewService {
    private static final ZoneId ZONE = ZoneId.systemDefault();
    private static final List<String> DEFAULT_PENDING_STATUSES = List.of(
            "NEEDS_CONFIRMATION",
            "CANDIDATE",
            "AWAITING_CLARIFICATION",
            AiMemoryMergeService.STATUS_MERGE_QUEUED
    );
    private static final List<String> DEFAULT_ACTIONS = List.of(
            "APPROVE",
            "REJECT",
            "EDIT_ACCEPT",
            "MERGE",
            "REQUEST_USER_CONFIRMATION"
    );

    private final AiMemoryCandidateMapper candidateMapper;
    private final AiMemoryEvidenceMapper evidenceMapper;
    private final AiUserMemoryMapper memoryMapper;
    private final AiMemoryClaimMapper claimMapper;
    private final AiLearningProfileMapper profileMapper;
    private final AiMemoryCandidateService candidateService;
    private final AiMemoryService memoryService;
    private final AiMemoryMergeService mergeService;
    private final AiMemoryEventPayloadSanitizer sanitizer;
    private final OperationAuditWriter auditWriter;
    private final ObjectMapper objectMapper;

    public AiMemoryReviewService(
            AiMemoryCandidateMapper candidateMapper,
            AiMemoryEvidenceMapper evidenceMapper,
            AiUserMemoryMapper memoryMapper,
            AiMemoryClaimMapper claimMapper,
            AiLearningProfileMapper profileMapper,
            AiMemoryCandidateService candidateService,
            AiMemoryService memoryService,
            AiMemoryMergeService mergeService,
            AiMemoryEventPayloadSanitizer sanitizer,
            OperationAuditWriter auditWriter,
            ObjectMapper objectMapper
    ) {
        this.candidateMapper = candidateMapper;
        this.evidenceMapper = evidenceMapper;
        this.memoryMapper = memoryMapper;
        this.claimMapper = claimMapper;
        this.profileMapper = profileMapper;
        this.candidateService = candidateService;
        this.memoryService = memoryService;
        this.mergeService = mergeService;
        this.sanitizer = sanitizer;
        this.auditWriter = auditWriter;
        this.objectMapper = objectMapper;
    }

    public PageResponse<AiMemoryReviewListItemResponse> list(
            long page,
            long pageSize,
            String status,
            String category,
            Long userId,
            String flag,
            String keyword
    ) {
        Page<AiMemoryCandidateEntity> queryPage = new Page<>(normalizePage(page), normalizePageSize(pageSize));
        QueryWrapper<AiMemoryCandidateEntity> query = new QueryWrapper<>();
        String normalizedStatus = normalize(status).toUpperCase(Locale.ROOT);
        if (normalizedStatus.isBlank()) {
            query.in("status", DEFAULT_PENDING_STATUSES);
        } else {
            query.eq("status", normalizedStatus);
        }
        String normalizedCategory = normalize(category).toUpperCase(Locale.ROOT);
        if (!normalizedCategory.isBlank()) {
            query.eq("category", normalizedCategory);
        }
        if (userId != null) {
            query.eq("user_id", userId);
        }
        String normalizedFlag = normalize(flag);
        if (!normalizedFlag.isBlank()) {
            query.and(wrapper -> wrapper
                    .like("quality_flags", normalizedFlag)
                    .or()
                    .like("ambiguity_flags", normalizedFlag));
        }
        String normalizedKeyword = normalize(keyword);
        if (!normalizedKeyword.isBlank()) {
            query.and(wrapper -> wrapper
                    .like("memory_key", normalizedKeyword)
                    .or()
                    .like("canonical_text", normalizedKeyword)
                    .or()
                    .like("rejected_reason", normalizedKeyword));
        }
        query.orderByDesc("updated_at").orderByDesc("created_at");
        Page<AiMemoryCandidateEntity> result = candidateMapper.selectPage(queryPage, query);
        return new PageResponse<>(
                result.getRecords().stream().map(this::toListItem).toList(),
                result.getTotal(),
                result.getCurrent(),
                result.getSize()
        );
    }

    public AiMemoryReviewDetailResponse detail(Long candidateId) {
        AiMemoryCandidateEntity candidate = requireCandidate(candidateId);
        return detail(candidate);
    }

    public AiMemoryReviewDetailResponse detailForUser(Long userId, Long candidateId) {
        AiMemoryCandidateEntity candidate = requireCandidate(candidateId);
        if (!userId.equals(candidate.userId)) {
            throw new DomainException(ErrorCode.NOT_FOUND, "AI memory candidate not found");
        }
        return detail(candidate);
    }

    @Transactional
    public AiMemoryReviewDetailResponse action(Long actorUserId, Long candidateId, AiMemoryReviewActionRequest request) {
        AiMemoryCandidateEntity candidate = requireCandidate(candidateId);
        String action = normalize(request == null ? null : request.action()).toUpperCase(Locale.ROOT);
        if (action.isBlank()) {
            throw new DomainException(ErrorCode.VALIDATION_FAILED, "Review action is required");
        }
        switch (action) {
            case "APPROVE" -> approve(candidate, request);
            case "REJECT" -> reject(candidate, request);
            case "EDIT_ACCEPT" -> editAccept(candidate, request);
            case "MERGE" -> merge(candidate, request);
            case "REQUEST_USER_CONFIRMATION" -> requestUserConfirmation(candidate, request);
            default -> throw new DomainException(ErrorCode.VALIDATION_FAILED, "Unsupported memory review action");
        }
        AiMemoryCandidateEntity updated = requireCandidate(candidateId);
        audit(actorUserId, updated, action, request);
        return detail(updated);
    }

    private void approve(AiMemoryCandidateEntity candidate, AiMemoryReviewActionRequest request) {
        mergeService.enqueueCandidateMerge(
                candidate.userId,
                candidate.id,
                toCandidateAction(request, candidate.canonicalText),
                null,
                null,
                "ADMIN_APPROVE",
                request == null ? "admin_approved" : request.reason()
        );
    }

    private void reject(AiMemoryCandidateEntity candidate, AiMemoryReviewActionRequest request) {
        candidateService.reject(candidate.userId, candidate.id, firstNonBlank(request == null ? null : request.reason(), "admin_rejected"));
    }

    private void editAccept(AiMemoryCandidateEntity candidate, AiMemoryReviewActionRequest request) {
        String edited = safeText(request == null ? null : request.canonicalText());
        if (edited.isBlank() || AiMemoryEventPayloadSanitizer.OMITTED.equals(edited)) {
            throw new DomainException(ErrorCode.VALIDATION_FAILED, "Edited memory content is not safe to accept");
        }
        mergeService.enqueueCandidateMerge(
                candidate.userId,
                candidate.id,
                toCandidateAction(request, edited),
                null,
                null,
                "ADMIN_EDIT_ACCEPT",
                request == null ? "admin_edit_accepted" : request.reason()
        );
    }

    private void merge(AiMemoryCandidateEntity candidate, AiMemoryReviewActionRequest request) {
        if (request == null || request.targetMemoryId() == null) {
            throw new DomainException(ErrorCode.VALIDATION_FAILED, "Target memory is required");
        }
        AiUserMemoryEntity target = memoryMapper.selectById(request.targetMemoryId());
        if (target == null) {
            throw new DomainException(ErrorCode.NOT_FOUND, "Target AI memory not found");
        }
        if (!candidate.userId.equals(target.getUserId())) {
            throw new DomainException(ErrorCode.FORBIDDEN, "Target AI memory belongs to another user");
        }
        if (request.targetClaimId() != null) {
            AiMemoryClaimEntity claim = claimMapper.selectById(request.targetClaimId());
            if (claim == null) {
                throw new DomainException(ErrorCode.NOT_FOUND, "Target AI memory claim not found");
            }
            if (!candidate.userId.equals(claim.userId)
                    || (claim.legacyMemoryId != null && !request.targetMemoryId().equals(claim.legacyMemoryId))) {
                throw new DomainException(ErrorCode.FORBIDDEN, "Target AI memory claim does not match the target memory");
            }
        }
        mergeService.enqueueCandidateMerge(
                candidate.userId,
                candidate.id,
                toCandidateAction(request, firstNonBlank(request.canonicalText(), candidate.canonicalText)),
                target.getId(),
                request.targetClaimId(),
                "ADMIN_MERGE",
                request.reason()
        );
    }

    private void requestUserConfirmation(AiMemoryCandidateEntity candidate, AiMemoryReviewActionRequest request) {
        LocalDateTime now = LocalDateTime.now();
        Map<String, Object> value = new LinkedHashMap<>(readObjectMap(candidate.valueJson));
        Map<String, Object> review = new LinkedHashMap<>();
        review.put("requestedBy", "ADMIN_REVIEW");
        review.put("requestedAt", now.toString());
        review.put("reason", safeText(request == null ? null : request.reason()));
        value.put("adminReview", review);
        candidate.valueJson = toJson(value);
        candidate.status = "NEEDS_CONFIRMATION";
        candidate.needsConfirmation = Boolean.TRUE;
        candidate.updatedAt = now;
        candidateMapper.updateById(candidate);
    }

    private AiMemoryCandidateActionRequest toCandidateAction(AiMemoryReviewActionRequest request, String content) {
        return new AiMemoryCandidateActionRequest(
                request == null ? null : request.category(),
                request == null ? null : request.title(),
                request == null ? null : request.memoryType(),
                safeText(content),
                request == null ? null : safeText(request.reason())
        );
    }

    private AiMemoryReviewDetailResponse detail(AiMemoryCandidateEntity candidate) {
        List<AiMemoryReviewEvidenceResponse> evidence = evidenceMapper.selectList(new QueryWrapper<AiMemoryEvidenceEntity>()
                        .eq("user_id", candidate.userId)
                        .eq("candidate_id", candidate.id)
                        .orderByDesc("created_at")
                        .last("LIMIT 50"))
                .stream()
                .map(this::toEvidenceResponse)
                .toList();
        return new AiMemoryReviewDetailResponse(
                toListItem(candidate),
                evidence,
                relatedMemories(candidate),
                relatedProfiles(candidate),
                DEFAULT_ACTIONS
        );
    }

    private List<AiMemoryReviewRelatedMemoryResponse> relatedMemories(AiMemoryCandidateEntity candidate) {
        List<AiUserMemoryEntity> memories = memoryMapper.selectList(new QueryWrapper<AiUserMemoryEntity>()
                .eq("user_id", candidate.userId)
                .eq("status", AiMemoryService.STATUS_ACTIVE)
                .and(wrapper -> wrapper
                        .eq("memory_type", candidate.memoryKey)
                        .or()
                        .eq("category", legacyCategory(candidate.category)))
                .orderByDesc("updated_at")
                .last("LIMIT 10"));
        return memories.stream().map(this::toRelatedMemory).toList();
    }

    private List<AiMemoryReviewRelatedProfileResponse> relatedProfiles(AiMemoryCandidateEntity candidate) {
        List<AiLearningProfileEntity> profiles = profileMapper.selectList(new QueryWrapper<AiLearningProfileEntity>()
                .eq("user_id", candidate.userId)
                .isNull("deleted_at")
                .and(wrapper -> wrapper
                        .eq("profile_key", candidate.memoryKey)
                        .or()
                        .eq("category", normalize(candidate.category).toLowerCase(Locale.ROOT)))
                .orderByDesc("updated_at")
                .last("LIMIT 10"));
        return profiles.stream().map(this::toRelatedProfile).toList();
    }

    private AiMemoryReviewListItemResponse toListItem(AiMemoryCandidateEntity item) {
        return new AiMemoryReviewListItemResponse(
                item.id,
                item.userId,
                safeText(item.category),
                safeText(item.memoryKey),
                safeText(item.canonicalText),
                safeText(item.scopeType),
                safeText(item.scopeId),
                safeText(item.evidenceType),
                item.extractionConfidence,
                item.writeScore,
                Boolean.TRUE.equals(item.needsConfirmation),
                readStringList(item.qualityFlags),
                readStringList(item.ambiguityFlags),
                safeText(item.status),
                safeText(item.rejectedReason),
                safeText(item.sourceConversationId),
                item.sourceMessageId,
                item.createdAt == null ? null : item.createdAt.atZone(ZONE).toInstant(),
                item.updatedAt == null ? null : item.updatedAt.atZone(ZONE).toInstant()
        );
    }

    private AiMemoryReviewEvidenceResponse toEvidenceResponse(AiMemoryEvidenceEntity item) {
        return new AiMemoryReviewEvidenceResponse(
                item.id,
                item.claimId,
                item.candidateId,
                safeText(item.evidenceType),
                safeText(item.evidenceText),
                item.confidence,
                safeText(item.reason),
                item.createdAt == null ? null : item.createdAt.atZone(ZONE).toInstant()
        );
    }

    private AiMemoryReviewRelatedMemoryResponse toRelatedMemory(AiUserMemoryEntity memory) {
        return new AiMemoryReviewRelatedMemoryResponse(
                memory.getId(),
                safeText(memory.getCategory()),
                safeText(memory.getTitle()),
                safeText(memory.getMemoryType()),
                safeText(memory.getContent()),
                memory.getConfidence(),
                safeText(memory.getSource()),
                safeText(memory.getStatus()),
                memory.getUpdatedAt() == null ? null : memory.getUpdatedAt().atZone(ZONE).toInstant()
        );
    }

    private AiMemoryReviewRelatedProfileResponse toRelatedProfile(AiLearningProfileEntity profile) {
        return new AiMemoryReviewRelatedProfileResponse(
                profile.id,
                safeText(profile.category),
                safeText(profile.profileKey),
                safeText(profile.label),
                safeText(profile.state),
                profile.confidence,
                profile.evidenceCount,
                profile.updatedAt == null ? null : profile.updatedAt.atZone(ZONE).toInstant()
        );
    }

    private void audit(Long actorUserId, AiMemoryCandidateEntity candidate, String action, AiMemoryReviewActionRequest request) {
        Map<String, Object> summary = new LinkedHashMap<>();
        summary.put("candidateId", candidate.id);
        summary.put("targetUserId", candidate.userId);
        summary.put("action", action);
        summary.put("status", candidate.status);
        summary.put("category", safeText(candidate.category));
        summary.put("memoryKey", safeText(candidate.memoryKey));
        summary.put("qualityFlags", readStringList(candidate.qualityFlags));
        summary.put("ambiguityFlags", readStringList(candidate.ambiguityFlags));
        if (request != null && request.targetMemoryId() != null) {
            summary.put("targetMemoryId", request.targetMemoryId());
        }
        if (request != null && request.targetClaimId() != null) {
            summary.put("targetClaimId", request.targetClaimId());
        }
        auditWriter.record(
                "AI_MEMORY_REVIEW_" + action,
                "AI_MEMORY_CANDIDATE",
                candidate.id,
                "SUCCESS",
                summary,
                actorUserId,
                null,
                null,
                candidate.userId
        );
    }

    private AiMemoryCandidateEntity requireCandidate(Long id) {
        AiMemoryCandidateEntity candidate = id == null ? null : candidateMapper.selectById(id);
        if (candidate == null) {
            throw new DomainException(ErrorCode.NOT_FOUND, "AI memory review candidate not found");
        }
        return candidate;
    }

    private long normalizePage(long page) {
        return Math.max(1, page);
    }

    private long normalizePageSize(long pageSize) {
        return Math.max(1, Math.min(100, pageSize));
    }

    private String legacyCategory(String category) {
        return switch (normalize(category).toUpperCase(Locale.ROOT)) {
            case "RULE" -> "rule";
            case "PREFERENCE" -> "preference";
            case "HABIT" -> "habit";
            case "WEAKNESS" -> "weakness";
            default -> "memory";
        };
    }

    private String safeText(String value) {
        return sanitizer.sanitizeText(value == null ? "" : value);
    }

    private String normalize(String value) {
        return value == null ? "" : value.replace("\r\n", "\n").replace('\r', '\n').trim();
    }

    private String firstNonBlank(String... values) {
        for (String value : values) {
            String normalized = normalize(value);
            if (!normalized.isBlank()) {
                return normalized;
            }
        }
        return "";
    }

    private List<String> readStringList(String json) {
        if (json == null || json.isBlank()) {
            return List.of();
        }
        try {
            return objectMapper.readValue(json, new TypeReference<List<String>>() {
            });
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
            if (!(value instanceof Map<?, ?> map)) {
                return Map.of();
            }
            Map<String, Object> copy = new LinkedHashMap<>();
            for (Map.Entry<?, ?> entry : map.entrySet()) {
                copy.put(String.valueOf(entry.getKey()), entry.getValue());
            }
            return copy;
        } catch (Exception ignored) {
            return Map.of();
        }
    }

    private String toJson(Map<String, Object> value) {
        try {
            return objectMapper.writeValueAsString(value);
        } catch (JsonProcessingException ignored) {
            return "{}";
        }
    }
}
