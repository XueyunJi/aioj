package com.aioj.next.ai.domain;

import com.aioj.next.ai.config.AiProperties;
import com.aioj.next.ai.persistence.entity.AiUserMemoryEntity;
import com.aioj.next.ai.persistence.entity.AiLearningWeaknessEntity;
import com.aioj.next.ai.persistence.entity.AiMemoryClaimEntity;
import com.aioj.next.ai.persistence.entity.AiMemoryRecallLogEntity;
import com.aioj.next.ai.persistence.mapper.AiLearningWeaknessMapper;
import com.aioj.next.ai.persistence.mapper.AiUserMemoryMapper;
import com.aioj.next.ai.persistence.mapper.AiMemoryClaimMapper;
import com.aioj.next.ai.persistence.mapper.AiMemoryRecallLogMapper;
import com.aioj.next.ai.domain.memory.AiMemoryCandidateService;
import com.aioj.next.ai.domain.memory.AiMemoryMergeService;
import com.aioj.next.ai.domain.memory.MemoryQualityGate;
import com.aioj.next.common.error.DomainException;
import com.aioj.next.common.error.ErrorCode;
import com.aioj.next.contract.ai.AiMemoryExportResponse;
import com.aioj.next.contract.ai.AiMemoryImportRequest;
import com.aioj.next.contract.ai.AiMemoryImportResponse;
import com.aioj.next.contract.ai.AiMemoryCandidateActionRequest;
import com.aioj.next.contract.ai.AiMemoryResponse;
import com.aioj.next.contract.ai.AiMemoryUpsertRequest;
import com.aioj.next.contract.ai.StudentPostmortemWeaknessConfirmRequest;
import com.aioj.next.contract.ai.StudentPostmortemWeaknessConfirmResponse;
import com.baomidou.mybatisplus.core.conditions.query.QueryWrapper;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

@Service
public class AiMemoryService {
    private static final ZoneId ZONE = ZoneId.systemDefault();
    public static final String STATUS_ACTIVE = "ACTIVE";
    public static final String STATUS_DISABLED = "DISABLED";
    public static final String STATUS_CANDIDATE = "CANDIDATE";
    public static final String STATUS_RESOLVED = "RESOLVED";
    public static final String STATUS_SUPERSEDED = "SUPERSEDED";
    public static final String SOURCE_AI_EXTRACTED = "AI_EXTRACTED";
    public static final String SOURCE_AI_CANDIDATE = "AI_CANDIDATE";
    public static final String SOURCE_USER_MANUAL = "USER_MANUAL";
    public static final String SOURCE_USER_CONFIRMED = "USER_CONFIRMED";
    public static final String SOURCE_STUDENT_POSTMORTEM_CONFIRMED = "STUDENT_POSTMORTEM_CONFIRMED";
    private static final String EVIDENCE_EXPLICIT = "EXPLICIT_USER_PREFERENCE";
    private static final String EVIDENCE_REPEATED = "REPEATED_BEHAVIOR";
    private static final String CATEGORY_MEMORY = "memory";
    private static final String IMPORT_MODE_MERGE = "MERGE";
    private static final double MIN_MEMORY_CONFIDENCE = 0.85;
    private static final Pattern MEMORY_BLOCK = Pattern.compile(
            "(?s)<!--\\s*AIOJ_MEMORY\\s*(.*?)-->(.*?)<!--\\s*/AIOJ_MEMORY\\s*-->"
    );

    public static final Set<String> AUTO_MEMORY_TYPES = Set.of(
            "preferred_language",
            "skill_level",
            "teaching_style",
            "guidance_preference",
            "learning_direction",
            "weakness",
            "code_style",
            "debugging_preference",
            "pace_preference",
            "name_preference"
    );

    public static final Set<String> MEMORY_CATEGORIES = Set.of(
            CATEGORY_MEMORY,
            "habit",
            "rule",
            "preference",
            "weakness",
            "teaching_style"
    );

    private final AiUserMemoryMapper memoryMapper;
    private final AiProvider aiProvider;
    private final AiCapacityService aiCapacityService;
    private final AiRetrievalService retrievalService;
    private final MemoryQualityGate memoryQualityGate;
    private final AiMemoryCandidateService memoryCandidateService;
    private final AiMemoryClaimMapper memoryClaimMapper;
    private final AiMemoryRecallLogMapper memoryRecallLogMapper;
    private final AiLearningWeaknessMapper learningWeaknessMapper;
    private final AiProperties properties;

    public AiMemoryService(
            AiUserMemoryMapper memoryMapper,
            AiProvider aiProvider,
            AiCapacityService aiCapacityService,
            AiRetrievalService retrievalService,
            MemoryQualityGate memoryQualityGate,
            AiMemoryCandidateService memoryCandidateService,
            AiMemoryClaimMapper memoryClaimMapper,
            AiMemoryRecallLogMapper memoryRecallLogMapper,
            AiLearningWeaknessMapper learningWeaknessMapper,
            AiProperties properties
    ) {
        this.memoryMapper = memoryMapper;
        this.aiProvider = aiProvider;
        this.aiCapacityService = aiCapacityService;
        this.retrievalService = retrievalService;
        this.memoryQualityGate = memoryQualityGate;
        this.memoryCandidateService = memoryCandidateService;
        this.memoryClaimMapper = memoryClaimMapper;
        this.memoryRecallLogMapper = memoryRecallLogMapper;
        this.learningWeaknessMapper = learningWeaknessMapper;
        this.properties = properties;
    }

    public List<AiMemoryResponse> list(Long userId) {
        return list(userId, null, null, null);
    }

    public static boolean isActiveStatus(String status) {
        return STATUS_ACTIVE.equals(normalizeValue(status).toUpperCase(Locale.ROOT));
    }

    public static boolean isTerminalOrDisabledStatus(String status) {
        String normalized = normalizeValue(status).toUpperCase(Locale.ROOT);
        return STATUS_DISABLED.equals(normalized)
                || STATUS_RESOLVED.equals(normalized)
                || STATUS_SUPERSEDED.equals(normalized);
    }

    public static boolean isUserControlledSource(String source) {
        String normalized = normalizeValue(source).toUpperCase(Locale.ROOT);
        return SOURCE_USER_MANUAL.equals(normalized)
                || SOURCE_USER_CONFIRMED.equals(normalized)
                || SOURCE_STUDENT_POSTMORTEM_CONFIRMED.equals(normalized);
    }

    public List<AiMemoryResponse> list(Long userId, String category, String status, String keyword) {
        QueryWrapper<AiUserMemoryEntity> query = new QueryWrapper<AiUserMemoryEntity>()
                .eq("user_id", userId)
                .ne("status", STATUS_CANDIDATE)
                .orderByDesc("updated_at")
                .last("LIMIT 200");
        String normalizedCategory = normalizeCategory(category, false);
        String normalizedStatus = normalizeStatusFilter(status);
        String normalizedKeyword = normalizeValue(keyword).toLowerCase(Locale.ROOT);
        if (!normalizedCategory.isBlank()) {
            query.eq("category", normalizedCategory);
        }
        if (!normalizedStatus.isBlank()) {
            query.eq("status", normalizedStatus);
        }
        return memoryMapper.selectList(query)
                .stream()
                .filter(this::visibleInProfile)
                .filter(memory -> normalizedKeyword.isBlank() || memoryMatches(memory, normalizedKeyword))
                .map(this::toResponse)
                .toList();
    }

