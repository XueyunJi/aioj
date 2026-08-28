package com.aioj.next.ai.domain;

import com.aioj.next.ai.agent.telemetry.ContestAiAssistanceTelemetryService;
import com.aioj.next.ai.persistence.entity.AiContestAssistanceLegacySnapshotEntity;
import com.aioj.next.ai.persistence.entity.AiContestAssistanceModelUsageEntity;
import com.aioj.next.ai.persistence.entity.AiContestAssistanceTurnEntity;
import com.aioj.next.ai.persistence.entity.AiConversationEntity;
import com.aioj.next.ai.persistence.entity.AiMessageEntity;
import com.aioj.next.ai.persistence.mapper.AiContestAssistanceLegacySnapshotMapper;
import com.aioj.next.ai.persistence.mapper.AiContestAssistanceModelUsageMapper;
import com.aioj.next.ai.persistence.mapper.AiContestAssistanceTurnMapper;
import com.aioj.next.ai.persistence.mapper.AiConversationMapper;
import com.aioj.next.ai.persistence.mapper.AiMessageMapper;
import com.aioj.next.common.error.DomainException;
import com.aioj.next.common.error.ErrorCode;
import com.aioj.next.contract.ai.AdminContestAiAssistanceSummary;
import com.aioj.next.contract.ai.AdminContestAiConversationSummary;
import com.aioj.next.contract.ai.AdminContestAiMessageResponse;
import com.aioj.next.contract.ai.ContestParticipantProfile;
import com.aioj.next.contract.ai.ProblemTitleInfo;
import com.aioj.next.contract.contest.ContestRunWindow;
import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Canonical administrator view for contest-period AI assistance.
 *
 * <p>Live rows are created only from V3's server-authoritative L1 attribution.
 * The legacy rows are an immutable V63 estimate and are explicitly labelled as
 * such in the response. This service is read-only with respect to the Agent
 * pipeline and preserves the old {@link ContestAiUsageService} API for clients
 * that have not migrated yet.</p>
 */
@Service
public class ContestAiAssistanceStatisticsService {
    private static final Logger log = LoggerFactory.getLogger(ContestAiAssistanceStatisticsService.class);
    private static final ZoneId ZONE = ZoneId.systemDefault();
    private static final long WINDOW_GRACE_SECONDS = 60L;

    public static final String DATA_SOURCE_LIVE = "LIVE";
    public static final String DATA_SOURCE_HISTORICAL_SNAPSHOT = "HISTORICAL_SNAPSHOT";
    public static final String DATA_SOURCE_MIXED = "MIXED";
    public static final String TOKEN_ACCOUNTING_COMPLETE = "COMPLETE";
    public static final String TOKEN_ACCOUNTING_PARTIAL = "PARTIAL";
    public static final String TOKEN_ACCOUNTING_ESTIMATED = "ESTIMATED";

    private final AiContestAssistanceTurnMapper turnMapper;
    private final AiContestAssistanceModelUsageMapper usageMapper;
    private final AiContestAssistanceLegacySnapshotMapper snapshotMapper;
    private final AiConversationMapper conversationMapper;
    private final AiMessageMapper messageMapper;
    private final ProblemServiceClient problemServiceClient;
    private final OperationAuditWriter auditWriter;

    public ContestAiAssistanceStatisticsService(
            AiContestAssistanceTurnMapper turnMapper,
            AiContestAssistanceModelUsageMapper usageMapper,
            AiContestAssistanceLegacySnapshotMapper snapshotMapper,
            AiConversationMapper conversationMapper,
            AiMessageMapper messageMapper,
            ProblemServiceClient problemServiceClient,
            OperationAuditWriter auditWriter
    ) {
        this.turnMapper = turnMapper;
        this.usageMapper = usageMapper;
        this.snapshotMapper = snapshotMapper;
        this.conversationMapper = conversationMapper;
        this.messageMapper = messageMapper;
        this.problemServiceClient = problemServiceClient;
        this.auditWriter = auditWriter;
    }

