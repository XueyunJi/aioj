package com.aioj.next.ai.domain;

import com.aioj.next.ai.domain.memory.AiMemoryCandidateService;
import com.aioj.next.ai.persistence.entity.AiMemoryClaimEntity;
import com.aioj.next.ai.persistence.entity.AiMemoryEvidenceEntity;
import com.aioj.next.ai.persistence.entity.AiMemoryVersionEntity;
import com.aioj.next.ai.persistence.entity.AiUserMemoryEntity;
import com.aioj.next.ai.persistence.mapper.AiMemoryClaimMapper;
import com.aioj.next.ai.persistence.mapper.AiMemoryEvidenceMapper;
import com.aioj.next.ai.persistence.mapper.AiMemoryVersionMapper;
import com.aioj.next.ai.persistence.mapper.AiUserMemoryMapper;
import com.aioj.next.contract.ai.AiChatRequest;
import com.baomidou.mybatisplus.core.conditions.query.QueryWrapper;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDateTime;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

@Service
public class AiMemoryUpdatePlanner {
    private static final String ACTION_SUPPORT = "SUPPORT";
    private static final String ACTION_CONTRADICT = "CONTRADICT";
    private static final String ACTION_RESOLVE = "RESOLVE";
    private static final String ACTION_SUPERSEDE = "SUPERSEDE";
    private static final String CATEGORY_WEAKNESS = "WEAKNESS";
    private static final String TOPIC_BINARY_SEARCH_ANSWER = "binary_search_answer";
    private static final String TOPIC_DYNAMIC_PROGRAMMING = "dynamic_programming";
    private static final String TOPIC_DEBUGGING = "debugging";

    private final AiUserMemoryMapper memoryMapper;
    private final AiMemoryClaimMapper claimMapper;
    private final AiMemoryEvidenceMapper evidenceMapper;
    private final AiMemoryVersionMapper versionMapper;
    private final AiLearningProfileService learningProfileService;
    private final AiMemoryCandidateService candidateService;

    public AiMemoryUpdatePlanner(
            AiUserMemoryMapper memoryMapper,
            AiMemoryClaimMapper claimMapper,
            AiMemoryEvidenceMapper evidenceMapper,
            AiMemoryVersionMapper versionMapper,
            AiLearningProfileService learningProfileService,
            AiMemoryCandidateService candidateService
    ) {
        this.memoryMapper = memoryMapper;
        this.claimMapper = claimMapper;
        this.evidenceMapper = evidenceMapper;
        this.versionMapper = versionMapper;
        this.learningProfileService = learningProfileService;
        this.candidateService = candidateService;
    }