    public List<AiRetrievalService.AiRetrievalHit> filterRecallableRetrievalHits(
            Long userId,
            List<AiRetrievalService.AiRetrievalHit> hits
    ) {
        if (hits == null || hits.isEmpty()) {
            return List.of();
        }
        List<AiRetrievalService.AiRetrievalHit> filtered = new ArrayList<>();
        for (AiRetrievalService.AiRetrievalHit hit : hits) {
            if (hit == null) {
                continue;
            }
            if (!"memory".equals(hit.ownerType())) {
                filtered.add(hit);
                continue;
            }
            AiUserMemoryEntity memory = memoryMapper.selectById(parseLong(hit.ownerId()));
            if (memory != null && userId.equals(memory.getUserId()) && recallableMemory(memory)) {
                filtered.add(hit);
            }
        }
        return filtered;
    }

    @Transactional
    public AiMemoryResponse create(Long userId, AiMemoryUpsertRequest request) {
        AiUserMemoryEntity memory = new AiUserMemoryEntity();
        LocalDateTime now = LocalDateTime.now();
        memory.setUserId(userId);
        applyRequest(memory, request, true);
        memory.setSource(SOURCE_USER_MANUAL);
        memory.setCreatedAt(now);
        memory.setUpdatedAt(now);
        memoryMapper.insert(memory);
        index(memory);
        memoryCandidateService.syncManualClaim(memory, "manual_create");
        return toResponse(memory);
    }

    @Transactional
    public AiMemoryResponse update(Long userId, Long id, AiMemoryUpsertRequest request) {
        AiUserMemoryEntity memory = requireOwned(userId, id);
        deleteIndex(memory);
        applyRequest(memory, request, false);
        memory.setUpdatedAt(LocalDateTime.now());
        memoryMapper.updateById(memory);
        index(memory);
        memoryCandidateService.syncManualClaim(memory, "manual_update");
        return toResponse(memory);
    }

    @Transactional
    public void delete(Long userId, Long id) {
        AiUserMemoryEntity memory = requireOwned(userId, id);
        deleteIndex(memory);
        memoryCandidateService.markClaimStatus(memory, "DELETED", "manual_delete");
        memoryMapper.deleteById(memory.getId());
    }

    @Transactional
    public AiMemoryResponse disable(Long userId, Long id) {
        AiUserMemoryEntity memory = requireOwned(userId, id);
        deleteIndex(memory);
        memory.setStatus(STATUS_DISABLED);
        memory.setUpdatedAt(LocalDateTime.now());
        memoryMapper.updateById(memory);
        memoryCandidateService.markClaimStatus(memory, STATUS_DISABLED, "manual_disable");
        return toResponse(memory);
    }

    @Transactional
    public AiMemoryResponse enable(Long userId, Long id) {
        AiUserMemoryEntity memory = requireOwned(userId, id);
        memory.setStatus(STATUS_ACTIVE);
        memory.setUpdatedAt(LocalDateTime.now());
        memoryMapper.updateById(memory);
        index(memory);
        memoryCandidateService.syncManualClaim(memory, "manual_enable");
        return toResponse(memory);
    }

    @Transactional
    public StudentPostmortemWeaknessConfirmResponse confirmStudentPostmortemWeakness(StudentPostmortemWeaknessConfirmRequest request) {
        if (request == null || request.userId() == null) {
            throw new DomainException(ErrorCode.VALIDATION_FAILED, "User id is required");
        }
        String knowledgeNode = truncateForColumn(normalizeValue(request.knowledgeNode()), 128);
        String symptom = truncateForColumn(normalizeContent(request.symptom()), 500);
        if (knowledgeNode.isBlank() || symptom.isBlank()) {
            throw new DomainException(ErrorCode.VALIDATION_FAILED, "Weakness candidate content is required");
        }
        LocalDateTime now = LocalDateTime.now();
        AiLearningWeaknessEntity weakness = learningWeaknessMapper.selectOne(new QueryWrapper<AiLearningWeaknessEntity>()
                .eq("user_id", request.userId())
                .eq("knowledge_node", knowledgeNode)
                .last("LIMIT 1"));
        if (weakness == null) {
            weakness = new AiLearningWeaknessEntity();
            weakness.userId = request.userId();
            weakness.knowledgeNode = knowledgeNode;
            weakness.symptom = symptom;
            weakness.tags = jsonArray(safeList(request.tags(), 8));
            weakness.masteryScore = BigDecimal.valueOf(0.45);
            weakness.weakSignalCount = 1;
            weakness.recentErrorCount = 1;
            weakness.teachingBoost = BigDecimal.valueOf(1.15);
            weakness.evidenceJson = jsonArray(safeList(request.evidence(), 12));
            weakness.status = STATUS_ACTIVE;
            weakness.lastEvidenceAt = now;
            weakness.createdAt = now;
            weakness.updatedAt = now;
            learningWeaknessMapper.insert(weakness);
        } else {
            weakness.symptom = symptom;
            weakness.tags = jsonArray(safeList(request.tags(), 8));
            weakness.weakSignalCount = weakness.weakSignalCount == null ? 1 : weakness.weakSignalCount + 1;
            weakness.recentErrorCount = weakness.recentErrorCount == null ? 1 : weakness.recentErrorCount + 1;
            weakness.teachingBoost = BigDecimal.valueOf(1.15);
            weakness.evidenceJson = jsonArray(safeList(request.evidence(), 12));
            weakness.status = STATUS_ACTIVE;
            weakness.lastEvidenceAt = now;
            weakness.updatedAt = now;
            learningWeaknessMapper.updateById(weakness);
        }
        AiMemoryMergeService.MergeEnqueueResult merge = memoryCandidateService.enqueueConfirmedMemory(
                request.userId(),
                new AiMemoryCandidateService.ConfirmedMemoryCandidateRequest(
                        "weakness",
                        knowledgeNode,
                        studentPostmortemWeaknessMarkdown(request, knowledgeNode, symptom),
                        "{}",
                        "STUDENT_POSTMORTEM_WEAKNESS",
                        request.candidateId() == null ? null : String.valueOf(request.candidateId()),
                        SOURCE_STUDENT_POSTMORTEM_CONFIRMED,
                        String.join("\n", safeList(request.evidence(), 12)),
                        "student_postmortem_confirmed",
                        Math.min(1.0, Math.max(0.0, request.confidence()))
                ));
        return new StudentPostmortemWeaknessConfirmResponse(
                null,
                weakness.id,
                merge.candidate() == null ? null : merge.candidate().id,
                merge.job() == null ? null : merge.job().getId(),
                AiMemoryMergeService.STATUS_MERGE_QUEUED
        );
    }