    public List<AdminContestAiAssistanceSummary> summaries(Long contestId, Long contestRunId) {
        WindowScope scope = windowScope(contestId, contestRunId);
        Map<Long, Aggregate> aggregates = new HashMap<>();
        Map<Long, Long> userByAssistanceTurnId = new HashMap<>();

        for (AiContestAssistanceTurnEntity turn : liveTurns(contestId, contestRunId, scope, null)) {
            if (turn.getUserId() == null || turn.getId() == null) {
                continue;
            }
            Aggregate aggregate = aggregates.computeIfAbsent(turn.getUserId(), ignored -> new Aggregate());
            aggregate.addLiveTurn(turn);
            userByAssistanceTurnId.put(turn.getId(), turn.getUserId());
        }
        if (!userByAssistanceTurnId.isEmpty()) {
            List<AiContestAssistanceModelUsageEntity> usages = usageMapper.selectList(
                    new LambdaQueryWrapper<AiContestAssistanceModelUsageEntity>()
                            .in(AiContestAssistanceModelUsageEntity::getAssistanceTurnId,
                                    userByAssistanceTurnId.keySet()));
            for (AiContestAssistanceModelUsageEntity usage : usages) {
                Long userId = userByAssistanceTurnId.get(usage.getAssistanceTurnId());
                if (userId != null) {
                    aggregates.get(userId).addModelUsage(usage);
                }
            }
        }
        for (AiContestAssistanceLegacySnapshotEntity snapshot : legacySnapshots(contestId, contestRunId, null)) {
            if (snapshot.getUserId() != null) {
                aggregates.computeIfAbsent(snapshot.getUserId(), ignored -> new Aggregate()).addLegacySnapshot(snapshot);
            }
        }

        Map<Long, ParticipantIdentity> identities = participantIdentities(contestId, contestRunId);
        List<AdminContestAiAssistanceSummary> result = new ArrayList<>();
        for (Map.Entry<Long, Aggregate> entry : aggregates.entrySet()) {
            Long userId = entry.getKey();
            Aggregate aggregate = entry.getValue();
            ParticipantIdentity identity = identities.getOrDefault(userId, ParticipantIdentity.EMPTY);
            result.add(new AdminContestAiAssistanceSummary(
                    userId,
                    identity.account(),
                    identity.displayName(),
                    aggregate.turnCount,
                    aggregate.promptTokens,
                    aggregate.completionTokens,
                    aggregate.conversationCount(),
                    aggregate.interceptedCount,
                    aggregate.dataSource(),
                    aggregate.tokenAccountingStatus(),
                    aggregate.lastUsedAt
            ));
        }
        result.sort(Comparator
                .comparing((AdminContestAiAssistanceSummary summary) -> summary.lastUsedAt() == null
                        ? Instant.EPOCH : summary.lastUsedAt()).reversed()
                .thenComparing(AdminContestAiAssistanceSummary::userId));
        return result;
    }

    public List<AdminContestAiConversationSummary> conversations(
            Long contestId,
            Long contestRunId,
            Long userId
    ) {
        WindowScope scope = windowScope(contestId, contestRunId);
        Map<String, ConversationAccess> accesses = conversationAccesses(contestId, scope, userId);
        if (accesses.isEmpty()) {
            return List.of();
        }
        Map<String, List<AiMessageEntity>> messagesByConversation = inWindowMessages(accesses, scope);
        if (messagesByConversation.isEmpty()) {
            return List.of();
        }
        Map<Long, ProblemTitleInfo> titles = titlesFor(accesses.values(), messagesByConversation.values());
        List<AdminContestAiConversationSummary> result = new ArrayList<>();
        for (ConversationAccess access : accesses.values()) {
            List<AiMessageEntity> messages = messagesByConversation.get(access.conversation().getId());
            if (messages == null || messages.isEmpty()) {
                continue;
            }
            Long problemId = problemId(access.conversation(), messages);
            ProblemTitleInfo title = problemId == null ? null : titles.get(problemId);
            result.add(new AdminContestAiConversationSummary(
                    access.conversation().getId(),
                    access.conversation().getTitle(),
                    access.conversation().getMode(),
                    access.primaryRunId(),
                    access.conversation().getContestProblemId(),
                    problemId,
                    title == null ? null : title.title(),
                    messages.size(),
                    latestMessageAt(messages)
            ));
        }
        result.sort(Comparator.comparing((AdminContestAiConversationSummary summary) -> summary.lastMessageAt() == null
                ? Instant.EPOCH : summary.lastMessageAt()).reversed());
        return result;
    }

