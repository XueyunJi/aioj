package com.aioj.next.ai.domain.memory;

import com.aioj.next.ai.domain.AiCapacityService;
import com.aioj.next.ai.domain.AiMemoryService;
import com.aioj.next.ai.domain.AiModelCompletionClient;
import com.aioj.next.ai.domain.AiModelConfigResolver;
import com.aioj.next.ai.domain.AiModelEffectiveConfig;
import com.aioj.next.ai.domain.AiModelScope;
import com.aioj.next.ai.domain.AiQuotaService;
import com.aioj.next.ai.domain.AiRetrievalService;
import com.aioj.next.ai.persistence.entity.AiMemoryCandidateEntity;
import com.aioj.next.ai.persistence.entity.AiMemoryClaimEntity;
import com.aioj.next.ai.persistence.entity.AiMemoryEvidenceEntity;
import com.aioj.next.ai.persistence.entity.AiMemoryJobEntity;
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
import com.aioj.next.contract.ai.AiMemoryMergeMaintenanceRequest;
import com.aioj.next.contract.ai.AiMemoryMergeMaintenanceResponse;
import com.aioj.next.contract.ai.AiMemoryResponse;
import com.baomidou.mybatisplus.core.conditions.query.QueryWrapper;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

@Service
public class AiMemoryMergeService {
    public static final String STATUS_MERGE_QUEUED = "MERGE_QUEUED";
    public static final String STATUS_MERGED = "MERGED";

    private static final String STATUS_ACTIVE = "ACTIVE";

    private static final ZoneId ZONE = ZoneId.systemDefault();
    private static final Logger log = LoggerFactory.getLogger(AiMemoryMergeService.class);
    private static final String SOURCE_USER_CONFIRMED = "USER_CONFIRMED";
    private static final String SOURCE_AI_EXTRACTED = "AI_EXTRACTED";
    private static final String SOURCE_AI_MEMORY_MERGED = "AI_MEMORY_MERGED";
    private static final String STATUS_SUPERSEDED = "SUPERSEDED";
    private static final String EVIDENCE_MAINTENANCE = "MEMORY_MERGE_MAINTENANCE";
    /**
     * V3 P2-7 distrust guard: merge source actions driven by automatic flows. Any other
     * non-blank action is an explicit user action; a missing/blank action defaults to AUTO
     * (never revives user-disabled claims), which is the safe reading of frozen decision Q5/Q6.
     */
    private static final Set<String> AUTO_MERGE_SOURCE_ACTIONS = Set.of("AUTO_MEMORY_EXTRACTION", "MAINTENANCE_DEDUPE");

    private final AiMemoryCandidateMapper candidateMapper;
    private final AiMemoryEvidenceMapper evidenceMapper;
    private final AiMemoryClaimMapper claimMapper;
    private final AiMemoryVersionMapper versionMapper;
    private final AiUserMemoryMapper memoryMapper;
    private final AiRetrievalService retrievalService;
    private final AiMemoryEventService eventService;
    private final AiMemoryEventPayloadSanitizer sanitizer;
    private final ObjectMapper objectMapper;
    private final AiModelConfigResolver configResolver;
    private final AiModelCompletionClient completionClient;
    private final AiQuotaService quotaService;
    private final AiCapacityService capacityService;

    public AiMemoryMergeService(
            AiMemoryCandidateMapper candidateMapper,
            AiMemoryEvidenceMapper evidenceMapper,
            AiMemoryClaimMapper claimMapper,
            AiMemoryVersionMapper versionMapper,
            AiUserMemoryMapper memoryMapper,
            AiRetrievalService retrievalService,
            AiMemoryEventService eventService,
            AiMemoryEventPayloadSanitizer sanitizer,
            ObjectMapper objectMapper,
            AiModelConfigResolver configResolver,
            AiModelCompletionClient completionClient,
            AiQuotaService quotaService,
            AiCapacityService capacityService
    ) {
        this.candidateMapper = candidateMapper;
        this.evidenceMapper = evidenceMapper;
        this.claimMapper = claimMapper;
        this.versionMapper = versionMapper;
        this.memoryMapper = memoryMapper;
        this.retrievalService = retrievalService;
        this.eventService = eventService;
        this.sanitizer = sanitizer;
        this.objectMapper = objectMapper;
        this.configResolver = configResolver;
        this.completionClient = completionClient;
        this.quotaService = quotaService;
        this.capacityService = capacityService;
    }

    @Transactional
    public MergeEnqueueResult enqueueCandidateMerge(
            Long userId,
            Long candidateId,
            AiMemoryCandidateActionRequest request,
            Long targetMemoryId,
            Long targetClaimId,
            String action,
            String reason
    ) {
        AiMemoryCandidateEntity candidate = requireOwnedCandidate(userId, candidateId);
        if (STATUS_MERGE_QUEUED.equals(normalizeStatus(candidate.status))) {
            return new MergeEnqueueResult(candidate, null, null);
        }
        if (!isCandidateReviewable(candidate.status)) {
            // V3 P2: a freshly gate-activated candidate (status ACTIVE straight from
            // recordExtraction, never merge-enqueued — no memoryMerge marker in valueJson)
            // is a legitimate AUTO_MEMORY_EXTRACTION input. Post-merge ACTIVE rows carry the
            // marker and every other processed status stays rejected as before.
            boolean freshAutoActive = STATUS_ACTIVE.equals(normalizeStatus(candidate.status))
                    && "AUTO_MEMORY_EXTRACTION".equals(normalize(action).toUpperCase(Locale.ROOT))
                    && (candidate.valueJson == null || !candidate.valueJson.contains("\"memoryMerge\""));
            if (!freshAutoActive) {
                throw new DomainException(ErrorCode.VALIDATION_FAILED, "AI memory candidate has already been processed");
            }
        }
        AiUserMemoryEntity targetMemory = validateTargetMemory(userId, targetMemoryId);
        validateTargetClaim(userId, targetMemory, targetClaimId);

        LocalDateTime now = LocalDateTime.now();
        String content = safeText(firstNonBlank(request == null ? null : request.canonicalText(), candidate.canonicalText));
        if (content.isBlank() || AiMemoryEventPayloadSanitizer.OMITTED.equals(content)) {
            throw new DomainException(ErrorCode.VALIDATION_FAILED, "Memory merge content is not safe");
        }
        String normalizedAction = normalize(action).isBlank() ? "ACCEPT" : normalize(action).toUpperCase(Locale.ROOT);
        Map<String, Object> mergeMetadata = new LinkedHashMap<>();
        mergeMetadata.put("action", normalizedAction);
        mergeMetadata.put("requestedAt", now.toString());
        mergeMetadata.put("reason", safeText(reason));
        mergeMetadata.put("targetMemoryId", targetMemoryId == null ? "" : String.valueOf(targetMemoryId));
        mergeMetadata.put("targetClaimId", targetClaimId == null ? "" : String.valueOf(targetClaimId));
        mergeMetadata.put("contentPreview", content);

        Map<String, Object> value = new LinkedHashMap<>(readObjectMap(candidate.valueJson));
        value.put("memoryMerge", mergeMetadata);
        candidate.valueJson = toJson(value);
        candidate.canonicalText = content;
        candidate.status = STATUS_MERGE_QUEUED;
        candidate.needsConfirmation = Boolean.FALSE;
        candidate.rejectedReason = safeText(firstNonBlank(reason, "merge_queued"));
        candidate.updatedAt = now;
        candidateMapper.updateById(candidate);

        Map<String, Object> payload = mergePayload(candidate, request, targetMemory, targetClaimId, normalizedAction, reason);
        AiMemoryEventService.RecordedEvent event = eventService.recordEvent(
                AiMemoryJobTypes.EVENT_AI_MEMORY_MERGE_REQUESTED,
                userId,
                "AI_MEMORY_CANDIDATE",
                String.valueOf(candidate.id),
                "ai-memory-merge-requested:" + candidate.id,
                payload,
                AiMemoryEventService.SENSITIVITY_USER_PRIVATE_SAFE,
                List.of(new AiMemoryEventService.EventJobSpec(
                        AiMemoryJobTypes.JOB_AI_MEMORY_MERGE_REVIEW,
                        "ai-memory-merge-review:" + candidate.id,
                        payload,
                        null,
                        now
                ))
        );
        AiMemoryJobEntity job = event.jobs().isEmpty() ? null : event.jobs().get(0);
        return new MergeEnqueueResult(candidate, targetMemory, job);
    }