    public AiMemoryExportResponse exportMarkdown(Long userId) {
        List<AiUserMemoryEntity> memories = memoryMapper.selectList(new QueryWrapper<AiUserMemoryEntity>()
                .eq("user_id", userId)
                .ne("status", STATUS_CANDIDATE)
                .orderByAsc("category")
                .orderByDesc("updated_at")
                .last("LIMIT 500"));
        StringBuilder markdown = new StringBuilder();
        markdown.append("# AI-OJ Long-Term Memory\n\n");
        markdown.append("> Edit memory content as Markdown. Keep the AIOJ_MEMORY comments if you want uploads to update existing records.\n\n");
        String currentCategory = "";
        for (AiUserMemoryEntity memory : memories) {
            if (!visibleInProfile(memory)) {
                continue;
            }
            String category = normalizeCategory(memory.getCategory(), true);
            if (!category.equals(currentCategory)) {
                currentCategory = category;
                markdown.append("## ").append(categoryLabel(category)).append("\n\n");
            }
            appendMemoryBlock(markdown, memory);
        }
        String fileName = "aioj-memory-" + DateTimeFormatter.ofPattern("yyyyMMdd-HHmmss").format(LocalDateTime.now()) + ".md";
        return new AiMemoryExportResponse(fileName, markdown.toString());
    }

    @Transactional
    public AiMemoryImportResponse importMarkdown(Long userId, AiMemoryImportRequest request) {
        String mode = normalizeValue(request.mode()).toUpperCase(Locale.ROOT);
        if (!mode.isBlank() && !IMPORT_MODE_MERGE.equals(mode)) {
            throw new DomainException(ErrorCode.VALIDATION_FAILED, "Only MERGE import mode is supported");
        }
        List<ImportedMemory> imported = parseMarkdown(request.markdown());
        int created = 0;
        int updated = 0;
        List<AiMemoryResponse> responses = new ArrayList<>();
        for (ImportedMemory item : imported) {
            AiUserMemoryEntity memory = findImportTarget(userId, item);
            if (memory == null) {
                memory = new AiUserMemoryEntity();
                memory.setUserId(userId);
                memory.setSource(SOURCE_USER_MANUAL);
                memory.setCreatedAt(LocalDateTime.now());
                applyImported(memory, item);
                memoryMapper.insert(memory);
                created++;
            } else {
                deleteIndex(memory);
                applyImported(memory, item);
                memoryMapper.updateById(memory);
                updated++;
            }
            index(memory);
            memoryCandidateService.syncManualClaim(memory, "markdown_import");
            responses.add(toResponse(memory));
        }
        return new AiMemoryImportResponse(created, updated, responses);
    }

    public String recallForContext(Long userId, String query) {
        String normalizedQuery = normalizeContent(query);
        List<AiUserMemoryEntity> candidates = memoryMapper.selectList(new QueryWrapper<AiUserMemoryEntity>()
                .eq("user_id", userId)
                .eq("status", STATUS_ACTIVE)
                .orderByDesc("updated_at")
                .last("LIMIT 120"));
        if (candidates.isEmpty()) {
            return "";
        }
        Set<String> semanticHits = new HashSet<>(retrievalService.search(userId, normalizedQuery, List.of("memory"), 24)
                .stream()
                .map(this::normalizeForMatch)
                .toList());
        LocalDateTime now = LocalDateTime.now();
        AiProperties.Recall recallConfig = properties.getRecall();
        Map<Long, List<AiMemoryClaimEntity>> claimsByMemory = claimsByLegacyMemory(userId, candidates);
        Set<Long> coolingDown = recentlyRecalledMemoryIds(userId, candidates, now, recallConfig);
        List<ScoredMemory> scored = candidates.stream()
                .filter(this::recallableMemory)
                .filter(memory -> !claimsExpired(claimsByMemory.get(memory.getId()), now))
                .map(memory -> new ScoredMemory(memory, recallScore(
                        memory,
                        normalizedQuery,
                        semanticHits,
                        coolingDown.contains(memory.getId()),
                        hasConfirmedClaim(claimsByMemory.get(memory.getId())),
                        recallConfig)))
                .filter(item -> item.score() > 0)
                .sorted(Comparator.comparingDouble(ScoredMemory::score).reversed())
                .limit(12)
                .toList();
        if (scored.isEmpty()) {
            return "";
        }
        Map<Long, Long> claimIdsByLegacy = firstClaimIdsByLegacy(claimsByMemory);
        logPromptRecall(userId, scored, claimIdsByLegacy);
        LocalDateTime usedAt = LocalDateTime.now();
        Map<String, List<String>> sections = new LinkedHashMap<>();
        for (ScoredMemory item : scored) {
            AiUserMemoryEntity memory = item.memory();
            memory.setLastUsedAt(usedAt);
            memoryMapper.updateById(memory);
            sections.computeIfAbsent(contextSection(memory), ignored -> new ArrayList<>()).add(memoryContextLine(memory));
        }
        StringBuilder block = new StringBuilder();
        sections.forEach((section, lines) -> {
            if (!block.isEmpty()) {
                block.append("\n\n");
            }
            block.append(section).append('\n');
            for (String line : lines) {
                block.append(line).append('\n');
            }
        });
        return block.toString().trim();
    }

    private String memoryContextLine(AiUserMemoryEntity memory) {
        StringBuilder line = new StringBuilder();
        line.append("- [")
                .append(normalizeCategory(memory.getCategory(), true))
                .append("/")
                .append(normalizeValue(memory.getMemoryType()))
                .append("] ");
            String title = normalizeValue(memory.getTitle());
            if (!title.isBlank()) {
                line.append(title).append(": ");
            }
        line.append(truncate(stripMarkdown(memory.getContent()), 420));
        return line.toString();
    }

    private String studentPostmortemWeaknessMarkdown(StudentPostmortemWeaknessConfirmRequest request,
                                                     String knowledgeNode,
                                                     String symptom) {
        StringBuilder markdown = new StringBuilder();
        markdown.append("## ").append(knowledgeNode).append("\n\n");
        markdown.append(symptom).append("\n\n");
        List<String> tags = safeList(request.tags(), 8);
        if (!tags.isEmpty()) {
            markdown.append("- 标签：").append(String.join(", ", tags)).append("\n");
        }
        markdown.append("- 来源：学生确认的赛后个人复盘候选\n");
        markdown.append("- 比赛：#").append(request.contestId()).append(" / 运行 #").append(request.contestRunId()).append("\n");
        List<String> evidence = safeList(request.evidence(), 12);
        if (!evidence.isEmpty()) {
            markdown.append("\n### 证据\n");
            for (String item : evidence) {
                markdown.append("- ").append(item).append("\n");
            }
        }
        return markdown.toString().trim();
    }