    public List<AdminContestAiMessageResponse> messages(
            Long contestId,
            Long contestRunId,
            Long userId,
            String conversationId,
            Long viewerUserId
    ) {
        WindowScope scope = windowScope(contestId, contestRunId);
        ConversationAccess access = conversationAccesses(contestId, scope, userId).get(conversationId);
        if (access == null) {
            throw new DomainException(ErrorCode.NOT_FOUND, "AI conversation not found");
        }
        List<AiMessageEntity> messages = messageMapper.selectList(new LambdaQueryWrapper<AiMessageEntity>()
                .eq(AiMessageEntity::getConversationId, conversationId)
                .orderByAsc(AiMessageEntity::getCreatedAt)
                .orderByAsc(AiMessageEntity::getId));
        recordTranscriptView(contestId, userId, conversationId, viewerUserId);
        return messages.stream()
                .filter(message -> scope.allowsTranscript(access.runIds(), toInstant(message.getCreatedAt())))
                .map(message -> new AdminContestAiMessageResponse(
                        message.getId(),
                        message.getRole(),
                        message.getContent(),
                        message.getStatus(),
                        message.getModel(),
                        toInstant(message.getCreatedAt())
                ))
                .toList();
    }

    private List<AiContestAssistanceTurnEntity> liveTurns(
            Long contestId,
            Long contestRunId,
            WindowScope scope,
            Long userId
    ) {
        List<AiContestAssistanceTurnEntity> rows = turnMapper.selectList(
                new LambdaQueryWrapper<AiContestAssistanceTurnEntity>()
                        .eq(AiContestAssistanceTurnEntity::getContestId, contestId)
                        .eq(contestRunId != null, AiContestAssistanceTurnEntity::getContestRunId, contestRunId)
                        .eq(userId != null, AiContestAssistanceTurnEntity::getUserId, userId));
        return rows.stream()
                .filter(turn -> scope.allowsLedger(turn.getContestRunId(), toInstant(turn.getStartedAt())))
                .toList();
    }

    private List<AiContestAssistanceLegacySnapshotEntity> legacySnapshots(
            Long contestId,
            Long contestRunId,
            Long userId
    ) {
        return snapshotMapper.selectList(new LambdaQueryWrapper<AiContestAssistanceLegacySnapshotEntity>()
                .eq(AiContestAssistanceLegacySnapshotEntity::getContestId, contestId)
                .eq(contestRunId != null, AiContestAssistanceLegacySnapshotEntity::getContestRunId, contestRunId)
                .eq(userId != null, AiContestAssistanceLegacySnapshotEntity::getUserId, userId));
    }