    @Transactional
    public AiMemoryMergeMaintenanceResponse enqueueMaintenance(
            Long actorUserId,
            AiMemoryMergeMaintenanceRequest request
    ) {
        Long targetUserId = request == null || request.targetUserId() == null ? actorUserId : request.targetUserId();
        if (targetUserId == null) {
            throw new DomainException(ErrorCode.VALIDATION_FAILED, "Target user id is required");
        }
        int limit = Math.max(10, Math.min(100, request == null || request.limit() == null ? 50 : request.limit()));
        QueryWrapper<AiUserMemoryEntity> query = new QueryWrapper<AiUserMemoryEntity>()
                .eq("user_id", targetUserId)
                .eq("status", AiMemoryService.STATUS_ACTIVE)
                .orderByDesc("updated_at")
                .last("LIMIT " + limit);
        String category = normalize(request == null ? null : request.category()).toLowerCase(Locale.ROOT);
        if (!category.isBlank()) {
            query.eq("category", category);
        }
        List<AiUserMemoryEntity> memories = memoryMapper.selectList(query);
        List<List<AiUserMemoryEntity>> groups = maintenanceGroups(memories);

        List<Long> candidateIds = new ArrayList<>();
        List<Long> jobIds = new ArrayList<>();
        int relatedGroups = 0;
        for (List<AiUserMemoryEntity> group : groups) {
            if (group.size() < 2) {
                continue;
            }
            relatedGroups++;
            AiUserMemoryEntity anchor = group.get(0);
            AiMemoryCandidateEntity candidate = maintenanceCandidate(targetUserId, anchor, group);
            MergeEnqueueResult result = enqueueCandidateMerge(
                    targetUserId,
                    candidate.id,
                    new AiMemoryCandidateActionRequest(
                            v2Category(anchor.getCategory()),
                            anchor.getTitle(),
                            anchor.getMemoryType(),
                            candidate.canonicalText,
                            "memory_merge_maintenance"
                    ),
                    anchor.getId(),
                    null,
                    "MAINTENANCE_DEDUPE",
                    "memory_merge_maintenance"
            );
            candidateIds.add(result.candidate().id);
            if (result.job() != null && result.job().getId() != null) {
                jobIds.add(result.job().getId());
            }
        }
        return new AiMemoryMergeMaintenanceResponse(
                targetUserId,
                memories.size(),
                relatedGroups,
                jobIds.size(),
                List.copyOf(candidateIds),
                List.copyOf(jobIds)
        );
    }

    private List<List<AiUserMemoryEntity>> maintenanceGroups(List<AiUserMemoryEntity> memories) {
        List<AiUserMemoryEntity> valid = memories.stream()
                .filter(memory -> memory != null && memory.getId() != null)
                .toList();
        List<List<AiUserMemoryEntity>> groups = new ArrayList<>();
        Set<Long> visited = new LinkedHashSet<>();
        for (AiUserMemoryEntity anchor : valid) {
            if (!visited.add(anchor.getId())) {
                continue;
            }
            List<AiUserMemoryEntity> group = new ArrayList<>();
            group.add(anchor);
            boolean changed;
            do {
                changed = false;
                for (AiUserMemoryEntity candidate : valid) {
                    if (visited.contains(candidate.getId())) {
                        continue;
                    }
                    if (group.stream().anyMatch(member -> maintenanceRelatedScore(member, candidate) >= 4)) {
                        visited.add(candidate.getId());
                        group.add(candidate);
                        changed = true;
                    }
                }
            } while (changed);
            groups.add(group);
        }
        return groups;
    }

    private int maintenanceRelatedScore(AiUserMemoryEntity left, AiUserMemoryEntity right) {
        if (!normalize(left.getCategory()).equals(normalize(right.getCategory()))) {
            return 0;
        }
        int score = 0;
        if (normalize(left.getMemoryType()).equals(normalize(right.getMemoryType()))) {
            score += 3;
        }
        String leftContent = normalize(left.getContent());
        String rightContent = normalize(right.getContent());
        if (!leftContent.isBlank() && leftContent.equals(rightContent)) {
            score += 20;
        }
        Set<String> leftTokens = semanticTokens(left.getMemoryType() + " " + left.getTitle() + " " + leftContent);
        Set<String> rightTokens = semanticTokens(right.getMemoryType() + " " + right.getTitle() + " " + rightContent);
        for (String token : leftTokens) {
            if (rightTokens.contains(token) && !isLowSignalMaintenanceToken(token)) {
                score += token.length() >= 2 ? 2 : 1;
            }
        }
        return score;
    }

    private boolean isLowSignalMaintenanceToken(String token) {
        return Set.of("用户", "偏好", "记忆", "user", "users", "prefer", "prefers", "preference", "memory")
                .contains(normalize(token).toLowerCase(Locale.ROOT));
    }

    public AiMemoryResponse pendingResponse(AiMemoryCandidateEntity candidate, AiUserMemoryEntity target) {
        if (target != null) {
            return toMemoryResponse(target, STATUS_MERGE_QUEUED);
        }
        return new AiMemoryResponse(
                candidate.id,
                legacyCategory(candidate.category),
                titleFor(candidate.category, candidate.canonicalText),
                legacyMemoryType(candidate.category, candidate.memoryKey),
                candidate.canonicalText,
                candidate.writeScore,
                SOURCE_USER_CONFIRMED,
                STATUS_MERGE_QUEUED,
                candidate.createdAt == null ? null : candidate.createdAt.atZone(ZONE).toInstant(),
                candidate.updatedAt == null ? null : candidate.updatedAt.atZone(ZONE).toInstant(),
                null
        );
    }

    @Transactional
    public void handleJob(AiMemoryJobEntity job) {
        Map<String, Object> payload = readObjectMap(job == null ? null : job.getPayloadJson());
        Long userId = readLong(payload.get("userId"));
        Long candidateId = readLong(payload.get("candidateId"));
        if (userId == null || candidateId == null) {
            throw new AiMemoryJobPermanentFailure("Memory merge job payload is missing userId or candidateId");
        }
        AiMemoryCandidateEntity candidate = requireOwnedCandidate(userId, candidateId);
        if (AiMemoryService.STATUS_ACTIVE.equals(candidate.status) || STATUS_MERGED.equals(candidate.status)) {
            return;
        }
        Long hintedTargetMemoryId = readLong(payload.get("targetMemoryId"));
        AiUserMemoryEntity hintedTarget = validateTargetMemory(userId, hintedTargetMemoryId);
        List<AiUserMemoryEntity> related = relatedMemories(candidate, hintedTarget);
        MergeDecision decision = runModel(userId, candidate, payload, related);
        // V3 P2-7: the merge source action (ACCEPT / AUTO_MEMORY_EXTRACTION / ...) travels in
        // the job payload (mergePayload) and gates distrusted-claim revival in upsertClaim.
        String mergeSourceAction = payload.get("action") == null ? "" : normalize(String.valueOf(payload.get("action")));
        applyDecision(userId, candidate, hintedTarget, related, decision, payload, mergeSourceAction);
    }

    private MergeDecision runModel(
            Long userId,
            AiMemoryCandidateEntity candidate,
            Map<String, Object> payload,
            List<AiUserMemoryEntity> related
    ) {
        AiModelEffectiveConfig config = configResolver.effectiveConfig(AiModelScope.MEMORY_EXTRACTION);
        return capacityService.call(AiCapacityService.AiWorkload.INTENT_MEMORY, () -> {
            quotaService.assertMonthlyAvailable(userId);
            AiModelCompletionClient.CompletionResult result = completionClient.complete(
                    config,
                    List.of(
                            message("system", """
                                    你是 AI-OJ 的长期记忆合并审查器。
                                    只输出一个严格 JSON 对象；第一个非空字符必须是 {，最后一个非空字符必须是 }。
                                    不要输出 Markdown、解释、分析过程、代码块或 JSON 外文本。
                                    优先使用英文 schema：actions,candidateStatus,reviewReason。
                                    actions 每一项字段：action,targetMemoryId,memoryKey,category,memoryType,canonicalText,confidence,supportDelta,contradictionDelta,evidenceItems,status,reason。
                                    """),
                            message("user", mergePrompt(candidate, payload, related))
                    ),
                    config.temperatureOr(0.1),
                    Math.min(config.maxTokensOr(1200), 1800),
                    true
            );
            quotaService.record(userId, result.provider(), result.model(), result.promptTokens(), result.completionTokens(), true);
            return parseDecision(result.content());
        });
    }