    private List<String> safeList(List<String> values, int limit) {
        if (values == null || values.isEmpty()) {
            return List.of();
        }
        return values.stream()
                .map(AiMemoryService::normalizeContent)
                .filter(value -> !value.isBlank())
                .map(value -> truncate(value, 240))
                .distinct()
                .limit(limit)
                .toList();
    }

    private String jsonArray(List<String> values) {
        StringBuilder json = new StringBuilder("[");
        for (int i = 0; i < values.size(); i++) {
            if (i > 0) {
                json.append(',');
            }
            json.append('"').append(values.get(i).replace("\\", "\\\\").replace("\"", "\\\"")).append('"');
        }
        json.append(']');
        return json.toString();
    }

    private String contextSection(AiUserMemoryEntity memory) {
        String category = normalizeCategory(memory.getCategory(), true);
        String type = normalizeValue(memory.getMemoryType()).toLowerCase(Locale.ROOT);
        if ("rule".equals(category) || "rule".equals(type)) {
            return "[User Rules - Must Follow]";
        }
        if ("weakness".equals(category) || "weakness".equals(type)) {
            return "[Weakness Focus]";
        }
        if (Set.of("preference", "habit", "teaching_style").contains(category)
                || containsAny(type, "preference", "style", "habit", "language", "pace", "debugging", "code_style")) {
            return "[Relevant Preferences]";
        }
        if (containsAny(type, "name_preference", "skill_level", "learning_direction")
                || "memory".equals(category)) {
            return "[User Profile / Learning Goals]";
        }
        return "[Retrieved Long-Term Memory]";
    }

    /**
     * Loads the claims linked to the candidate memories in one query and groups
     * them by legacy memory id. Memories without claims are simply absent from
     * the map; callers must treat that as "no claim information" (null-safe).
     */
    private Map<Long, List<AiMemoryClaimEntity>> claimsByLegacyMemory(Long userId, List<AiUserMemoryEntity> candidates) {
        Set<Long> ids = new HashSet<>();
        for (AiUserMemoryEntity memory : candidates) {
            if (memory.getId() != null) {
                ids.add(memory.getId());
            }
        }
        if (ids.isEmpty()) {
            return Map.of();
        }
        List<AiMemoryClaimEntity> claims = memoryClaimMapper.selectList(new QueryWrapper<AiMemoryClaimEntity>()
                .eq("user_id", userId)
                .in("legacy_memory_id", ids));
        Map<Long, List<AiMemoryClaimEntity>> grouped = new HashMap<>();
        if (claims == null) {
            return grouped;
        }
        for (AiMemoryClaimEntity claim : claims) {
            if (claim != null && claim.legacyMemoryId != null) {
                grouped.computeIfAbsent(claim.legacyMemoryId, ignored -> new ArrayList<>()).add(claim);
            }
        }
        return grouped;
    }

    /**
     * W1.5 expiry filter: a memory is excluded only when it has at least one
     * claim and every linked claim has a non-null expires_at in the past.
     * Memories without claims, or with any claim that never expires
     * (expires_at IS NULL), stay recallable.
     */
    private boolean claimsExpired(List<AiMemoryClaimEntity> claims, LocalDateTime now) {
        if (claims == null || claims.isEmpty()) {
            return false;
        }
        for (AiMemoryClaimEntity claim : claims) {
            if (claim == null || claim.expiresAt == null || !claim.expiresAt.isBefore(now)) {
                return false;
            }
        }
        return true;
    }

    private boolean hasConfirmedClaim(List<AiMemoryClaimEntity> claims) {
        if (claims == null) {
            return false;
        }
        for (AiMemoryClaimEntity claim : claims) {
            if (claim != null && claim.lastConfirmedAt != null) {
                return true;
            }
        }
        return false;
    }

    /**
     * W1.5 cooldown: memory ids that already appeared in recall logs within
     * the configured window get a negative score adjustment this turn,
     * replacing the old last_used_at positive feedback.
     */
    private Set<Long> recentlyRecalledMemoryIds(
            Long userId,
            List<AiUserMemoryEntity> candidates,
            LocalDateTime now,
            AiProperties.Recall config
    ) {
        if (config.getCooldownWindowMinutes() <= 0 || config.getCooldownPenalty() <= 0) {
            return Set.of();
        }
        Set<Long> ids = new HashSet<>();
        for (AiUserMemoryEntity memory : candidates) {
            if (memory.getId() != null) {
                ids.add(memory.getId());
            }
        }
        if (ids.isEmpty()) {
            return Set.of();
        }
        List<AiMemoryRecallLogEntity> logs = memoryRecallLogMapper.selectList(new QueryWrapper<AiMemoryRecallLogEntity>()
                .select("legacy_memory_id")
                .eq("user_id", userId)
                .in("legacy_memory_id", ids)
                .ge("created_at", now.minusMinutes(config.getCooldownWindowMinutes()))
                .last("LIMIT 500"));
        if (logs == null || logs.isEmpty()) {
            return Set.of();
        }
        Set<Long> recent = new HashSet<>();
        for (AiMemoryRecallLogEntity log : logs) {
            if (log != null && log.legacyMemoryId != null) {
                recent.add(log.legacyMemoryId);
            }
        }
        return recent;
    }

    private Map<Long, Long> firstClaimIdsByLegacy(Map<Long, List<AiMemoryClaimEntity>> claimsByMemory) {
        Map<Long, Long> values = new HashMap<>();
        claimsByMemory.forEach((memoryId, claims) -> {
            for (AiMemoryClaimEntity claim : claims) {
                if (claim.id != null) {
                    values.putIfAbsent(memoryId, claim.id);
                    break;
                }
            }
        });
        return values;
    }

    private void logPromptRecall(Long userId, List<ScoredMemory> scored, Map<Long, Long> claimIdsByLegacy) {
        LocalDateTime now = LocalDateTime.now();
        for (ScoredMemory item : scored) {
            AiMemoryRecallLogEntity log = new AiMemoryRecallLogEntity();
            log.userId = userId;
            log.legacyMemoryId = item.memory().getId();
            log.claimId = claimIdsByLegacy.get(item.memory().getId());
            log.recallScore = BigDecimal.valueOf(item.score()).setScale(4, RoundingMode.HALF_UP);
            log.selected = Boolean.TRUE;
            log.usedInPrompt = Boolean.TRUE;
            log.reasonJson = "[\"prompt_recall\"]";
            log.createdAt = now;
            memoryRecallLogMapper.insert(log);
        }
    }