    private Map<String, ConversationAccess> conversationAccesses(
            Long contestId,
            WindowScope scope,
            Long userId
    ) {
        List<AiContestAssistanceTurnEntity> turns = liveTurns(contestId, scope.requestedRunId(), scope, userId);
        Map<String, ConversationAccess> accesses = new LinkedHashMap<>();
        Set<RunUser> liveRunUsers = new HashSet<>();
        Set<String> liveConversationIds = new LinkedHashSet<>();
        for (AiContestAssistanceTurnEntity turn : turns) {
            if (turn.getConversationId() == null || turn.getConversationId().isBlank()) {
                continue;
            }
            liveConversationIds.add(turn.getConversationId());
            liveRunUsers.add(new RunUser(turn.getContestRunId(), turn.getUserId()));
        }
        if (!liveConversationIds.isEmpty()) {
            for (AiConversationEntity conversation : conversationMapper.selectList(
                    new LambdaQueryWrapper<AiConversationEntity>()
                            .in(AiConversationEntity::getId, liveConversationIds)
                            .isNull(AiConversationEntity::getDeletedAt))) {
                List<AiContestAssistanceTurnEntity> matchingTurns = turns.stream()
                        .filter(turn -> conversation.getId().equals(turn.getConversationId()))
                        .toList();
                // The ledger is authoritative, but keep the transcript boundary
                // defensive in case an operator ever encounters corrupt rows.
                if (matchingTurns.stream().noneMatch(turn -> turn.getUserId() != null
                        && turn.getUserId().equals(conversation.getUserId()))) {
                    continue;
                }
                ConversationAccess access = accesses.computeIfAbsent(conversation.getId(),
                        ignored -> new ConversationAccess(conversation));
                for (AiContestAssistanceTurnEntity turn : matchingTurns) {
                    access.addRun(turn.getContestRunId());
                }
            }
        }

        // Legacy conversation binding is intentionally a narrow transcript fallback.
        // It is used only for an estimated user/run that has no live ledger turn.
        for (AiContestAssistanceLegacySnapshotEntity snapshot : legacySnapshots(
                contestId, scope.requestedRunId(), userId)) {
            RunUser runUser = new RunUser(snapshot.getContestRunId(), snapshot.getUserId());
            if (snapshot.getUserId() == null || liveRunUsers.contains(runUser)) {
                continue;
            }
            List<AiConversationEntity> legacyConversations = conversationMapper.selectList(
                    new LambdaQueryWrapper<AiConversationEntity>()
                            .eq(AiConversationEntity::getContestId, contestId)
                            .eq(AiConversationEntity::getContestRunId, snapshot.getContestRunId())
                            .eq(AiConversationEntity::getUserId, snapshot.getUserId())
                            .isNull(AiConversationEntity::getDeletedAt));
            for (AiConversationEntity conversation : legacyConversations) {
                accesses.computeIfAbsent(conversation.getId(), ignored -> new ConversationAccess(conversation))
                        .addRun(snapshot.getContestRunId());
            }
        }
        return accesses;
    }

    private Map<String, List<AiMessageEntity>> inWindowMessages(
            Map<String, ConversationAccess> accesses,
            WindowScope scope
    ) {
        List<AiMessageEntity> allMessages = messageMapper.selectList(new LambdaQueryWrapper<AiMessageEntity>()
                .in(AiMessageEntity::getConversationId, accesses.keySet())
                .orderByAsc(AiMessageEntity::getCreatedAt)
                .orderByAsc(AiMessageEntity::getId));
        Map<String, List<AiMessageEntity>> byConversation = new LinkedHashMap<>();
        for (AiMessageEntity message : allMessages) {
            ConversationAccess access = accesses.get(message.getConversationId());
            if (access != null && scope.allowsTranscript(access.runIds(), toInstant(message.getCreatedAt()))) {
                byConversation.computeIfAbsent(message.getConversationId(), ignored -> new ArrayList<>()).add(message);
            }
        }
        return byConversation;
    }

    private Map<Long, ParticipantIdentity> participantIdentities(Long contestId, Long contestRunId) {
        Map<Long, ParticipantIdentity> result = new HashMap<>();
        try {
            for (ContestParticipantProfile profile : problemServiceClient.contestParticipantProfiles(contestId)) {
                if (profile.userId() == null || (contestRunId != null && !contestRunId.equals(profile.contestRunId()))) {
                    continue;
                }
                result.putIfAbsent(profile.userId(), new ParticipantIdentity(profile.account(), profile.displayName()));
            }
        } catch (RuntimeException ex) {
            // The ledger remains safe and useful even if a display-name lookup is unavailable.
            log.warn("contest assistance participant profile lookup failed contest={} error={}", contestId, ex.toString());
        }
        return result;
    }