    private void applyDecision(
            Long userId,
            AiMemoryCandidateEntity candidate,
            AiUserMemoryEntity hintedTarget,
            List<AiUserMemoryEntity> related,
            MergeDecision decision,
            Map<String, Object> payload,
            String mergeSourceAction
    ) {
        LocalDateTime now = LocalDateTime.now();
        if (decision.actions().isEmpty()) {
            candidate.status = "NEEDS_CONFIRMATION";
            candidate.needsConfirmation = Boolean.TRUE;
            candidate.rejectedReason = safeText(firstNonBlank(decision.reviewReason(), "merge_needs_confirmation"));
            candidate.updatedAt = now;
            candidateMapper.updateById(candidate);
            return;
        }

        boolean applied = false;
        boolean createdAny = false;
        List<String> reasons = new ArrayList<>();
        for (MergeAction action : decision.actions()) {
            String actionType = normalizeActionValue(action.action());
            if ("NEEDS_REVIEW".equals(actionType) || "IGNORE".equals(actionType)) {
                reasons.add(firstNonBlank(action.reason(), decision.reviewReason(), "merge_needs_confirmation"));
                continue;
            }
            AiUserMemoryEntity target = selectTargetMemory(userId, hintedTarget, related, action.targetMemoryId());
            if ("SUPERSEDE".equals(actionType)) {
                if (target != null) {
                    supersedeMemory(userId, target, candidate, action, now);
                    applied = true;
                    reasons.add(firstNonBlank(action.reason(), "memory_merge_superseded"));
                }
                continue;
            }

            boolean createNew = target == null || "CREATE_NEW".equals(actionType) || "SPLIT_CREATE".equals(actionType);
            if (createNew) {
                target = new AiUserMemoryEntity();
                target.setUserId(userId);
                target.setCreatedAt(now);
                target.setSource(SOURCE_AI_MEMORY_MERGED);
                target.setSourceConversationId(candidate.sourceConversationId);
                target.setSourceMessageId(candidate.sourceMessageId);
                createdAny = true;
            } else {
                target.setSource(SOURCE_AI_MEMORY_MERGED);
            }

            String content = safeText(firstNonBlank(action.content(), candidate.canonicalText));
            if (content.isBlank() || AiMemoryEventPayloadSanitizer.OMITTED.equals(content)) {
                throw new DomainException(ErrorCode.INTERNAL_ERROR, "AI memory merge returned unsafe content");
            }
            String actionCategory = firstNonBlank(action.category(), candidate.category);
            String actionKey = firstNonBlank(action.memoryKey(), candidate.memoryKey, action.memoryType(), target.getMemoryType());
            target.setCategory(legacyCategory(actionCategory));
            target.setTitle(titleFor(actionCategory, content));
            target.setMemoryType(legacyMemoryType(actionCategory, actionKey));
            target.setContent(content);
            target.setConfidence(adjustedConfidence(target.getConfidence(), action));
            target.setStatus(normalizeMemoryStatus(action.status()));
            target.setUpdatedAt(now);
            if (createNew) {
                memoryMapper.insert(target);
            } else {
                memoryMapper.updateById(target);
            }
            index(target);

            AiMemoryClaimEntity claim = upsertClaim(userId, target, candidate, action, now, mergeSourceAction);
            List<String> evidenceItems = action.evidenceItems().isEmpty()
                    ? List.of(candidate.canonicalText)
                    : action.evidenceItems();
            for (String item : evidenceItems) {
                insertEvidenceIfAbsent(
                        userId,
                        claim.id,
                        candidate.id,
                        candidate.sourceConversationId,
                        candidate.sourceMessageId,
                        evidenceTypeFor(actionType),
                        item,
                        action.confidence(),
                        firstNonBlank(action.reason(), decision.reviewReason(), "memory_merge_model"),
                        now
                );
            }
            if (("MERGE".equals(actionType) || "REINFORCE".equals(actionType)) && !createNew) {
                supersedeEquivalentRelatedMemories(userId, target, related, candidate, action, now);
            }
            applied = true;
            reasons.add(firstNonBlank(action.reason(), decision.reviewReason(), "memory_merge_applied"));
        }

        candidate.status = applied
                ? normalizeCandidateStatus(decision.candidateStatus(), createdAny)
                : "NEEDS_CONFIRMATION";
        candidate.needsConfirmation = "NEEDS_CONFIRMATION".equals(candidate.status);
        candidate.rejectedReason = safeText(firstNonBlank(String.join("; ", reasons), decision.reviewReason(), "memory_merge_applied"));
        candidate.updatedAt = now;
        candidateMapper.updateById(candidate);
    }

    private AiMemoryClaimEntity upsertClaim(
            Long userId,
            AiUserMemoryEntity memory,
            AiMemoryCandidateEntity candidate,
            MergeAction action,
            LocalDateTime now,
            String mergeSourceAction
    ) {
        String category = firstNonBlank(action.category(), candidate.category, "MANUAL_NOTE");
        String memoryKey = firstNonBlank(action.memoryKey(), candidate.memoryKey, memory.getMemoryType());
        QueryWrapper<AiMemoryClaimEntity> query = new QueryWrapper<AiMemoryClaimEntity>()
                .eq("user_id", userId)
                .eq("scope_type", firstNonBlank(candidate.scopeType, "GLOBAL"))
                .eq("category", category)
                .eq("memory_key", memoryKey);
        if (candidate.scopeId == null || candidate.scopeId.isBlank()) {
            query.isNull("scope_id");
        } else {
            query.eq("scope_id", candidate.scopeId);
        }
        AiMemoryClaimEntity claim = claimMapper.selectOne(query.last("LIMIT 1"));
        // V3 P2-7 distrust guard (frozen decision Q5/Q6): automatic merges — or legacy jobs
        // without an action — never revive a claim the user disabled/deleted. The claim is
        // returned unchanged (id stays usable for evidence rows); only an explicit user
        // action reactivates it, with a small confidence boost below.
        boolean distrustedClaim = claim != null && isDistrustedClaimStatus(claim.status);
        if (distrustedClaim && !isUserExplicitMergeAction(mergeSourceAction)) {
            log.info("memory merge kept distrusted claim inactive userId={} claimId={} key={} action={}",
                    userId, claim.id, claim.memoryKey, mergeSourceAction);
            return claim;
        }
        BigDecimal previousConfidence = claim == null ? null : claim.confidence;
        if (claim == null) {
            claim = new AiMemoryClaimEntity();
            claim.userId = userId;
            claim.scopeType = firstNonBlank(candidate.scopeType, "GLOBAL");
            claim.scopeId = normalize(candidate.scopeId).isBlank() ? null : normalize(candidate.scopeId);
            claim.category = category;
            claim.memoryKey = memoryKey;
            claim.valueJson = candidate.valueJson;
            claim.firstSeenAt = now;
            claim.createdAt = now;
            claim.sourceMode = SOURCE_AI_MEMORY_MERGED;
            claim.sensitivityLevel = "LOW";
            claim.ambiguityLevel = "LOW";
            claim.pinned = Boolean.FALSE;
            claim.version = 1;
        } else {
            claim.version = Math.max(1, claim.version == null ? 1 : claim.version + 1);
        }
        claim.legacyMemoryId = memory.getId();
        claim.canonicalText = memory.getContent();
        claim.confidence = normalizeConfidence(memory.getConfidence());
        claim.stabilityScore = adjustStability(claim.stabilityScore, action);
        claim.supportCount = Math.max(0, claim.supportCount == null ? 0 : claim.supportCount) + Math.max(0, action.supportDelta());
        claim.contradictionCount = Math.max(0, claim.contradictionCount == null ? 0 : claim.contradictionCount) + Math.max(0, action.contradictionDelta());
        if (claim.supportCount == 0 && claim.contradictionCount == 0) {
            claim.supportCount = 1;
        }
        claim.sourceMode = SOURCE_AI_MEMORY_MERGED;
        claim.status = memory.getStatus();
        claim.lastSeenAt = now;
        claim.updatedAt = now;
        if (distrustedClaim) {
            // V3 P2-7: explicit user re-acceptance clears the distrust — reactivate and apply
            // a small confidence boost (old value, null treated as 0.5, capped at 1.0).
            claim.status = AiMemoryService.STATUS_ACTIVE;
            double base = previousConfidence == null ? 0.5 : previousConfidence.doubleValue();
            claim.confidence = BigDecimal.valueOf(Math.min(1.0, base + 0.1)).setScale(4, RoundingMode.HALF_UP);
        }
        if (claim.id == null) {
            claimMapper.insert(claim);
        } else {
            claimMapper.updateById(claim);
        }
        insertVersion(userId, claim, candidate.id, firstNonBlank(action.reason(), "memory_merge_model"), now);
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
        version.changeReason = truncate(safeText(reason), 500);
        version.sourceCandidateId = candidateId;
        version.createdAt = now;
        versionMapper.insert(version);
    }