    @Transactional
    public int extractAndSave(
            Long userId,
            String conversationId,
            Long sourceMessageId,
            String userMessage,
            String assistantMessage
    ) {
        Map<String, AiCompletion.MemorySignal> signals = new LinkedHashMap<>();
        try {
            addSignals(signals, aiCapacityService.call(
                    AiCapacityService.AiWorkload.INTENT_MEMORY,
                    () -> aiProvider.extractMemories(userMessage, assistantMessage)
            ));
        } catch (DomainException ex) {
            if (ex.errorCode() == ErrorCode.TOO_MANY_REQUESTS) {
                return 0;
            }
            throw ex;
        }

        int activated = 0;
        for (AiCompletion.MemorySignal signal : signals.values()) {
            MemoryQualityGate.MemoryCandidate candidate = toCandidate(signal);
            MemoryQualityGate.GateResult gate = memoryQualityGate.evaluate(candidate,
                    new MemoryQualityGate.MessageContext(userMessage, assistantMessage));
            gate = applyConflictReview(userId, signal, gate);
            var candidateEntity = memoryCandidateService.recordExtraction(
                    userId,
                    conversationId,
                    sourceMessageId,
                    signal,
                    candidate,
                    gate,
                    userMessage
            );
            if (!gate.accepted() || !STATUS_ACTIVE.equals(gate.status()) || !isStorable(signal)) {
                continue;
            }
            memoryCandidateService.accept(userId, candidateEntity.id, new AiMemoryCandidateActionRequest(
                    candidateEntity.category,
                    null,
                    candidateEntity.memoryKey,
                    candidateEntity.canonicalText,
                    "auto_memory_extraction"
            ));
            activated++;
        }
        return activated;
    }

    private MemoryQualityGate.GateResult applyConflictReview(
            Long userId,
            AiCompletion.MemorySignal signal,
            MemoryQualityGate.GateResult gate
    ) {
        if (gate == null || !gate.accepted()) {
            return gate;
        }
        String type = normalizeValue(signal.type()).toLowerCase(Locale.ROOT);
        String content = normalizeForMatch(signal.content());
        if (type.isBlank() || content.isBlank()) {
            return gate;
        }
        AiUserMemoryEntity existing = memoryMapper.selectOne(new QueryWrapper<AiUserMemoryEntity>()
                .eq("user_id", userId)
                .eq("memory_type", type)
                .eq("status", STATUS_ACTIVE)
                .last("LIMIT 1"));
        if (existing == null || normalizeForMatch(existing.getContent()).equals(content)) {
            return gate;
        }
        List<String> ambiguityFlags = new ArrayList<>(gate.ambiguityFlags());
        if (!ambiguityFlags.contains("conflict_with_active_memory")) {
            ambiguityFlags.add("conflict_with_active_memory");
        }
        return new MemoryQualityGate.GateResult(
                true,
                true,
                gate.normalizedCategory(),
                gate.normalizedKey(),
                gate.scopeType(),
                gate.scopeId(),
                Math.min(gate.writeScore(), 0.6900),
                gate.qualityFlags(),
                List.copyOf(ambiguityFlags),
                "",
                "NEEDS_CONFIRMATION"
        );
    }

    private void addSignals(Map<String, AiCompletion.MemorySignal> target, List<AiCompletion.MemorySignal> signals) {
        if (signals == null) {
            return;
        }
        for (AiCompletion.MemorySignal signal : signals) {
            if (signal == null) {
                continue;
            }
            String key = normalizeValue(signal.type()).toLowerCase(Locale.ROOT) + "|" + normalizeContent(signal.content());
            if (!key.isBlank()) {
                target.putIfAbsent(key, signal);
            }
        }
    }

    private MemoryQualityGate.MemoryCandidate toCandidate(AiCompletion.MemorySignal signal) {
        String type = normalizeValue(signal.type()).toLowerCase(Locale.ROOT);
        String content = normalizeContent(signal.content());
        String evidenceType = normalizeValue(signal.evidenceType()).toUpperCase(Locale.ROOT);
        boolean explicit = EVIDENCE_EXPLICIT.equals(evidenceType) || "EXPLICIT_REMEMBER".equals(evidenceType) || "EXPLICIT_PREFERENCE".equals(evidenceType);
        boolean repeated = EVIDENCE_REPEATED.equals(evidenceType) || "REPEATED_BEHAVIOR".equals(evidenceType);
        boolean problemSpecific = containsAny(content, "本题", "这道题", "该题", "当前题", "样例", "输入格式", "输出格式");
        boolean hypothetical = containsAny(content, "假设", "假如", "如果我", "比如我", "角色扮演");
        boolean quoted = containsAny(content, "题目里", "老师说", "别人说", "引用");
        return new MemoryQualityGate.MemoryCandidate(
                "",
                type,
                content,
                null,
                problemSpecific ? "PROBLEM" : "GLOBAL",
                null,
                evidenceType,
                signal.confidence(),
                explicit || repeated,
                problemSpecific,
                hypothetical,
                quoted,
                "name_preference".equals(type) && !explicit
        );
    }

    private MemoryUpsertResult upsert(Long userId, String conversationId, Long sourceMessageId, AiCompletion.MemorySignal signal) {
        LocalDateTime now = LocalDateTime.now();
        String type = normalizeValue(signal.type()).toLowerCase(Locale.ROOT);
        String content = normalizeContent(signal.content());
        String evidenceType = normalizeValue(signal.evidenceType()).toUpperCase(Locale.ROOT);
        AiUserMemoryEntity existing = memoryMapper.selectOne(new QueryWrapper<AiUserMemoryEntity>()
                .eq("user_id", userId)
                .eq("memory_type", type)
                .eq("content", content)
                .last("LIMIT 1"));

        if (existing != null && isTerminalOrDisabledStatus(existing.getStatus())) {
            return new MemoryUpsertResult(false, existing);
        }

        if (EVIDENCE_EXPLICIT.equals(evidenceType)) {
            if (existing == null) {
                AiUserMemoryEntity inserted = insertMemory(userId, conversationId, sourceMessageId, signal, STATUS_ACTIVE, SOURCE_AI_EXTRACTED, now);
                return new MemoryUpsertResult(true, inserted);
            }
            deleteIndex(existing);
            existing.setCategory(categoryForType(type));
            existing.setTitle(titleForContent(type, content));
            existing.setConfidence(maxConfidence(existing, signal.confidence()));
            existing.setSource(SOURCE_AI_EXTRACTED);
            existing.setStatus(STATUS_ACTIVE);
            existing.setUpdatedAt(now);
            memoryMapper.updateById(existing);
            index(existing);
            return new MemoryUpsertResult(false, existing);
        }

        if (EVIDENCE_REPEATED.equals(evidenceType)) {
            if (existing == null) {
                AiUserMemoryEntity inserted = insertMemory(userId, conversationId, sourceMessageId, signal, STATUS_CANDIDATE, SOURCE_AI_CANDIDATE, now);
                return new MemoryUpsertResult(false, inserted);
            }
            if (STATUS_CANDIDATE.equals(existing.getStatus())) {
                deleteIndex(existing);
                existing.setCategory(categoryForType(type));
                existing.setTitle(titleForContent(type, content));
                existing.setConfidence(maxConfidence(existing, signal.confidence()));
                existing.setSource(SOURCE_AI_EXTRACTED);
                existing.setStatus(STATUS_ACTIVE);
                existing.setUpdatedAt(now);
                memoryMapper.updateById(existing);
                index(existing);
                return new MemoryUpsertResult(true, existing);
            }
            existing.setConfidence(maxConfidence(existing, signal.confidence()));
            existing.setUpdatedAt(now);
            memoryMapper.updateById(existing);
            return new MemoryUpsertResult(false, existing);
        }
        return new MemoryUpsertResult(false, existing);
    }