    @Transactional
    public PlanResult afterTurn(
            Long userId,
            String conversationId,
            Long assistantMessageId,
            AiChatRequest request,
            AiCompletion completion,
            AiLearningProfileService.SubmissionAnalysisSignal signal
    ) {
        if (userId == null || signal == null || signal.profileKey() == null) {
            return PlanResult.empty();
        }
        String topic = topicKey(signal, request, completion);
        String evidenceText = evidenceText(signal, request, completion);
        List<AiLearningProfileService.LearningProfileWeakness> profiles = learningProfileService.recallableWeaknessProfiles(userId);
        List<AiUserMemoryEntity> memories = activeWeaknessMemories(userId);
        List<AiMemoryClaimEntity> claims = activeWeaknessClaims(userId);
        Map<Long, AiMemoryClaimEntity> claimsByLegacy = claimsByLegacy(claims);
        EvidenceKind evidenceKind = evidenceKind(signal, request, completion, topic);
        if (evidenceKind == EvidenceKind.IGNORE) {
            return PlanResult.empty();
        }

        int profileUpdates = 0;
        int memoryUpdates = 0;
        int candidates = 0;
        int claimEvidence = 0;
        Set<Long> processedClaims = new HashSet<>();
        Set<Long> processedMemories = new HashSet<>();

        if (evidenceKind == EvidenceKind.RESOLVE) {
            for (AiLearningProfileService.LearningProfileWeakness profile : profiles) {
                if (matchesProfile(profile.profileKey(), topic)) {
                    learningProfileService.markProfileState(userId, profile.id(), AiMemoryService.STATUS_RESOLVED);
                    profileUpdates++;
                }
            }
            for (AiUserMemoryEntity memory : memories) {
                if (!matchesMemory(memory, topic)) {
                    continue;
                }
                AiMemoryClaimEntity claim = claimsByLegacy.get(memory.getId());
                processedMemories.add(memory.getId());
                if (claim != null && claim.id != null) {
                    processedClaims.add(claim.id);
                }
                if (AiMemoryService.isUserControlledSource(memory.getSource())) {
                    candidateService.createPlannerResolutionCandidate(userId, resolutionCandidate(
                            ACTION_RESOLVE, memory, claim, topic, evidenceText, conversationId, assistantMessageId));
                    candidates++;
                } else {
                    candidateService.applyPlannerStatus(userId, memory.getId(), claim == null ? null : claim.id,
                            ACTION_RESOLVE, evidenceText, "planner_resolve", conversationId, assistantMessageId);
                    memoryUpdates++;
                }
            }
            for (AiMemoryClaimEntity claim : claims) {
                if (claim.id != null && processedClaims.contains(claim.id)) {
                    continue;
                }
                if (matchesClaim(claim, topic) && !AiMemoryService.isUserControlledSource(claim.sourceMode)) {
                    markStandaloneClaimStatus(userId, claim, AiMemoryService.STATUS_RESOLVED, ACTION_RESOLVE,
                            evidenceText, conversationId, assistantMessageId);
                    processedClaims.add(claim.id);
                    claimEvidence++;
                }
            }
            return new PlanResult(profileUpdates, memoryUpdates, candidates, claimEvidence, ACTION_RESOLVE);
        }

        if (evidenceKind == EvidenceKind.SUPPORT) {
            for (AiMemoryClaimEntity claim : claims) {
                if (matchesClaim(claim, topic)) {
                    recordClaimEvidence(userId, claim, ACTION_SUPPORT, evidenceText, conversationId, assistantMessageId, 1, 0);
                    claimEvidence++;
                }
            }
            SupersedeResult superseded = supersedeGenericWeaknesses(userId, topic, signal, profiles, memories, claimsByLegacy,
                    evidenceText, conversationId, assistantMessageId, processedMemories);
            return new PlanResult(superseded.profileUpdates(), superseded.memoryUpdates(), superseded.candidates(),
                    claimEvidence, superseded.changed() ? ACTION_SUPERSEDE : ACTION_SUPPORT);
        }

        if (evidenceKind == EvidenceKind.CONTRADICT) {
            for (AiMemoryClaimEntity claim : claims) {
                if (matchesClaim(claim, topic)) {
                    recordClaimEvidence(userId, claim, ACTION_CONTRADICT, evidenceText, conversationId, assistantMessageId, 0, 1);
                    claimEvidence++;
                }
            }
            return new PlanResult(0, 0, 0, claimEvidence, ACTION_CONTRADICT);
        }
        return PlanResult.empty();
    }

    private SupersedeResult supersedeGenericWeaknesses(
            Long userId,
            String topic,
            AiLearningProfileService.SubmissionAnalysisSignal signal,
            List<AiLearningProfileService.LearningProfileWeakness> profiles,
            List<AiUserMemoryEntity> memories,
            Map<Long, AiMemoryClaimEntity> claimsByLegacy,
            String evidenceText,
            String conversationId,
            Long assistantMessageId,
            Set<Long> processedMemories
    ) {
        if (TOPIC_DEBUGGING.equals(topic) || !isFailureStatus(signal.status())) {
            return SupersedeResult.empty();
        }
        int profileUpdates = 0;
        int memoryUpdates = 0;
        int candidates = 0;
        String genericProfileKey = statusPrefix(signal.profileKey()) + "_debugging";
        for (AiLearningProfileService.LearningProfileWeakness profile : profiles) {
            if (genericProfileKey.equals(normalizeKey(profile.profileKey()))) {
                learningProfileService.markProfileState(userId, profile.id(), AiMemoryService.STATUS_SUPERSEDED);
                profileUpdates++;
            }
        }
        for (AiUserMemoryEntity memory : memories) {
            if (memory.getId() == null || processedMemories.contains(memory.getId()) || !matchesGenericWeakness(memory)) {
                continue;
            }
            AiMemoryClaimEntity claim = claimsByLegacy.get(memory.getId());
            processedMemories.add(memory.getId());
            if (AiMemoryService.isUserControlledSource(memory.getSource())) {
                candidateService.createPlannerResolutionCandidate(userId, resolutionCandidate(
                        ACTION_SUPERSEDE, memory, claim, topic, evidenceText, conversationId, assistantMessageId));
                candidates++;
            } else {
                candidateService.applyPlannerStatus(userId, memory.getId(), claim == null ? null : claim.id,
                        ACTION_SUPERSEDE, evidenceText, "planner_supersede", conversationId, assistantMessageId);
                memoryUpdates++;
            }
        }
        return new SupersedeResult(profileUpdates, memoryUpdates, candidates);
    }