    private void insertEvidenceIfAbsent(
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
        String safeEvidence = truncate(safeText(evidenceText), 4000);
        if (safeEvidence.isBlank() || AiMemoryEventPayloadSanitizer.OMITTED.equals(safeEvidence)) {
            return;
        }
        QueryWrapper<AiMemoryEvidenceEntity> query = new QueryWrapper<AiMemoryEvidenceEntity>()
                .eq("user_id", userId)
                .eq("candidate_id", candidateId)
                .eq("evidence_type", evidenceType)
                .eq("evidence_text", safeEvidence);
        if (claimId == null) {
            query.isNull("claim_id");
        } else {
            query.eq("claim_id", claimId);
        }
        if (messageId == null) {
            query.isNull("message_id");
        } else {
            query.eq("message_id", messageId);
        }
        if (evidenceMapper.selectOne(query.last("LIMIT 1")) != null) {
            return;
        }
        AiMemoryEvidenceEntity evidence = new AiMemoryEvidenceEntity();
        evidence.userId = userId;
        evidence.claimId = claimId;
        evidence.candidateId = candidateId;
        evidence.conversationId = conversationId;
        evidence.messageId = messageId;
        evidence.evidenceType = evidenceType;
        evidence.evidenceText = safeEvidence;
        evidence.confidence = BigDecimal.valueOf(Math.min(1, Math.max(0, confidence))).setScale(4, RoundingMode.HALF_UP);
        evidence.reason = truncate(safeText(reason), 500);
        evidence.createdAt = now;
        evidenceMapper.insert(evidence);
    }

    private Map<String, Object> mergePayload(
            AiMemoryCandidateEntity candidate,
            AiMemoryCandidateActionRequest request,
            AiUserMemoryEntity targetMemory,
            Long targetClaimId,
            String action,
            String reason
    ) {
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("userId", candidate.userId);
        payload.put("candidateId", candidate.id);
        payload.put("category", safeText(firstNonBlank(request == null ? null : request.category(), candidate.category)));
        payload.put("memoryKey", safeText(candidate.memoryKey));
        payload.put("memoryType", safeText(firstNonBlank(request == null ? null : request.memoryType(), candidate.memoryKey)));
        payload.put("canonicalText", safeText(firstNonBlank(request == null ? null : request.canonicalText(), candidate.canonicalText)));
        payload.put("evidenceType", safeText(candidate.evidenceType));
        payload.put("writeScore", candidate.writeScore);
        payload.put("sourceConversationId", safeText(candidate.sourceConversationId));
        payload.put("sourceMessageId", candidate.sourceMessageId);
        payload.put("targetMemoryId", targetMemory == null ? null : targetMemory.getId());
        payload.put("targetClaimId", targetClaimId);
        payload.put("action", action);
        payload.put("reason", safeText(reason));
        if (targetMemory != null) {
            payload.put("targetMemory", Map.of(
                    "id", targetMemory.getId(),
                    "category", safeText(targetMemory.getCategory()),
                    "memoryType", safeText(targetMemory.getMemoryType()),
                    "content", safeText(targetMemory.getContent()),
                    "confidence", targetMemory.getConfidence(),
                    "status", safeText(targetMemory.getStatus())
            ));
        }
        return payload;
    }

    private AiMemoryCandidateEntity maintenanceCandidate(
            Long targetUserId,
            AiUserMemoryEntity anchor,
            List<AiUserMemoryEntity> group
    ) {
        String category = v2Category(anchor.getCategory());
        String memoryKey = normalizeKey(anchor.getMemoryType());
        AiMemoryCandidateEntity existing = candidateMapper.selectOne(new QueryWrapper<AiMemoryCandidateEntity>()
                .eq("user_id", targetUserId)
                .eq("category", category)
                .eq("memory_key", memoryKey)
                .eq("evidence_type", EVIDENCE_MAINTENANCE)
                .in("status", List.of(STATUS_MERGE_QUEUED, "NEEDS_CONFIRMATION"))
                .orderByDesc("updated_at")
                .last("LIMIT 1"));
        if (existing != null) {
            return existing;
        }
        LocalDateTime now = LocalDateTime.now();
        String text = maintenanceCandidateText(group);
        AiMemoryCandidateEntity candidate = new AiMemoryCandidateEntity();
        candidate.userId = targetUserId;
        candidate.category = category;
        candidate.memoryKey = memoryKey;
        candidate.canonicalText = text;
        candidate.valueJson = toJson(Map.of(
                "candidateKind", "MEMORY_MERGE_MAINTENANCE",
                "memoryIds", group.stream().map(AiUserMemoryEntity::getId).map(String::valueOf).toList()
        ));
        candidate.scopeType = "GLOBAL";
        candidate.scopeId = null;
        candidate.evidenceType = EVIDENCE_MAINTENANCE;
        candidate.extractionConfidence = decimal(0.9);
        candidate.writeScore = decimal(0.9);
        candidate.isLongTerm = Boolean.TRUE;
        candidate.isProblemSpecific = Boolean.FALSE;
        candidate.isHypothetical = Boolean.FALSE;
        candidate.isQuoted = Boolean.FALSE;
        candidate.needsConfirmation = Boolean.FALSE;
        candidate.qualityFlags = toJson(List.of("maintenance_dedupe"));
        candidate.ambiguityFlags = toJson(List.of());
        candidate.status = "NEEDS_CONFIRMATION";
        candidate.rejectedReason = "memory_merge_maintenance";
        candidate.createdAt = now;
        candidate.updatedAt = now;
        candidateMapper.insert(candidate);
        insertEvidenceIfAbsent(
                targetUserId,
                null,
                candidate.id,
                null,
                null,
                EVIDENCE_MAINTENANCE,
                text,
                0.9,
                "memory_merge_maintenance",
                now
        );
        return candidate;
    }

    private String maintenanceCandidateText(List<AiUserMemoryEntity> group) {
        StringBuilder text = new StringBuilder("请合并、拆分或弱化这些高度相关的长期记忆：");
        for (AiUserMemoryEntity memory : group.stream().limit(8).toList()) {
            text.append("\n#").append(memory.getId())
                    .append(" ")
                    .append(safeText(memory.getMemoryType()))
                    .append("：")
                    .append(safeText(memory.getContent()));
        }
        return truncate(text.toString(), 4000);
    }