    private AiUserMemoryEntity insertMemory(
            Long userId,
            String conversationId,
            Long sourceMessageId,
            AiCompletion.MemorySignal signal,
            String status,
            String source,
            LocalDateTime now
    ) {
        String type = normalizeValue(signal.type()).toLowerCase(Locale.ROOT);
        String content = normalizeContent(signal.content());
        AiUserMemoryEntity memory = new AiUserMemoryEntity();
        memory.setUserId(userId);
        memory.setCategory(categoryForType(type));
        memory.setTitle(titleForContent(type, content));
        memory.setMemoryType(type);
        memory.setContent(content);
        memory.setConfidence(BigDecimal.valueOf(Math.min(1, Math.max(0, signal.confidence()))));
        memory.setSourceConversationId(conversationId);
        memory.setSourceMessageId(sourceMessageId);
        memory.setSource(source);
        memory.setStatus(status);
        memory.setCreatedAt(now);
        memory.setUpdatedAt(now);
        memoryMapper.insert(memory);
        index(memory);
        return memory;
    }

    private void applyRequest(AiUserMemoryEntity memory, AiMemoryUpsertRequest request, boolean create) {
        String content = normalizeContent(request.content());
        String memoryType = normalizeMemoryType(request.memoryType(), request.category());
        String category = normalizeCategory(request.category(), true);
        memory.setCategory(category);
        memory.setTitle(normalizeTitle(request.title(), memoryType, content));
        memory.setMemoryType(memoryType);
        memory.setContent(content);
        memory.setConfidence(request.confidence() == null ? (create ? BigDecimal.ONE : memory.getConfidence()) : request.confidence());
        memory.setStatus(normalizeStatus(request.status()));
    }

    private void applyImported(AiUserMemoryEntity memory, ImportedMemory item) {
        LocalDateTime now = LocalDateTime.now();
        memory.setCategory(item.category());
        memory.setTitle(item.title());
        memory.setMemoryType(item.memoryType());
        memory.setContent(item.content());
        memory.setConfidence(item.confidence());
        memory.setStatus(item.status());
        memory.setUpdatedAt(now);
        if (memory.getCreatedAt() == null) {
            memory.setCreatedAt(now);
        }
    }

    private AiUserMemoryEntity findImportTarget(Long userId, ImportedMemory item) {
        if (item.id() != null) {
            AiUserMemoryEntity owned = memoryMapper.selectById(item.id());
            if (owned != null && userId.equals(owned.getUserId())) {
                return owned;
            }
        }
        return memoryMapper.selectOne(new QueryWrapper<AiUserMemoryEntity>()
                .eq("user_id", userId)
                .eq("category", item.category())
                .eq("title", item.title())
                .eq("content", item.content())
                .last("LIMIT 1"));
    }

    private List<ImportedMemory> parseMarkdown(String markdown) {
        String normalized = normalizeContent(markdown);
        List<ImportedMemory> items = new ArrayList<>();
        Matcher matcher = MEMORY_BLOCK.matcher(normalized);
        while (matcher.find()) {
            Map<String, String> meta = parseMeta(matcher.group(1));
            String content = normalizeContent(matcher.group(2));
            if (content.isBlank()) {
                continue;
            }
            items.add(new ImportedMemory(
                    parseLong(meta.get("id")),
                    normalizeCategory(meta.get("category"), true),
                    normalizeTitle(meta.get("title"), normalizeMemoryType(meta.get("type"), meta.get("category")), content),
                    normalizeMemoryType(meta.get("type"), meta.get("category")),
                    content,
                    parseConfidence(meta.get("confidence")),
                    normalizeStatus(meta.get("status"))
            ));
        }
        if (items.isEmpty() && !normalized.isBlank()) {
            items.add(new ImportedMemory(
                    null,
                    CATEGORY_MEMORY,
                    "Imported memory",
                    "manual_note",
                    normalized,
                    BigDecimal.ONE,
                    STATUS_ACTIVE
            ));
        }
        return items;
    }

    private Map<String, String> parseMeta(String block) {
        Map<String, String> meta = new HashMap<>();
        for (String line : block.split("\\R")) {
            int separator = line.indexOf(':');
            if (separator <= 0) {
                continue;
            }
            String key = line.substring(0, separator).trim().toLowerCase(Locale.ROOT);
            String value = line.substring(separator + 1).trim();
            if (!key.isBlank()) {
                meta.put(key, value);
            }
        }
        return meta;
    }

    private void appendMemoryBlock(StringBuilder markdown, AiUserMemoryEntity memory) {
        markdown.append("<!-- AIOJ_MEMORY\n")
                .append("id: ").append(memory.getId()).append("\n")
                .append("category: ").append(normalizeCategory(memory.getCategory(), true)).append("\n")
                .append("title: ").append(escapeMeta(normalizeTitle(memory.getTitle(), memory.getMemoryType(), memory.getContent()))).append("\n")
                .append("type: ").append(normalizeValue(memory.getMemoryType())).append("\n")
                .append("status: ").append(normalizeStatus(memory.getStatus())).append("\n")
                .append("confidence: ").append(formatConfidence(memory.getConfidence())).append("\n")
                .append("-->\n\n")
                .append(normalizeContent(memory.getContent()))
                .append("\n\n<!-- /AIOJ_MEMORY -->\n\n");
    }

    private BigDecimal maxConfidence(AiUserMemoryEntity existing, double confidence) {
        double current = existing.getConfidence() == null ? 0 : existing.getConfidence().doubleValue();
        return BigDecimal.valueOf(Math.min(1, Math.max(current, confidence)));
    }

    private boolean isStorable(AiCompletion.MemorySignal signal) {
        String type = normalizeValue(signal.type()).toLowerCase(Locale.ROOT);
        String content = normalizeContent(signal.content());
        String evidenceType = normalizeValue(signal.evidenceType()).toUpperCase(Locale.ROOT);
        return signal.confidence() >= MIN_MEMORY_CONFIDENCE
                && (EVIDENCE_EXPLICIT.equals(evidenceType) || EVIDENCE_REPEATED.equals(evidenceType))
                && isAcceptedAutoType(type)
                && !content.isBlank()
                && content.length() <= 1000
                && !looksSensitive(content)
                && !looksLikeNoisyMemory(type, content);
    }

    private boolean visibleInProfile(AiUserMemoryEntity memory) {
        if (STATUS_CANDIDATE.equals(memory.getStatus())) {
            return false;
        }
        if (SOURCE_AI_EXTRACTED.equals(memory.getSource()) || SOURCE_AI_CANDIDATE.equals(memory.getSource())) {
            return isRecallableMemory(memory.getMemoryType(), memory.getContent());
        }
        return memory.getContent() != null && !memory.getContent().isBlank();
    }