    private Map<Long, ProblemTitleInfo> titlesFor(
            Collection<ConversationAccess> accesses,
            Collection<List<AiMessageEntity>> messageLists
    ) {
        Set<Long> problemIds = new LinkedHashSet<>();
        for (ConversationAccess access : accesses) {
            if (access.conversation().getProblemId() != null) {
                problemIds.add(access.conversation().getProblemId());
            } else if (access.conversation().getRecentProblemId() != null) {
                problemIds.add(access.conversation().getRecentProblemId());
            }
        }
        for (List<AiMessageEntity> messages : messageLists) {
            for (AiMessageEntity message : messages) {
                if (message.getProblemId() != null) {
                    problemIds.add(message.getProblemId());
                }
            }
        }
        if (problemIds.isEmpty()) {
            return Map.of();
        }
        Map<Long, ProblemTitleInfo> result = new HashMap<>();
        try {
            for (ProblemTitleInfo info : problemServiceClient.problemTitles(new ArrayList<>(problemIds))) {
                if (info.id() != null) {
                    result.putIfAbsent(info.id(), info);
                }
            }
        } catch (RuntimeException ex) {
            log.warn("contest assistance problem title lookup failed error={}", ex.toString());
        }
        return result;
    }

    private Long problemId(AiConversationEntity conversation, List<AiMessageEntity> messages) {
        if (conversation.getProblemId() != null) {
            return conversation.getProblemId();
        }
        if (conversation.getRecentProblemId() != null) {
            return conversation.getRecentProblemId();
        }
        return messages.stream()
                .map(AiMessageEntity::getProblemId)
                .filter(problemId -> problemId != null)
                .findFirst()
                .orElse(null);
    }

    private Instant latestMessageAt(List<AiMessageEntity> messages) {
        Instant latest = null;
        for (AiMessageEntity message : messages) {
            Instant createdAt = toInstant(message.getCreatedAt());
            if (createdAt != null && (latest == null || createdAt.isAfter(latest))) {
                latest = createdAt;
            }
        }
        return latest;
    }

    private WindowScope windowScope(Long contestId, Long contestRunId) {
        Map<Long, RunWindow> windows = new HashMap<>();
        try {
            for (ContestRunWindow window : problemServiceClient.contestRunWindows(contestId)) {
                if (window.runId() != null && window.startAt() != null && window.endAt() != null) {
                    windows.put(window.runId(), new RunWindow(window.startAt(), window.endAt().plusSeconds(WINDOW_GRACE_SECONDS)));
                }
            }
        } catch (RuntimeException ex) {
            log.warn("contest assistance window lookup failed contest={} error={}", contestId, ex.toString());
        }
        return new WindowScope(contestRunId, windows);
    }

    private void recordTranscriptView(Long contestId, Long targetUserId, String conversationId, Long viewerUserId) {
        if (auditWriter == null) {
            return;
        }
        try {
            Map<String, Object> summary = new LinkedHashMap<>();
            summary.put("conversationId", conversationId);
            auditWriter.record(
                    "AI_CONTEST_TRANSCRIPT_VIEWED",
                    "AI_CONVERSATION",
                    null,
                    "VIEWED",
                    summary,
                    viewerUserId,
                    contestId,
                    null,
                    targetUserId
            );
        } catch (RuntimeException ignored) {
            // Reading a transcript must remain available when its audit sink is unavailable.
        }
    }

    private Instant toInstant(LocalDateTime value) {
        return value == null ? null : value.atZone(ZONE).toInstant();
    }

    private record RunWindow(Instant start, Instant endInclusive) {
        boolean contains(Instant at) {
            return at != null && !at.isBefore(start) && !at.isAfter(endInclusive);
        }
    }

    /** Ledger rows were already accepted at write time; unavailable live run windows must not erase them. */
    private record WindowScope(Long requestedRunId, Map<Long, RunWindow> windows) {
        boolean allowsLedger(Long runId, Instant at) {
            if (requestedRunId != null && !requestedRunId.equals(runId)) {
                return false;
            }
            if (windows.isEmpty()) {
                return true;
            }
            RunWindow window = windows.get(runId);
            return window != null && window.contains(at);
        }

        /** Transcript content is sensitive, so an unavailable run window fails closed. */
        boolean allowsTranscript(Set<Long> runIds, Instant at) {
            if (at == null || windows.isEmpty()) {
                return false;
            }
            Set<Long> effectiveRunIds = requestedRunId == null ? runIds : Set.of(requestedRunId);
            for (Long runId : effectiveRunIds) {
                RunWindow window = windows.get(runId);
                if (window != null && window.contains(at)) {
                    return true;
                }
            }
            return false;
        }
    }