    private AiMemoryCandidateService.ResolutionCandidateRequest resolutionCandidate(
            String action,
            AiUserMemoryEntity memory,
            AiMemoryClaimEntity claim,
            String topic,
            String evidenceText,
            String conversationId,
            Long assistantMessageId
    ) {
        String verb = ACTION_SUPERSEDE.equals(action) ? "替换为更具体弱点" : "标记为已解决";
        String text = ("系统发现新的提交证据，建议将旧弱点" + verb + "。\n"
                + "旧弱点：" + normalize(memory.getTitle()) + "\n"
                + "证据：" + evidenceText).trim();
        return new AiMemoryCandidateService.ResolutionCandidateRequest(
                action,
                memory.getId(),
                claim == null ? null : claim.id,
                topic,
                text,
                evidenceText,
                conversationId,
                assistantMessageId,
                0.92,
                "planner_" + action.toLowerCase(Locale.ROOT)
        );
    }

    private List<AiUserMemoryEntity> activeWeaknessMemories(Long userId) {
        return memoryMapper.selectList(new QueryWrapper<AiUserMemoryEntity>()
                        .eq("user_id", userId)
                        .eq("status", AiMemoryService.STATUS_ACTIVE)
                        .last("LIMIT 240"))
                .stream()
                .filter(this::isWeaknessMemory)
                .toList();
    }

    private List<AiMemoryClaimEntity> activeWeaknessClaims(Long userId) {
        return claimMapper.selectList(new QueryWrapper<AiMemoryClaimEntity>()
                .eq("user_id", userId)
                .eq("status", AiMemoryService.STATUS_ACTIVE)
                .eq("category", CATEGORY_WEAKNESS)
                .last("LIMIT 240"))
                .stream()
                .filter(claim -> AiMemoryService.STATUS_ACTIVE.equals(claim.status))
                .toList();
    }

    private Map<Long, AiMemoryClaimEntity> claimsByLegacy(List<AiMemoryClaimEntity> claims) {
        Map<Long, AiMemoryClaimEntity> values = new HashMap<>();
        for (AiMemoryClaimEntity claim : claims) {
            if (claim.legacyMemoryId != null) {
                values.putIfAbsent(claim.legacyMemoryId, claim);
            }
        }
        return values;
    }

    private EvidenceKind evidenceKind(
            AiLearningProfileService.SubmissionAnalysisSignal signal,
            AiChatRequest request,
            AiCompletion completion,
            String topic
    ) {
        if ("ACCEPTED".equalsIgnoreCase(signal.status())) {
            return signal.masteryEvidence() ? EvidenceKind.RESOLVE : EvidenceKind.CONTRADICT;
        }
        if (isFailureStatus(signal.status())) {
            return EvidenceKind.SUPPORT;
        }
        return EvidenceKind.IGNORE;
    }

    private boolean isFailureStatus(String status) {
        String normalized = normalize(status).toUpperCase(Locale.ROOT);
        return !normalized.isBlank()
                && !"ACCEPTED".equals(normalized)
                && !"QUEUED".equals(normalized)
                && !"RUNNING".equals(normalized);
    }

    private boolean matchesProfile(String profileKey, String topic) {
        String normalized = normalizeKey(profileKey);
        if (TOPIC_BINARY_SEARCH_ANSWER.equals(topic)) {
            return normalized.contains("binary_search") || normalized.contains("二分");
        }
        return normalized.contains(topic);
    }

    private boolean matchesMemory(AiUserMemoryEntity memory, String topic) {
        return isWeaknessMemory(memory) && matchesText(memoryText(memory), topic);
    }