    private String mergePrompt(
            AiMemoryCandidateEntity candidate,
            Map<String, Object> payload,
            List<AiUserMemoryEntity> related
    ) {
        return """
                请审查一个 AI-OJ 长期记忆候选，并决定是否创建新记忆、合并强化已有记忆、因冲突而减弱已有记忆，或拆分已有记忆。
                只根据下方安全摘要判断。不要输出代码、stdout/stderr、prompt、token/key/password。

                输出严格 JSON；不要加 ```json 标记，不要加解释文字：
                {
                  "actions":[
                    {
                      "action":"CREATE_NEW|MERGE|REINFORCE|WEAKEN|SPLIT_CREATE|SUPERSEDE|NEEDS_REVIEW|IGNORE",
                      "targetMemoryId": null,
                      "memoryKey":"stable_key",
                      "category":"PREFERENCE|RULE|HABIT|WEAKNESS|PROFILE|GOAL|MEMORY",
                      "memoryType":"guidance_preference",
                      "canonicalText":"安全、精炼、带边界的长期记忆文本",
                      "confidence":0.0,
                      "supportDelta":1,
                      "contradictionDelta":0,
                      "evidenceItems":["安全证据摘要"],
                      "status":"ACTIVE|SUPERSEDED|NEEDS_CONFIRMATION",
                      "reason":"简短原因"
                    }
                  ],
                  "candidateStatus":"MERGED|ACTIVE|NEEDS_CONFIRMATION|REJECTED",
                  "reviewReason":"整体简短原因"
                }

                规则：
                - 同一描述对象、同类事实或高度重复内容必须合并强化，不要创建重复项。
                - 兼容事实可以融合取并集，但必须由你重写成自然的 canonicalText，不要简单拼接。
                - 排斥/冲突记忆不要删除；应 WEAKEN，降低置信并加入 contradiction evidence。
                - 如果旧记忆包含多个子概念，而新证据只反驳其中一部分，应使用 SPLIT_CREATE 创建细分记忆，并用 SUPERSEDE 标记旧的过宽记忆。
                - “现在/今天/暂时/这次”等临时表达只应产生低强度影响；通常降低 confidence 或转 NEEDS_REVIEW，不要强行推翻长期偏好。
                - 如果证据不够明确，返回 NEEDS_REVIEW 或 NEEDS_CONFIRMATION。
                - canonicalText 不得包含源码、原始输出、密钥或完整题面。

                <CANDIDATE>
                id=%s
                category=%s
                key=%s
                text=%s
                score=%s
                payload=%s
                </CANDIDATE>

                <RELATED_MEMORIES>
                %s
                </RELATED_MEMORIES>
                """.formatted(
                candidate.id,
                safeText(candidate.category),
                safeText(candidate.memoryKey),
                safeText(candidate.canonicalText),
                candidate.writeScore,
                safeText(toJson(payload)),
                relatedMemoryBlock(related)
        );
    }

    private String relatedMemoryBlock(List<AiUserMemoryEntity> related) {
        if (related == null || related.isEmpty()) {
            return "- none";
        }
        StringBuilder block = new StringBuilder();
        for (AiUserMemoryEntity memory : related) {
            block.append("- #").append(memory.getId())
                    .append(" type=").append(safeText(memory.getMemoryType()))
                    .append(" confidence=").append(memory.getConfidence())
                    .append(" status=").append(safeText(memory.getStatus()))
                    .append(" content=").append(safeText(memory.getContent()))
                    .append('\n');
            List<String> evidence = latestEvidenceForMemory(memory);
            for (String item : evidence) {
                block.append("  evidence=").append(item).append('\n');
            }
        }
        return block.toString().trim();
    }

    private List<String> latestEvidenceForMemory(AiUserMemoryEntity memory) {
        if (memory == null || memory.getId() == null) {
            return List.of();
        }
        AiMemoryClaimEntity claim = claimMapper.selectOne(new QueryWrapper<AiMemoryClaimEntity>()
                .eq("user_id", memory.getUserId())
                .eq("legacy_memory_id", memory.getId())
                .orderByDesc("updated_at")
                .last("LIMIT 1"));
        if (claim == null || claim.id == null) {
            return List.of();
        }
        return evidenceMapper.selectList(new QueryWrapper<AiMemoryEvidenceEntity>()
                        .eq("user_id", memory.getUserId())
                        .eq("claim_id", claim.id)
                        .orderByDesc("created_at")
                        .last("LIMIT 3"))
                .stream()
                .map(item -> truncate(safeText(item.evidenceText), 240))
                .filter(item -> !item.isBlank() && !AiMemoryEventPayloadSanitizer.OMITTED.equals(item))
                .toList();
    }

    private MergeDecision parseDecision(String content) {
        try {
            JsonNode root = readDecisionRoot(content);
            List<MergeAction> actions = new ArrayList<>();
            JsonNode actionsNode = jsonNode(root, "actions", "操作", "动作");
            if (actionsNode.isArray()) {
                for (JsonNode item : actionsNode) {
                    MergeAction action = parseAction(item, root);
                    if (!action.action().isBlank()) {
                        actions.add(action);
                    }
                }
            }
            if (actions.isEmpty()) {
                MergeAction action = parseAction(root, root);
                if (!action.action().isBlank()) {
                    actions.add(action);
                }
            }
            if (actions.isEmpty()) {
                throw new DomainException(ErrorCode.INTERNAL_ERROR, "AI memory merge decision is empty");
            }
            return new MergeDecision(
                    List.copyOf(actions),
                    safeText(jsonText(root, "candidateStatus", "candidate_status", "status", "候选状态")),
                    safeText(jsonText(root, "reviewReason", "review_reason", "reason", "原因", "审查原因"))
            );
        } catch (DomainException ex) {
            throw ex;
        } catch (Exception ex) {
            throw new DomainException(ErrorCode.INTERNAL_ERROR, "AI memory merge response could not be parsed");
        }
    }

    private MergeAction parseAction(JsonNode node, JsonNode root) {
        String decision = normalizeActionValue(jsonText(node, "action", "decision", "决定", "决策"));
        if (decision.isBlank()) {
            return new MergeAction("", null, "", "", "", "", 0.75, 0, 0, List.of(), "", "");
        }
        List<String> evidence = new ArrayList<>();
        JsonNode evidenceNode = jsonNode(node, "evidenceItems", "evidence_items", "evidence", "evidences", "证据", "证据项");
        if (!evidenceNode.isArray()) {
            evidenceNode = jsonNode(root, "evidenceItems", "evidence_items", "evidence", "evidences", "证据", "证据项");
        }
        if (evidenceNode.isArray()) {
            for (JsonNode item : evidenceNode) {
                String safe = safeText(item.asText(""));
                if (!safe.isBlank() && !AiMemoryEventPayloadSanitizer.OMITTED.equals(safe)) {
                    evidence.add(safe);
                }
            }
        }
        Long targetMemoryId = jsonLong(node, "targetMemoryId", "target_memory_id", "targetId", "target_id", "memoryId", "目标记忆ID", "目标记忆");
        if (targetMemoryId == null) {
            targetMemoryId = jsonLong(root, "targetMemoryId", "target_memory_id", "targetId", "target_id", "memoryId", "目标记忆ID", "目标记忆");
        }
        String content = safeText(firstNonBlank(
                jsonText(node, "canonicalText", "canonical_text", "mergedContent", "merged_content", "content", "memory", "summary", "合并内容", "记忆内容", "摘要"),
                jsonText(root, "canonicalText", "canonical_text", "mergedContent", "merged_content", "content", "memory", "summary", "合并内容", "记忆内容", "摘要")
        ));
        String reason = safeText(firstNonBlank(
                jsonText(node, "reason", "reviewReason", "review_reason", "原因", "审查原因"),
                jsonText(root, "reviewReason", "review_reason", "reason", "原因", "审查原因")
        ));
        String rawMemoryKey = jsonText(node, "memoryKey", "memory_key", "key", "记忆键");
        return new MergeAction(
                decision,
                targetMemoryId,
                normalize(rawMemoryKey).isBlank() ? "" : normalizeKey(rawMemoryKey),
                safeText(jsonText(node, "category", "类别")),
                safeText(jsonText(node, "memoryType", "memory_type", "type", "类型")),
                content,
                Math.min(1.0, Math.max(0.0, jsonDouble(node, jsonDouble(root, 0.75, "confidence", "置信度"), "confidence", "置信度"))),
                Math.max(0, jsonInt(node, jsonInt(root, 1, "supportDelta", "support_delta", "support", "支持增量"), "supportDelta", "support_delta", "support", "支持增量")),
                Math.max(0, jsonInt(node, jsonInt(root, 0, "contradictionDelta", "contradiction_delta", "contradiction", "冲突增量", "反证增量"), "contradictionDelta", "contradiction_delta", "contradiction", "冲突增量", "反证增量")),
                List.copyOf(evidence),
                safeText(jsonText(node, "status", "memoryStatus", "memory_status", "状态")),
                reason
        );
    }