    private static final class ConversationAccess {
        private final AiConversationEntity conversation;
        private final Set<Long> runIds = new LinkedHashSet<>();

        private ConversationAccess(AiConversationEntity conversation) {
            this.conversation = conversation;
        }

        private AiConversationEntity conversation() {
            return conversation;
        }

        private ConversationAccess addRun(Long runId) {
            if (runId != null) {
                runIds.add(runId);
            }
            return this;
        }

        private Set<Long> runIds() {
            return Set.copyOf(runIds);
        }

        private Long primaryRunId() {
            return runIds.stream().findFirst().orElse(conversation.getContestRunId());
        }
    }

    private record RunUser(Long runId, Long userId) {
    }

    private record ParticipantIdentity(String account, String displayName) {
        private static final ParticipantIdentity EMPTY = new ParticipantIdentity("", "");
    }

    private static final class Aggregate {
        private long turnCount;
        private long promptTokens;
        private long completionTokens;
        private long interceptedCount;
        private final Set<String> conversationIds = new LinkedHashSet<>();
        private long legacyConversationCount;
        private boolean live;
        private boolean legacy;
        private boolean liveTokenPartial;
        private Instant lastUsedAt;

        private void addLiveTurn(AiContestAssistanceTurnEntity turn) {
            live = true;
            turnCount++;
            if (turn.getConversationId() != null && !turn.getConversationId().isBlank()) {
                conversationIds.add(turn.getConversationId());
            }
            if (ContestAiAssistanceTelemetryService.TOKEN_ACCOUNTING_PARTIAL.equals(turn.getTokenAccountingStatus())) {
                liveTokenPartial = true;
            }
            if ("PRIVATE_CONTEST_QUESTION".equals(turn.getInterceptType())
                    || "PUBLIC_FULL_CODE_REQUEST".equals(turn.getInterceptType())) {
                interceptedCount++;
            }
            mergeLastUsed(toInstantStatic(turn.getStartedAt()));
        }

        private void addModelUsage(AiContestAssistanceModelUsageEntity usage) {
            promptTokens += nonNegative(usage.getPromptTokens());
            completionTokens += nonNegative(usage.getCompletionTokens());
        }

        private void addLegacySnapshot(AiContestAssistanceLegacySnapshotEntity snapshot) {
            legacy = true;
            turnCount += nonNegative(snapshot.getTurnCount());
            promptTokens += nonNegative(snapshot.getPromptTokens());
            completionTokens += nonNegative(snapshot.getCompletionTokens());
            legacyConversationCount += nonNegative(snapshot.getConversationCount());
            interceptedCount += nonNegative(snapshot.getInterceptedCount());
            mergeLastUsed(toInstantStatic(snapshot.getLastUsedAt()));
        }

        private long conversationCount() {
            // V63 snapshots and V3 ledger cover disjoint periods; only live IDs need de-duplication.
            return conversationIds.size() + legacyConversationCount;
        }

        private String dataSource() {
            if (live && legacy) {
                return DATA_SOURCE_MIXED;
            }
            return live ? DATA_SOURCE_LIVE : DATA_SOURCE_HISTORICAL_SNAPSHOT;
        }

        private String tokenAccountingStatus() {
            if (legacy) {
                return live ? TOKEN_ACCOUNTING_PARTIAL : TOKEN_ACCOUNTING_ESTIMATED;
            }
            return liveTokenPartial ? TOKEN_ACCOUNTING_PARTIAL : TOKEN_ACCOUNTING_COMPLETE;
        }

        private void mergeLastUsed(Instant candidate) {
            if (candidate != null && (lastUsedAt == null || candidate.isAfter(lastUsedAt))) {
                lastUsedAt = candidate;
            }
        }

        private static long nonNegative(Long value) {
            return value == null ? 0L : Math.max(0L, value);
        }

        private static Instant toInstantStatic(LocalDateTime value) {
            return value == null ? null : value.atZone(ZONE).toInstant();
        }
    }
}