    public static boolean isRecallableMemory(String type, String content) {
        String normalizedType = normalizeValue(type).toLowerCase(Locale.ROOT);
        String normalizedContent = normalizeContent(content);
        return !normalizedContent.isBlank()
                && normalizedContent.length() <= 5000
                && (isAcceptedAutoType(normalizedType) || isManualMemoryType(normalizedType))
                && !looksLikeNoisyMemory(normalizedType, normalizedContent);
    }

    public static boolean isRecallableMemoryChunk(String chunkText) {
        String normalized = normalizeValue(chunkText);
        String prefix = "长期记忆：";
        if (!normalized.startsWith(prefix)) {
            return true;
        }
        String body = normalized.substring(prefix.length());
        int separator = body.indexOf(" - ");
        if (separator < 0) {
            return false;
        }
        return isRecallableMemory(body.substring(0, separator), body.substring(separator + 3));
    }

    public static boolean isAcceptedAutoType(String type) {
        return AUTO_MEMORY_TYPES.contains(normalizeValue(type).toLowerCase(Locale.ROOT));
    }

    private static boolean isManualMemoryType(String type) {
        return Set.of("manual_note", "habit", "rule", "guidance_preference", "weakness", "teaching_style")
                .contains(normalizeValue(type).toLowerCase(Locale.ROOT));
    }

    private boolean recallableMemory(AiUserMemoryEntity memory) {
        if (!STATUS_ACTIVE.equals(memory.getStatus())) {
            return false;
        }
        if (SOURCE_AI_EXTRACTED.equals(memory.getSource()) || SOURCE_AI_CANDIDATE.equals(memory.getSource())) {
            return isRecallableMemory(memory.getMemoryType(), memory.getContent());
        }
        String content = stripMarkdown(memory.getContent());
        return content.length() >= 4 && content.length() <= 5000 && !looksLikeNoisyMemory(memory.getMemoryType(), content);
    }

    private double recallScore(
            AiUserMemoryEntity memory,
            String query,
            Set<String> semanticHits,
            boolean recentlyRecalled,
            boolean confirmed,
            AiProperties.Recall config
    ) {
        String text = normalizeForMatch(memory.getMemoryType() + "\n" + memory.getTitle() + "\n" + memory.getContent());
        double relevance = 0;
        if (semanticHits.contains(normalizeForMatch(memoryChunkText(memory)))) {
            relevance += 2.0;
        }
        for (String word : query.toLowerCase(Locale.ROOT).split("\\s+|[，。！？、；：,.!?;:]")) {
            if (word.length() >= 2 && text.contains(word)) {
                relevance += 0.16;
            }
        }
        String category = normalizeCategory(memory.getCategory(), true);
        if (query.toLowerCase(Locale.ROOT).contains(category)) {
            relevance += 0.3;
        }
        if (relevance <= 0) {
            return 0;
        }
        double score = relevance;
        if ("rule".equals(category) || "preference".equals(category)) {
            score += 0.12;
        }
        if (memory.getConfidence() != null) {
            score += Math.min(0.5, memory.getConfidence().doubleValue() * 0.35);
        }
        if (confirmed) {
            score += config.getConfirmedBoost();
        }
        if (recentlyRecalled) {
            score -= config.getCooldownPenalty();
        }
        return score;
    }

    private static boolean looksLikeNoisyMemory(String type, String content) {
        String text = normalizeContent(content);
        String lower = text.toLowerCase(Locale.ROOT);
        if ("content".equals(type) || text.contains("```") || text.contains("==") || text.contains("->")) {
            return true;
        }
        if (containsAny(text, "本题", "这道题", "该题", "当前题", "这个问题", "上述代码", "当前代码", "该代码", "这段代码", "样例", "输入格式", "输出格式")) {
            return true;
        }
        if (containsAny(lower, "ans", "chosen", "currentmin", "lastindex", "dfs函数", "bfs函数")) {
            return true;
        }
        if (containsAny(text, "检查", "确认", "建议", "调试步骤", "解法是", "答案是", "可以用于在有序数组中", "有助于简化问题", "可能导致")) {
            return true;
        }
        return switch (type) {
            case "preferred_language" -> !containsAny(lower, "python", "java", "c++", "cpp", "go", "javascript", "typescript", "js", "ts", "c#", "rust");
            case "skill_level" -> !containsAny(lower, "novice", "beginner", "intermediate", "advanced", "入门", "初学", "基础", "中等", "进阶", "熟练");
            case "weakness" -> !containsAny(text, "薄弱", "弱点", "不熟", "容易", "经常", "反复", "常在", "卡在", "混淆", "忽略", "漏");
            case "guidance_preference" -> !containsAny(text, "提示", "引导", "不要直接", "完整答案", "先问", "逐步", "苏格拉底", "先给");
            case "teaching_style" -> !containsAny(text, "讲解", "风格", "例子", "类比", "步骤", "简洁", "详细", "中文", "图示");
            case "learning_direction" -> !containsAny(text, "学习", "方向", "准备", "刷题", "目标", "正在", "计划", "提升");
            case "code_style" -> !containsAny(text, "代码", "命名", "注释", "风格", "简洁", "模板", "变量");
            case "debugging_preference" -> !containsAny(lower, "debug", "调试", "反例", "日志", "逐行", "检查", "定位", "排查");
            case "pace_preference" -> !containsAny(text, "慢", "快", "一步", "节奏", "详细", "简短", "慢一点", "快一点");
            case "name_preference" -> text.length() > 60;
            default -> text.length() < 4;
        };
    }

    private boolean looksSensitive(String value) {
        String lower = value.toLowerCase(Locale.ROOT);
        List<String> sensitiveWords = new ArrayList<>(List.of(
                "password", "token", "secret", "api key", "apikey", "手机号", "身份证", "密码", "令牌", "密钥"
        ));
        return sensitiveWords.stream().anyMatch(lower::contains);
    }

    private static boolean containsAny(String value, String... needles) {
        for (String needle : needles) {
            if (value.contains(needle)) {
                return true;
            }
        }
        return false;
    }

    private boolean memoryMatches(AiUserMemoryEntity memory, String keyword) {
        return normalizeForMatch(memory.getCategory()).contains(keyword)
                || normalizeForMatch(memory.getTitle()).contains(keyword)
                || normalizeForMatch(memory.getMemoryType()).contains(keyword)
                || normalizeForMatch(memory.getContent()).contains(keyword)
                || normalizeForMatch(memory.getStatus()).contains(keyword);
    }

    private AiUserMemoryEntity requireOwned(Long userId, Long id) {
        AiUserMemoryEntity memory = memoryMapper.selectById(id);
        if (memory == null) {
            throw new DomainException(ErrorCode.NOT_FOUND, "AI memory not found");
        }
        if (!userId.equals(memory.getUserId())) {
            throw new DomainException(ErrorCode.FORBIDDEN, "AI memory belongs to another user");
        }
        return memory;
    }