    private JsonNode readDecisionRoot(String content) throws Exception {
        Exception lastError = null;
        for (String candidate : extractJsonCandidates(content)) {
            try {
                JsonNode root = findDecisionNode(objectMapper.readTree(normalizeJsonLikeText(candidate)), 0);
                if (root != null) {
                    return root;
                }
            } catch (Exception ex) {
                lastError = ex;
            }
        }
        String normalized = normalizeJsonLikeText(content == null ? "" : content.trim());
        if (!normalized.isBlank()) {
            try {
                JsonNode root = findDecisionNode(objectMapper.readTree(normalized), 0);
                if (root != null) {
                    return root;
                }
            } catch (Exception ex) {
                lastError = ex;
            }
        }
        if (lastError != null) {
            throw lastError;
        }
        throw new DomainException(ErrorCode.INTERNAL_ERROR, "AI memory merge decision is empty");
    }

    private JsonNode findDecisionNode(JsonNode node, int depth) {
        if (node == null || node.isMissingNode() || node.isNull() || depth > 4) {
            return null;
        }
        if (node.isObject()) {
            JsonNode actions = jsonNode(node, "actions", "操作", "动作");
            if ((actions.isArray() && !actions.isEmpty()) || !jsonText(node, "decision", "action", "决定", "决策").isBlank()) {
                return node;
            }
            for (JsonNode child : node) {
                JsonNode found = findDecisionNode(child, depth + 1);
                if (found != null) {
                    return found;
                }
            }
        } else if (node.isArray()) {
            for (JsonNode child : node) {
                JsonNode found = findDecisionNode(child, depth + 1);
                if (found != null) {
                    return found;
                }
            }
        }
        return null;
    }

    private List<String> extractJsonCandidates(String content) {
        String normalized = content == null ? "" : content.trim();
        List<String> candidates = new ArrayList<>();
        boolean inString = false;
        boolean escaped = false;
        int depth = 0;
        int start = -1;
        for (int index = 0; index < normalized.length(); index++) {
            char current = normalized.charAt(index);
            if (escaped) {
                escaped = false;
                continue;
            }
            if (current == '\\') {
                escaped = inString;
                continue;
            }
            if (current == '"') {
                inString = !inString;
                continue;
            }
            if (inString) {
                continue;
            }
            if (current == '{') {
                if (depth == 0) {
                    start = index;
                }
                depth++;
            } else if (current == '}') {
                if (depth > 0) {
                    depth--;
                }
                if (depth == 0 && start >= 0) {
                    candidates.add(normalized.substring(start, index + 1));
                    start = -1;
                }
            }
        }
        return candidates;
    }

    private String normalizeJsonLikeText(String value) {
        if (value == null) {
            return "";
        }
        return value.trim()
                .replace('\uFEFF', ' ')
                .replace('“', '"')
                .replace('”', '"')
                .replace('‘', '\'')
                .replace('’', '\'')
                .replaceFirst("(?s)^```(?:json)?\\s*", "")
                .replaceFirst("(?s)\\s*```$", "")
                .trim();
    }

    private JsonNode jsonNode(JsonNode root, String... keys) {
        if (root == null || keys == null) {
            return objectMapper.missingNode();
        }
        for (String key : keys) {
            JsonNode value = root.get(key);
            if (value != null && !value.isMissingNode() && !value.isNull()) {
                return value;
            }
        }
        return objectMapper.missingNode();
    }

    private String jsonText(JsonNode root, String... keys) {
        JsonNode value = jsonNode(root, keys);
        if (value.isMissingNode() || value.isNull()) {
            return "";
        }
        if (value.isTextual() || value.isNumber() || value.isBoolean()) {
            return normalize(value.asText(""));
        }
        return "";
    }

    private Long jsonLong(JsonNode root, String... keys) {
        JsonNode value = jsonNode(root, keys);
        if (value.isMissingNode() || value.isNull()) {
            return null;
        }
        if (value.isNumber()) {
            return value.asLong();
        }
        return readLong(value.asText(""));
    }

    private double jsonDouble(JsonNode root, double defaultValue, String... keys) {
        JsonNode value = jsonNode(root, keys);
        if (value.isMissingNode() || value.isNull()) {
            return defaultValue;
        }
        if (value.isNumber()) {
            return value.asDouble(defaultValue);
        }
        try {
            return Double.parseDouble(value.asText(""));
        } catch (RuntimeException ex) {
            return defaultValue;
        }
    }

    private int jsonInt(JsonNode root, int defaultValue, String... keys) {
        JsonNode value = jsonNode(root, keys);
        if (value.isMissingNode() || value.isNull()) {
            return defaultValue;
        }
        if (value.isNumber()) {
            return value.asInt(defaultValue);
        }
        try {
            return Integer.parseInt(value.asText(""));
        } catch (RuntimeException ex) {
            return defaultValue;
        }
    }

    private String normalizeActionValue(String value) {
        String normalized = normalize(value).toUpperCase(Locale.ROOT)
                .replace('-', '_')
                .replace(' ', '_');
        return switch (normalized) {
            case "CREATE", "CREATE_NEW", "NEW", "新增", "创建", "创建新记忆" -> "CREATE_NEW";
            case "SPLIT", "SPLIT_CREATE", "拆分", "拆分新记忆", "拆分创建" -> "SPLIT_CREATE";
            case "MERGE", "合并", "合并强化" -> "MERGE";
            case "REINFORCE", "强化", "支持", "增强" -> "REINFORCE";
            case "WEAKEN", "CONTRADICT", "减弱", "弱化", "冲突减弱" -> "WEAKEN";
            case "SUPERSEDE", "SUPERSEDED", "取代", "被取代", "标记过宽", "过宽旧记忆" -> "SUPERSEDE";
            case "NEEDS_CONFIRMATION", "NEEDS_REVIEW", "REVIEW", "待确认", "需要确认", "需要审查" -> "NEEDS_REVIEW";
            case "IGNORE", "REJECT", "忽略", "拒绝" -> "IGNORE";
            default -> normalized;
        };
    }

    private List<AiUserMemoryEntity> relatedMemories(AiMemoryCandidateEntity candidate, AiUserMemoryEntity hintedTarget) {
        List<AiUserMemoryEntity> related = new ArrayList<>();
        if (hintedTarget != null) {
            related.add(hintedTarget);
        }
        List<AiUserMemoryEntity> matches = memoryMapper.selectList(new QueryWrapper<AiUserMemoryEntity>()
                .eq("user_id", candidate.userId)
                .eq("status", AiMemoryService.STATUS_ACTIVE)
                .orderByDesc("updated_at")
                .last("LIMIT 50"));
        Set<String> candidateTokens = semanticTokens(candidate.memoryKey + " " + candidate.canonicalText + " " + candidate.category);
        for (AiUserMemoryEntity match : matches) {
            if (match == null || match.getId() == null || related.stream().anyMatch(item -> item.getId() != null && item.getId().equals(match.getId()))) {
                continue;
            }
            if (relatedScore(candidate, candidateTokens, match) > 0) {
                related.add(match);
            }
        }
        if (related.size() <= 1) {
            matches.stream()
                    .filter(match -> match != null && match.getId() != null)
                    .filter(match -> related.stream().noneMatch(item -> item.getId() != null && item.getId().equals(match.getId())))
                    .limit(8)
                    .forEach(related::add);
        }
        return related.stream()
                .sorted(Comparator.comparingInt((AiUserMemoryEntity memory) -> relatedScore(candidate, candidateTokens, memory)).reversed())
                .limit(12)
                .toList();
    }

    private int relatedScore(AiMemoryCandidateEntity candidate, Set<String> candidateTokens, AiUserMemoryEntity memory) {
        int score = 0;
        if (memory == null) {
            return score;
        }
        if (legacyCategory(candidate.category).equals(normalize(memory.getCategory()))) {
            score += 3;
        }
        if (legacyMemoryType(candidate.category, candidate.memoryKey).equals(normalize(memory.getMemoryType()))) {
            score += 4;
        }
        Set<String> memoryTokens = semanticTokens(memory.getMemoryType() + " " + memory.getTitle() + " " + memory.getContent());
        for (String token : candidateTokens) {
            if (memoryTokens.contains(token)) {
                score += token.length() >= 2 ? 2 : 1;
            }
        }
        return score;
    }