    private boolean matchesClaim(AiMemoryClaimEntity claim, String topic) {
        return claim != null && CATEGORY_WEAKNESS.equalsIgnoreCase(claim.category)
                && matchesText(normalize(claim.memoryKey) + "\n" + normalize(claim.canonicalText), topic);
    }

    private boolean matchesGenericWeakness(AiUserMemoryEntity memory) {
        String text = memoryText(memory);
        return isWeaknessMemory(memory)
                && containsAny(text, "debug", "调试", "提交错误", "wrong_answer_debugging", "答案错误", "wa")
                && !containsAny(text, "二分", "binary", "dynamic", "dp", "背包");
    }

    private boolean matchesText(String text, String topic) {
        String normalized = normalize(text).toLowerCase(Locale.ROOT);
        if (TOPIC_BINARY_SEARCH_ANSWER.equals(topic)) {
            return containsAny(normalized, "binary", "二分", "check", "单调", "最大化最小值", "最小化最大值");
        }
        if (TOPIC_DYNAMIC_PROGRAMMING.equals(topic)) {
            return containsAny(normalized, "dynamic", "dp", "动态规划", "背包");
        }
        return containsAny(normalized, "debug", "调试", "答案错误", "wrong_answer", "wa", "提交错误");
    }

    private boolean isWeaknessMemory(AiUserMemoryEntity memory) {
        if (memory == null || !AiMemoryService.STATUS_ACTIVE.equals(memory.getStatus())) {
            return false;
        }
        return "weakness".equalsIgnoreCase(memory.getCategory()) || "weakness".equalsIgnoreCase(memory.getMemoryType());
    }

    private String memoryText(AiUserMemoryEntity memory) {
        return normalize(memory.getCategory()) + "\n"
                + normalize(memory.getMemoryType()) + "\n"
                + normalize(memory.getTitle()) + "\n"
                + normalize(memory.getContent());
    }

    private String topicKey(AiLearningProfileService.SubmissionAnalysisSignal signal, AiChatRequest request, AiCompletion completion) {
        String text = normalize(signal.profileKey()) + "\n"
                + String.join("\n", signal.tags() == null ? List.of() : signal.tags()) + "\n"
                + normalize(request == null ? null : request.message()) + "\n"
                + normalize(completion == null ? null : completion.content());
        String lower = text.toLowerCase(Locale.ROOT);
        if (containsAny(lower, "binary", "二分", "check", "单调", "最大化最小值", "最小化最大值")) {
            return TOPIC_BINARY_SEARCH_ANSWER;
        }
        if (containsAny(lower, "dynamic", "dp", "动态规划", "背包", "knapsack")) {
            return TOPIC_DYNAMIC_PROGRAMMING;
        }
        return TOPIC_DEBUGGING;
    }

    private String evidenceText(AiLearningProfileService.SubmissionAnalysisSignal signal, AiChatRequest request, AiCompletion completion) {
        StringBuilder builder = new StringBuilder();
        builder.append("submission=").append(signal.submissionId()).append('\n');
        builder.append("status=").append(normalize(signal.status())).append('\n');
        builder.append("profileKey=").append(normalize(signal.profileKey())).append('\n');
        builder.append(normalize(signal.safeSummary()));
        return truncate(builder.toString(), 1200);
    }

    private void recordClaimEvidence(
            Long userId,
            AiMemoryClaimEntity claim,
            String action,
            String evidenceText,
            String conversationId,
            Long messageId,
            int supportDelta,
            int contradictionDelta
    ) {
        LocalDateTime now = LocalDateTime.now();
        claim.supportCount = Math.max(0, claim.supportCount == null ? 0 : claim.supportCount) + supportDelta;
        claim.contradictionCount = Math.max(0, claim.contradictionCount == null ? 0 : claim.contradictionCount) + contradictionDelta;
        claim.lastSeenAt = now;
        claim.version = Math.max(1, claim.version == null ? 1 : claim.version + 1);
        claim.updatedAt = now;
        claimMapper.updateById(claim);

        AiMemoryEvidenceEntity evidence = new AiMemoryEvidenceEntity();
        evidence.userId = userId;
        evidence.claimId = claim.id;
        evidence.conversationId = conversationId;
        evidence.messageId = messageId;
        evidence.evidenceType = action;
        evidence.evidenceText = truncate(evidenceText, 4000);
        evidence.confidence = BigDecimal.valueOf(ACTION_SUPPORT.equals(action) ? 0.70 : 0.82)
                .setScale(4, RoundingMode.HALF_UP);
        evidence.reason = "planner_" + action.toLowerCase(Locale.ROOT);
        evidence.createdAt = now;
        evidenceMapper.insert(evidence);

        AiMemoryVersionEntity version = new AiMemoryVersionEntity();
        version.userId = userId;
        version.claimId = claim.id;
        version.version = claim.version;
        version.canonicalText = claim.canonicalText;
        version.valueJson = claim.valueJson;
        version.status = claim.status;
        version.changeReason = evidence.reason;
        version.createdAt = now;
        versionMapper.insert(version);
    }