    private AiMemoryResponse toResponse(AiUserMemoryEntity memory) {
        return new AiMemoryResponse(
                memory.getId(),
                normalizeCategory(memory.getCategory(), true),
                normalizeTitle(memory.getTitle(), memory.getMemoryType(), memory.getContent()),
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

    private String normalizeMemoryType(String value, String category) {
        String normalized = normalizeValue(value).toLowerCase(Locale.ROOT);
        if (!normalized.isBlank()) {
            return normalized.length() <= 48 ? normalized : normalized.substring(0, 48);
        }
        String normalizedCategory = normalizeCategory(category, true);
        return switch (normalizedCategory) {
            case "habit" -> "habit";
            case "rule" -> "rule";
            case "preference" -> "guidance_preference";
            case "weakness" -> "weakness";
            case "teaching_style" -> "teaching_style";
            default -> "manual_note";
        };
    }

    private String normalizeTitle(String value, String memoryType, String content) {
        String normalized = normalizeValue(value);
        if (!normalized.isBlank()) {
            return truncate(normalized, 160);
        }
        return titleForContent(memoryType, content);
    }

    private static String normalizeValue(String value) {
        if (value == null) {
            return "";
        }
        return value.replace("\r", " ").replace("\n", " ").trim();
    }

    private static String normalizeContent(String value) {
        if (value == null) {
            return "";
        }
        return value.replace("\r\n", "\n").replace('\r', '\n').trim();
    }

    private String normalizeCategory(String value, boolean fallback) {
        String normalized = normalizeValue(value).toLowerCase(Locale.ROOT);
        if (MEMORY_CATEGORIES.contains(normalized)) {
            return normalized;
        }
        return fallback ? CATEGORY_MEMORY : "";
    }

    private String normalizeStatus(String value) {
        String normalized = normalizeValue(value).toUpperCase(Locale.ROOT);
        if (STATUS_DISABLED.equals(normalized)) {
            return STATUS_DISABLED;
        }
        if (STATUS_CANDIDATE.equals(normalized)) {
            return STATUS_CANDIDATE;
        }
        if (STATUS_RESOLVED.equals(normalized)) {
            return STATUS_RESOLVED;
        }
        if (STATUS_SUPERSEDED.equals(normalized)) {
            return STATUS_SUPERSEDED;
        }
        return STATUS_ACTIVE;
    }

    private String normalizeStatusFilter(String value) {
        String normalized = normalizeValue(value).toUpperCase(Locale.ROOT);
        if (STATUS_ACTIVE.equals(normalized)
                || STATUS_DISABLED.equals(normalized)
                || STATUS_RESOLVED.equals(normalized)
                || STATUS_SUPERSEDED.equals(normalized)) {
            return normalized;
        }
        return "";
    }

    private String categoryForType(String type) {
        return switch (normalizeValue(type).toLowerCase(Locale.ROOT)) {
            case "preferred_language", "guidance_preference", "pace_preference" -> "preference";
            case "teaching_style" -> "teaching_style";
            case "weakness" -> "weakness";
            case "code_style", "debugging_preference" -> "habit";
            default -> CATEGORY_MEMORY;
        };
    }

    private String titleForContent(String type, String content) {
        String prefix = switch (normalizeValue(type).toLowerCase(Locale.ROOT)) {
            case "preferred_language" -> "常用语言";
            case "skill_level" -> "能力水平";
            case "teaching_style" -> "讲解风格";
            case "guidance_preference" -> "引导偏好";
            case "learning_direction" -> "学习方向";
            case "weakness" -> "薄弱点";
            case "code_style" -> "代码习惯";
            case "debugging_preference" -> "调试习惯";
            case "pace_preference" -> "节奏偏好";
            case "name_preference" -> "称呼偏好";
            case "rule" -> "规则";
            case "habit" -> "习惯";
            default -> "记忆";
        };
        String plain = stripMarkdown(content);
        if (plain.isBlank()) {
            return prefix;
        }
        return truncate(prefix + "：" + plain, 160);
    }

    private String categoryLabel(String category) {
        return switch (category) {
            case "habit" -> "习惯";
            case "rule" -> "规则";
            case "preference" -> "偏好";
            case "weakness" -> "薄弱点";
            case "teaching_style" -> "讲解风格";
            default -> "记忆";
        };
    }

    private BigDecimal parseConfidence(String value) {
        try {
            return new BigDecimal(normalizeValue(value)).max(BigDecimal.ZERO).min(BigDecimal.ONE);
        } catch (Exception ignored) {
            return BigDecimal.ONE;
        }
    }

    private Long parseLong(String value) {
        try {
            String normalized = normalizeValue(value);
            return normalized.isBlank() ? null : Long.parseLong(normalized);
        } catch (Exception ignored) {
            return null;
        }
    }

    private String formatConfidence(BigDecimal value) {
        BigDecimal normalized = value == null ? BigDecimal.ONE : value;
        return normalized.setScale(3, RoundingMode.HALF_UP).toPlainString();
    }

    private String escapeMeta(String value) {
        return normalizeValue(value).replace("-->", "");
    }

    private String memoryChunkText(AiUserMemoryEntity memory) {
        return "长期记忆：" + normalizeValue(memory.getMemoryType()) + " - " + normalizeContent(memory.getContent());
    }

    private void index(AiUserMemoryEntity memory) {
        deleteIndex(memory);
        if (!STATUS_ACTIVE.equals(memory.getStatus()) || !recallableMemory(memory)) {
            return;
        }
        retrievalService.indexChunk(
                memory.getUserId(),
                "memory",
                String.valueOf(memory.getId()),
                memoryChunkText(memory)
        );
    }

    private void deleteIndex(AiUserMemoryEntity memory) {
        if (memory == null || memory.getId() == null) {
            return;
        }
        retrievalService.deleteOwner(memory.getUserId(), "memory", String.valueOf(memory.getId()));
    }

    private String normalizeForMatch(String value) {
        return normalizeContent(value).toLowerCase(Locale.ROOT).replaceAll("\\s+", " ");
    }

    private static String stripMarkdown(String value) {
        return normalizeContent(value)
                .replaceAll("(?s)```.*?```", " ")
                .replaceAll("(?m)^#{1,6}\\s*", "")
                .replaceAll("[*_`>\\[\\]()]"," ")
                .replaceAll("\\s+", " ")
                .trim();
    }

    private String truncate(String value, int maxLength) {
        if (value == null) {
            return "";
        }
        String normalized = value.trim();
        return normalized.length() <= maxLength ? normalized : normalized.substring(0, maxLength) + "…";
    }

    private String truncateForColumn(String value, int maxLength) {
        if (value == null) {
            return "";
        }
        String normalized = value.trim();
        return normalized.length() <= maxLength ? normalized : normalized.substring(0, maxLength);
    }

    private record ImportedMemory(
            Long id,
            String category,
            String title,
            String memoryType,
            String content,
            BigDecimal confidence,
            String status
    ) {
    }

    private record ScoredMemory(AiUserMemoryEntity memory, double score) {
    }

    private record MemoryUpsertResult(boolean activated, AiUserMemoryEntity memory) {
    }
}