    private AiUserMemoryEntity selectTargetMemory(Long userId, AiUserMemoryEntity hinted, List<AiUserMemoryEntity> related, Long modelTargetId) {
        if (modelTargetId != null) {
            AiUserMemoryEntity target = validateTargetMemory(userId, modelTargetId);
            if (target != null) {
                return target;
            }
        }
        if (hinted != null) {
            return hinted;
        }
        return related == null || related.isEmpty() ? null : related.get(0);
    }

    private AiMemoryCandidateEntity requireOwnedCandidate(Long userId, Long candidateId) {
        AiMemoryCandidateEntity candidate = candidateId == null ? null : candidateMapper.selectById(candidateId);
        if (candidate == null) {
            throw new DomainException(ErrorCode.NOT_FOUND, "AI memory candidate not found");
        }
        if (!userId.equals(candidate.userId)) {
            throw new DomainException(ErrorCode.FORBIDDEN, "AI memory candidate belongs to another user");
        }
        return candidate;
    }

    private AiUserMemoryEntity validateTargetMemory(Long userId, Long targetMemoryId) {
        if (targetMemoryId == null) {
            return null;
        }
        AiUserMemoryEntity target = memoryMapper.selectById(targetMemoryId);
        if (target == null) {
            throw new DomainException(ErrorCode.NOT_FOUND, "Target AI memory not found");
        }
        if (!userId.equals(target.getUserId())) {
            throw new DomainException(ErrorCode.FORBIDDEN, "Target AI memory belongs to another user");
        }
        return target;
    }

    private void validateTargetClaim(Long userId, AiUserMemoryEntity targetMemory, Long targetClaimId) {
        if (targetClaimId == null) {
            return;
        }
        AiMemoryClaimEntity claim = claimMapper.selectById(targetClaimId);
        if (claim == null) {
            throw new DomainException(ErrorCode.NOT_FOUND, "Target AI memory claim not found");
        }
        if (!userId.equals(claim.userId)
                || (targetMemory != null && claim.legacyMemoryId != null && !targetMemory.getId().equals(claim.legacyMemoryId))) {
            throw new DomainException(ErrorCode.FORBIDDEN, "Target AI memory claim does not match the target memory");
        }
    }

    private void index(AiUserMemoryEntity memory) {
        retrievalService.deleteOwner(memory.getUserId(), "memory", String.valueOf(memory.getId()));
        if (!AiMemoryService.STATUS_ACTIVE.equals(memory.getStatus())
                || !AiMemoryService.isRecallableMemory(memory.getMemoryType(), memory.getContent())) {
            return;
        }
        retrievalService.indexChunk(memory.getUserId(), "memory", String.valueOf(memory.getId()),
                "长期记忆：" + normalize(memory.getMemoryType()) + " - " + normalize(memory.getContent()));
    }

    private void supersedeMemory(
            Long userId,
            AiUserMemoryEntity memory,
            AiMemoryCandidateEntity candidate,
            MergeAction action,
            LocalDateTime now
    ) {
        retrievalService.deleteOwner(userId, "memory", String.valueOf(memory.getId()));
        memory.setStatus(STATUS_SUPERSEDED);
        memory.setSource(SOURCE_AI_MEMORY_MERGED);
        memory.setUpdatedAt(now);
        memoryMapper.updateById(memory);

        AiMemoryClaimEntity claim = claimMapper.selectOne(new QueryWrapper<AiMemoryClaimEntity>()
                .eq("user_id", userId)
                .eq("legacy_memory_id", memory.getId())
                .orderByDesc("updated_at")
                .last("LIMIT 1"));
        if (claim != null) {
            claim.status = STATUS_SUPERSEDED;
            claim.sourceMode = SOURCE_AI_MEMORY_MERGED;
            claim.version = Math.max(1, claim.version == null ? 1 : claim.version + 1);
            claim.updatedAt = now;
            claimMapper.updateById(claim);
            insertVersion(userId, claim, candidate.id, firstNonBlank(action.reason(), "memory_merge_superseded"), now);
            insertEvidenceIfAbsent(
                    userId,
                    claim.id,
                    candidate.id,
                    candidate.sourceConversationId,
                    candidate.sourceMessageId,
                    "MODEL_MERGE_SUPERSEDE",
                    firstNonBlank(action.reason(), candidate.canonicalText),
                    action.confidence(),
                    firstNonBlank(action.reason(), "memory_merge_superseded"),
                    now
            );
        }
    }

    private void supersedeEquivalentRelatedMemories(
            Long userId,
            AiUserMemoryEntity target,
            List<AiUserMemoryEntity> related,
            AiMemoryCandidateEntity candidate,
            MergeAction action,
            LocalDateTime now
    ) {
        if (target == null || target.getId() == null || related == null || related.isEmpty()) {
            return;
        }
        for (AiUserMemoryEntity duplicate : related) {
            if (!shouldAutoSupersedeAsEquivalent(target, duplicate)) {
                continue;
            }
            supersedeMemory(
                    userId,
                    duplicate,
                    candidate,
                    new MergeAction(
                            "SUPERSEDE",
                            duplicate.getId(),
                            action.memoryKey(),
                            action.category(),
                            action.memoryType(),
                            duplicate.getContent(),
                            action.confidence(),
                            0,
                            0,
                            List.of(firstNonBlank(action.reason(), candidate.canonicalText)),
                            STATUS_SUPERSEDED,
                            firstNonBlank(action.reason(), "memory_merge_duplicate_superseded")
                    ),
                    now
            );
        }
    }

    private boolean shouldAutoSupersedeAsEquivalent(AiUserMemoryEntity target, AiUserMemoryEntity duplicate) {
        if (target == null || duplicate == null
                || target.getId() == null || duplicate.getId() == null
                || target.getId().equals(duplicate.getId())) {
            return false;
        }
        if (!AiMemoryService.STATUS_ACTIVE.equals(duplicate.getStatus())) {
            return false;
        }
        if (!normalize(target.getCategory()).equals(normalize(duplicate.getCategory()))) {
            return false;
        }
        String targetContent = normalize(target.getContent());
        String duplicateContent = normalize(duplicate.getContent());
        if (targetContent.isBlank() || duplicateContent.isBlank()) {
            return false;
        }
        if (targetContent.equals(duplicateContent)) {
            return true;
        }
        if (hasNegationSignal(targetContent) != hasNegationSignal(duplicateContent)) {
            return false;
        }
        Set<String> targetTokens = semanticTokens(target.getMemoryType() + " " + target.getTitle() + " " + targetContent);
        Set<String> duplicateTokens = semanticTokens(duplicate.getMemoryType() + " " + duplicate.getTitle() + " " + duplicateContent);
        if (targetTokens.isEmpty() || duplicateTokens.isEmpty()) {
            return false;
        }
        long overlap = duplicateTokens.stream().filter(targetTokens::contains).count();
        double ratio = overlap / (double) Math.min(targetTokens.size(), duplicateTokens.size());
        return ratio >= 0.75;
    }

    private boolean hasNegationSignal(String value) {
        String normalized = normalize(value).toLowerCase(Locale.ROOT);
        return normalized.contains("不")
                || normalized.contains("不是")
                || normalized.contains("不想")
                || normalized.contains("不喜欢")
                || normalized.contains("not ")
                || normalized.contains("don't")
                || normalized.contains("do not")
                || normalized.contains("dislike")
                || normalized.contains("contradict")
                || normalized.contains("反证");
    }

    private AiMemoryResponse toMemoryResponse(AiUserMemoryEntity memory, String overrideStatus) {
        return new AiMemoryResponse(
                memory.getId(),
                memory.getCategory(),
                memory.getTitle(),
                memory.getMemoryType(),
                memory.getContent(),
                memory.getConfidence(),
                memory.getSource(),
                overrideStatus == null ? memory.getStatus() : overrideStatus,
                memory.getCreatedAt() == null ? null : memory.getCreatedAt().atZone(ZONE).toInstant(),
                memory.getUpdatedAt() == null ? null : memory.getUpdatedAt().atZone(ZONE).toInstant(),
                memory.getLastUsedAt() == null ? null : memory.getLastUsedAt().atZone(ZONE).toInstant()
        );
    }