    private void markStandaloneClaimStatus(
            Long userId,
            AiMemoryClaimEntity claim,
            String status,
            String action,
            String evidenceText,
            String conversationId,
            Long messageId
    ) {
        LocalDateTime now = LocalDateTime.now();
        claim.status = status;
        claim.contradictionCount = Math.max(0, claim.contradictionCount == null ? 0 : claim.contradictionCount) + 1;
        claim.lastSeenAt = now;
        claim.version = Math.max(1, claim.version == null ? 1 : claim.version + 1);
        claim.updatedAt = now;
        claimMapper.updateById(claim);

        AiMemoryEvidenceEntity evidence = new AiMemoryEvidenceEntity();
        evidence.userId = userId;
        evidence.claimId = claim.id;
        evidence.conversationId = conversationId;
        evidence.messageId = messageId;
        evidence.evidenceType = action;
        evidence.evidenceText = truncate(evidenceText, 4000);
        evidence.confidence = BigDecimal.valueOf(0.92).setScale(4, RoundingMode.HALF_UP);
        evidence.reason = "planner_" + action.toLowerCase(Locale.ROOT);
        evidence.createdAt = now;
        evidenceMapper.insert(evidence);

        AiMemoryVersionEntity version = new AiMemoryVersionEntity();
        version.userId = userId;
        version.claimId = claim.id;
        version.version = claim.version;
        version.canonicalText = claim.canonicalText;
        version.valueJson = claim.valueJson;
        version.status = claim.status;
        version.changeReason = evidence.reason;
        version.createdAt = now;
        versionMapper.insert(version);
    }

    private String statusPrefix(String profileKey) {
        String normalized = normalizeKey(profileKey);
        for (String prefix : List.of("wrong_answer", "time_limit", "runtime_error", "compile_error", "system_error", "accepted")) {
            if (normalized.startsWith(prefix + "_")) {
                return prefix;
            }
        }
        int index = normalized.indexOf('_');
        return index <= 0 ? normalized : normalized.substring(0, index);
    }

    private String normalizeKey(String value) {
        return normalize(value).toLowerCase(Locale.ROOT)
                .replaceAll("[^a-z0-9_\\u4e00-\\u9fa5]+", "_")
                .replaceAll("_+", "_")
                .replaceAll("^_|_$", "");
    }

    private boolean containsAny(String value, String... needles) {
        String haystack = value == null ? "" : value;
        for (String needle : needles) {
            if (haystack.contains(needle)) {
                return true;
            }
        }
        return false;
    }

    private String normalize(String value) {
        return value == null ? "" : value.replace("\r\n", "\n").replace('\r', '\n').trim();
    }

    private String truncate(String value, int maxLength) {
        String normalized = normalize(value);
        return normalized.length() <= maxLength ? normalized : normalized.substring(0, maxLength);
    }

    private enum EvidenceKind {
        SUPPORT,
        CONTRADICT,
        RESOLVE,
        IGNORE
    }

    public record PlanResult(
            int profileUpdates,
            int memoryUpdates,
            int candidatesCreated,
            int claimEvidence,
            String primaryAction
    ) {
        public static PlanResult empty() {
            return new PlanResult(0, 0, 0, 0, "IGNORE");
        }
    }

    private record SupersedeResult(int profileUpdates, int memoryUpdates, int candidates) {
        static SupersedeResult empty() {
            return new SupersedeResult(0, 0, 0);
        }

        boolean changed() {
            return profileUpdates > 0 || memoryUpdates > 0 || candidates > 0;
        }
    }
}