    private String normalizeCandidateStatus(String requested, boolean created) {
        String normalized = normalize(requested).toUpperCase(Locale.ROOT);
        if ("NEEDS_CONFIRMATION".equals(normalized)) {
            return "NEEDS_CONFIRMATION";
        }
        if ("REJECTED".equals(normalized)) {
            return "REJECTED";
        }
        if ("ACTIVE".equals(normalized)) {
            return AiMemoryService.STATUS_ACTIVE;
        }
        if ("MERGED".equals(normalized)) {
            return STATUS_MERGED;
        }
        return created ? AiMemoryService.STATUS_ACTIVE : STATUS_MERGED;
    }

    private boolean isCandidateReviewable(String status) {
        return Set.of("CANDIDATE", "NEEDS_CONFIRMATION", "AWAITING_CLARIFICATION")
                .contains(normalizeStatus(status));
    }

    private String normalizeStatus(String status) {
        return normalize(status).toUpperCase(Locale.ROOT);
    }

    private String evidenceTypeFor(String decision) {
        return switch (decision) {
            case "WEAKEN" -> "MODEL_MERGE_CONTRADICTION";
            case "SPLIT_CREATE" -> "MODEL_MERGE_SPLIT";
            case "SUPERSEDE" -> "MODEL_MERGE_SUPERSEDE";
            case "MERGE", "REINFORCE" -> "MODEL_MERGE_SUPPORT";
            default -> "MODEL_MERGE_CREATE";
        };
    }

    private BigDecimal adjustedConfidence(BigDecimal current, MergeAction action) {
        double requested = Math.min(1.0, Math.max(0.0, action.confidence()));
        double value = current == null ? requested : current.doubleValue();
        if (action.contradictionDelta() > action.supportDelta()) {
            value = Math.min(value, requested) * 0.85;
        } else {
            value = Math.max(value, requested);
        }
        return BigDecimal.valueOf(Math.min(1, Math.max(0.05, value))).setScale(4, RoundingMode.HALF_UP);
    }

    private BigDecimal adjustStability(BigDecimal current, MergeAction action) {
        double value = current == null ? 0.75 : current.doubleValue();
        if (action.contradictionDelta() > action.supportDelta()) {
            value -= 0.10;
        } else {
            value += 0.05;
        }
        return BigDecimal.valueOf(Math.min(1, Math.max(0.10, value))).setScale(4, RoundingMode.HALF_UP);
    }

    private String normalizeMemoryStatus(String requested) {
        String normalized = normalize(requested).toUpperCase(Locale.ROOT);
        if (STATUS_SUPERSEDED.equals(normalized)) {
            return STATUS_SUPERSEDED;
        }
        if ("NEEDS_CONFIRMATION".equals(normalized)) {
            return "CANDIDATE";
        }
        return AiMemoryService.STATUS_ACTIVE;
    }

    /** V3 P2-7: a claim the user disabled (or whose memory was deleted) is distrusted. */
    private boolean isDistrustedClaimStatus(String status) {
        String normalized = normalize(status).toUpperCase(Locale.ROOT);
        return "DISABLED".equals(normalized) || "DELETED".equals(normalized);
    }

    /**
     * V3 P2-7: only an explicit user action (ACCEPT, clarification confirm/update, planner
     * resolution accept, confirmed-signal enqueue, ...) may reactivate a distrusted claim.
     * Automatic flows and missing/blank actions are treated as AUTO and never revive one.
     */
    private boolean isUserExplicitMergeAction(String mergeSourceAction) {
        String normalized = normalize(mergeSourceAction).toUpperCase(Locale.ROOT);
        return !normalized.isBlank() && !AUTO_MERGE_SOURCE_ACTIONS.contains(normalized);
    }

    private BigDecimal normalizeConfidence(BigDecimal value) {
        return (value == null ? BigDecimal.ONE : value)
                .max(BigDecimal.ZERO)
                .min(BigDecimal.ONE)
                .setScale(4, RoundingMode.HALF_UP);
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

    private String legacyMemoryType(String category, String key) {
        return switch (normalize(category).toUpperCase(Locale.ROOT)) {
            case "RULE" -> "rule";
            case "PREFERENCE" -> "guidance_preference";
            case "HABIT" -> "habit";
            case "WEAKNESS" -> "weakness";
            case "PROFILE" -> "name_preference";
            case "GOAL" -> "learning_direction";
            default -> truncate(normalizeKey(key), 48);
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
        return truncate(prefix + "：" + normalize(content).replaceAll("\\s+", " "), 160);
    }

    private String normalizeKey(String value) {
        String normalized = normalize(value).toLowerCase(Locale.ROOT)
                .replaceAll("[^a-z0-9_\\u4e00-\\u9fa5]+", "_")
                .replaceAll("_+", "_")
                .replaceAll("^_|_$", "");
        return normalized.isBlank() ? "manual_note" : truncate(normalized, 96);
    }

    private Set<String> semanticTokens(String value) {
        String normalized = normalize(value).toLowerCase(Locale.ROOT);
        Set<String> tokens = new LinkedHashSet<>();
        for (String token : normalized.split("[^a-z0-9\\u4e00-\\u9fa5]+")) {
            if (token.length() >= 2) {
                tokens.add(token);
            }
            if (token.length() > 4 && token.codePoints().anyMatch(code -> code >= 0x4e00 && code <= 0x9fa5)) {
                List<Integer> codePoints = token.codePoints().boxed().toList();
                for (int index = 0; index + 1 < codePoints.size(); index++) {
                    tokens.add(new String(new int[]{codePoints.get(index), codePoints.get(index + 1)}, 0, 2));
                }
            }
        }
        return tokens;
    }

    private String v2Category(String category) {
        return switch (normalize(category).toLowerCase(Locale.ROOT)) {
            case "rule" -> "RULE";
            case "preference" -> "PREFERENCE";
            case "habit" -> "HABIT";
            case "weakness" -> "WEAKNESS";
            default -> "MEMORY";
        };
    }

    private BigDecimal decimal(double value) {
        return BigDecimal.valueOf(Math.min(1.0, Math.max(0.0, value))).setScale(4, RoundingMode.HALF_UP);
    }

    private String safeText(String value) {
        return sanitizer.sanitizeText(value == null ? "" : value);
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

    private String normalize(String value) {
        return value == null ? "" : value.replace("\r\n", "\n").replace('\r', '\n').trim();
    }

    private String truncate(String value, int maxLength) {
        String normalized = normalize(value);
        return normalized.length() <= maxLength ? normalized : normalized.substring(0, maxLength);
    }

    private Long readLong(Object value) {
        if (value == null) {
            return null;
        }
        try {
            String normalized = normalize(String.valueOf(value));
            return normalized.isBlank() || "null".equalsIgnoreCase(normalized) ? null : Long.parseLong(normalized);
        } catch (NumberFormatException ignored) {
            return null;
        }
    }

    @SuppressWarnings("unchecked")
    private Map<String, Object> readObjectMap(String json) {
        if (json == null || json.isBlank()) {
            return Map.of();
        }
        try {
            return objectMapper.readValue(json, new TypeReference<Map<String, Object>>() {
            });
        } catch (Exception ignored) {
            return Map.of();
        }
    }

    private String toJson(Object value) {
        try {
            return objectMapper.writeValueAsString(sanitizer.sanitizePayload(value == null ? Map.of() : value));
        } catch (Exception ignored) {
            return "{}";
        }
    }

    private Map<String, String> message(String role, String content) {
        return Map.of("role", role, "content", content);
    }

    public record MergeEnqueueResult(
            AiMemoryCandidateEntity candidate,
            AiUserMemoryEntity targetMemory,
            AiMemoryJobEntity job
    ) {
    }

    private record MergeAction(
            String action,
            Long targetMemoryId,
            String memoryKey,
            String category,
            String memoryType,
            String content,
            double confidence,
            int supportDelta,
            int contradictionDelta,
            List<String> evidenceItems,
            String status,
            String reason
    ) {
    }

    private record MergeDecision(
            List<MergeAction> actions,
            String candidateStatus,
            String reviewReason
    ) {
    }
}
