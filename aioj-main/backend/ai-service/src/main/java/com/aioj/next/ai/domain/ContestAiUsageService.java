package com.aioj.next.ai.domain;

import com.aioj.next.ai.persistence.entity.AiConversationEntity;
import com.aioj.next.ai.persistence.entity.AiMessageEntity;
import com.aioj.next.ai.persistence.entity.AiUsageRecordEntity;
import com.aioj.next.ai.persistence.entity.OperationAuditEventEntity;
import com.aioj.next.ai.persistence.mapper.AiConversationMapper;
import com.aioj.next.ai.persistence.mapper.AiMessageMapper;
import com.aioj.next.ai.persistence.mapper.AiUsageRecordMapper;
import com.aioj.next.ai.persistence.mapper.OperationAuditEventMapper;
import com.aioj.next.common.error.DomainException;
import com.aioj.next.common.error.ErrorCode;
import com.aioj.next.contract.ai.AdminContestAiConversationSummary;
import com.aioj.next.contract.ai.AdminContestAiMessageResponse;
import com.aioj.next.contract.ai.AdminContestAiUsageSummary;
import com.aioj.next.contract.ai.ContestParticipantProfile;
import com.aioj.next.contract.ai.ProblemTitleInfo;
import com.aioj.next.contract.contest.ContestRunWindow;
import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import org.springframework.stereotype.Service;

import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Admin-facing view of students' in-contest AI assistance: per-student usage
 * summaries, their conversations and the full message transcripts.
 */
@Service
public class ContestAiUsageService {
    private static final ZoneId ZONE = ZoneId.systemDefault();
    private static final String ACTION_GUARD_EVALUATED = "AI_CONTEST_GUARD_EVALUATED";
    private static final String ACTION_GUARD_DEGRADED = "AI_CONTEST_GUARD_DEGRADED";
    private static final List<String> BLOCKED_ACTIONS =
            List.of("AI_CONTEST_REQUEST_BLOCKED", "AI_CONTEST_LEAK_BLOCKED", "AI_CONTEST_LEAK_PARTICIPANT_BLOCKED");
    private static final List<String> TRACKED_ACTIONS = List.of(
            "AI_CONTEST_REQUEST_BLOCKED", "AI_CONTEST_LEAK_BLOCKED", "AI_CONTEST_LEAK_PARTICIPANT_BLOCKED",
            ACTION_GUARD_EVALUATED, ACTION_GUARD_DEGRADED);
    private static final long WINDOW_GRACE_SECONDS = 60;

    private final AiUsageRecordMapper usageMapper;
    private final AiConversationMapper conversationMapper;
    private final AiMessageMapper messageMapper;
    private final OperationAuditEventMapper auditMapper;
    private final ProblemServiceClient problemServiceClient;
    private final OperationAuditWriter auditWriter;

    public ContestAiUsageService(AiUsageRecordMapper usageMapper,
                                 AiConversationMapper conversationMapper,
                                 AiMessageMapper messageMapper,
                                 OperationAuditEventMapper auditMapper,
                                 ProblemServiceClient problemServiceClient,
                                 OperationAuditWriter auditWriter) {
        this.usageMapper = usageMapper;
        this.conversationMapper = conversationMapper;
        this.messageMapper = messageMapper;
        this.auditMapper = auditMapper;
        this.problemServiceClient = problemServiceClient;
        this.auditWriter = auditWriter;
    }

    public List<AdminContestAiUsageSummary> summaries(Long contestId, Long contestRunId) {
        Map<Long, RunWindow> windows = runWindows(contestId);
        List<AiUsageRecordEntity> usage = usageMapper.selectList(new LambdaQueryWrapper<AiUsageRecordEntity>()
                .eq(AiUsageRecordEntity::getContestId, contestId)
                .eq(contestRunId != null, AiUsageRecordEntity::getContestRunId, contestRunId));
        List<AiConversationEntity> conversations = conversationMapper.selectList(contestConversationQuery(contestId, contestRunId, null));
        List<OperationAuditEventEntity> events = auditMapper.selectList(new LambdaQueryWrapper<OperationAuditEventEntity>()
                .eq(OperationAuditEventEntity::getContestId, contestId)
                .eq(contestRunId != null, OperationAuditEventEntity::getContestRunId, contestRunId)
                .in(OperationAuditEventEntity::getAction, TRACKED_ACTIONS));
        Map<String, Long> runIdByConversation = new HashMap<>();
        for (AiConversationEntity conversation : conversations) {
            runIdByConversation.put(conversation.getId(), conversation.getContestRunId());
        }
        List<AiMessageEntity> conversationMessages = conversations.isEmpty()
                ? List.of()
                : messageMapper.selectList(new LambdaQueryWrapper<AiMessageEntity>()
                        .in(AiMessageEntity::getConversationId, runIdByConversation.keySet()));
        Map<String, Instant> windowLastMessageByConversation = new HashMap<>();
        for (AiMessageEntity message : conversationMessages) {
            Instant at = toInstant(message.getCreatedAt());
            if (!withinWindow(windows, runIdByConversation.get(message.getConversationId()), at)) {
                continue;
            }
            windowLastMessageByConversation.merge(message.getConversationId(), at,
                    (left, right) -> left != null && right != null && left.isAfter(right) ? left : right);
        }

        Map<Long, String> accounts = new HashMap<>();
        Map<Long, String> displayNames = new HashMap<>();
        for (ContestParticipantProfile profile : problemServiceClient.contestParticipantProfiles(contestId)) {
            if (profile.userId() == null) {
                continue;
            }
            if (contestRunId == null || contestRunId.equals(profile.contestRunId())) {
                accounts.putIfAbsent(profile.userId(), profile.account());
                displayNames.putIfAbsent(profile.userId(), profile.displayName());
            }
        }

        Map<Long, long[]> usageByUser = new HashMap<>();
        Map<Long, Instant> lastUsedByUser = new HashMap<>();
        Set<Long> userIds = new HashSet<>();
        for (AiUsageRecordEntity record : usage) {
            if (record.getUserId() == null) {
                continue;
            }
            Instant createdAt = toInstant(record.getCreatedAt());
            if (!withinWindow(windows, record.getContestRunId(), createdAt)) {
                continue;
            }
            userIds.add(record.getUserId());
            long[] totals = usageByUser.computeIfAbsent(record.getUserId(), key -> new long[3]);
            totals[0] += 1;
            totals[1] += record.getPromptTokens() == null ? 0 : record.getPromptTokens();
            totals[2] += record.getCompletionTokens() == null ? 0 : record.getCompletionTokens();
            if (createdAt != null) {
                lastUsedByUser.merge(record.getUserId(), createdAt, (left, right) -> left.isAfter(right) ? left : right);
            }
        }

        Map<Long, Long> conversationCountByUser = new HashMap<>();
        for (AiConversationEntity conversation : conversations) {
            if (conversation.getUserId() == null) {
                continue;
            }
            // Only conversations with at least one in-window message count toward the contest.
            Instant windowLast = windowLastMessageByConversation.get(conversation.getId());
            if (windowLast == null) {
                continue;
            }
            userIds.add(conversation.getUserId());
            conversationCountByUser.merge(conversation.getUserId(), 1L, Long::sum);
            lastUsedByUser.merge(conversation.getUserId(), windowLast, (left, right) -> left.isAfter(right) ? left : right);
        }

        Map<Long, Long> blockedCountByUser = new HashMap<>();
        Map<Long, Long> evaluatedCountByUser = new HashMap<>();
        Map<Long, Long> constrainCountByUser = new HashMap<>();
        Map<Long, Long> refuseCountByUser = new HashMap<>();
        Map<Long, Long> degradedCountByUser = new HashMap<>();
        for (OperationAuditEventEntity event : events) {
            if (event.getActorUserId() == null) {
                continue;
            }
            if (!withinWindow(windows, event.getContestRunId(), event.getCreatedAt())) {
                continue;
            }
            userIds.add(event.getActorUserId());
            if (ACTION_GUARD_EVALUATED.equals(event.getAction())) {
                // ContestTurnGuard writes the decision name (PASS/CONSTRAIN/REFUSE) into status.
                evaluatedCountByUser.merge(event.getActorUserId(), 1L, Long::sum);
                if ("CONSTRAIN".equals(event.getStatus())) {
                    constrainCountByUser.merge(event.getActorUserId(), 1L, Long::sum);
                } else if ("REFUSE".equals(event.getStatus())) {
                    refuseCountByUser.merge(event.getActorUserId(), 1L, Long::sum);
                }
            } else if (ACTION_GUARD_DEGRADED.equals(event.getAction())) {
                degradedCountByUser.merge(event.getActorUserId(), 1L, Long::sum);
            } else if (BLOCKED_ACTIONS.contains(event.getAction())) {
                blockedCountByUser.merge(event.getActorUserId(), 1L, Long::sum);
            }
        }

        List<AdminContestAiUsageSummary> summaries = new ArrayList<>();
        for (Long userId : userIds) {
            long[] totals = usageByUser.getOrDefault(userId, new long[3]);
            summaries.add(new AdminContestAiUsageSummary(
                    userId,
                    accounts.getOrDefault(userId, ""),
                    displayNames.getOrDefault(userId, ""),
                    totals[0],
                    totals[1],
                    totals[2],
                    conversationCountByUser.getOrDefault(userId, 0L),
                    blockedCountByUser.getOrDefault(userId, 0L),
                    evaluatedCountByUser.getOrDefault(userId, 0L),
                    constrainCountByUser.getOrDefault(userId, 0L),
                    refuseCountByUser.getOrDefault(userId, 0L),
                    degradedCountByUser.getOrDefault(userId, 0L),
                    lastUsedByUser.get(userId)
            ));
        }
        summaries.sort(Comparator
                .comparing((AdminContestAiUsageSummary summary) -> summary.lastUsedAt() == null
                        ? Instant.EPOCH : summary.lastUsedAt()).reversed()
                .thenComparing(AdminContestAiUsageSummary::userId));
        return summaries;
    }

    public List<AdminContestAiConversationSummary> conversations(Long contestId, Long contestRunId, Long userId) {
        Map<Long, RunWindow> windows = runWindows(contestId);
        List<AiConversationEntity> conversations = conversationMapper
                .selectList(contestConversationQuery(contestId, contestRunId, userId));
        if (conversations.isEmpty()) {
            return List.of();
        }
        Map<String, Long> runIdByConversation = new HashMap<>();
        for (AiConversationEntity conversation : conversations) {
            runIdByConversation.put(conversation.getId(), conversation.getContestRunId());
        }
        List<String> conversationIds = conversations.stream().map(AiConversationEntity::getId).toList();
        List<AiMessageEntity> allMessages = messageMapper.selectList(new LambdaQueryWrapper<AiMessageEntity>()
                .in(AiMessageEntity::getConversationId, conversationIds));
        // Only messages inside the contest time window (start..end + grace) are tracked.
        List<AiMessageEntity> messages = new ArrayList<>();
        for (AiMessageEntity message : allMessages) {
            if (withinWindow(windows, runIdByConversation.get(message.getConversationId()), toInstant(message.getCreatedAt()))) {
                messages.add(message);
            }
        }
        Map<String, Long> countByConversation = new HashMap<>();
        Map<String, Instant> lastMessageByConversation = new HashMap<>();
        for (AiMessageEntity message : messages) {
            countByConversation.merge(message.getConversationId(), 1L, Long::sum);
            Instant createdAt = toInstant(message.getCreatedAt());
            if (createdAt != null) {
                lastMessageByConversation.merge(message.getConversationId(), createdAt,
                        (left, right) -> left.isAfter(right) ? left : right);
            }
        }
        List<AdminContestAiConversationSummary> summaries = new ArrayList<>();
        Set<Long> problemIds = new HashSet<>();
        for (AiConversationEntity conversation : conversations) {
            if (conversation.getProblemId() != null) {
                problemIds.add(conversation.getProblemId());
            } else if (conversation.getRecentProblemId() != null) {
                problemIds.add(conversation.getRecentProblemId());
            }
        }
        for (AiMessageEntity message : messages) {
            if (message.getProblemId() != null) {
                problemIds.add(message.getProblemId());
            }
        }
        Map<Long, ProblemTitleInfo> titlesById = new HashMap<>();
        for (ProblemTitleInfo info : problemServiceClient.problemTitles(new ArrayList<>(problemIds))) {
            titlesById.putIfAbsent(info.id(), info);
        }
        for (AiConversationEntity conversation : conversations) {
            if (countByConversation.getOrDefault(conversation.getId(), 0L) == 0L) {
                // No in-window messages: the conversation is not part of this contest's records.
                continue;
            }
            Long problemId = conversation.getProblemId() != null
                    ? conversation.getProblemId()
                    : conversation.getRecentProblemId();
            if (problemId == null) {
                for (AiMessageEntity message : messages) {
                    if (conversation.getId().equals(message.getConversationId()) && message.getProblemId() != null) {
                        problemId = message.getProblemId();
                        break;
                    }
                }
            }
            ProblemTitleInfo titleInfo = problemId == null ? null : titlesById.get(problemId);
            summaries.add(new AdminContestAiConversationSummary(
                    conversation.getId(),
                    conversation.getTitle(),
                    conversation.getMode(),
                    conversation.getContestRunId(),
                    conversation.getContestProblemId(),
                    problemId,
                    titleInfo == null ? null : titleInfo.title(),
                    countByConversation.getOrDefault(conversation.getId(), 0L),
                    lastMessageByConversation.getOrDefault(conversation.getId(), toInstant(conversation.getUpdatedAt()))
            ));
        }
        summaries.sort(Comparator
                .comparing((AdminContestAiConversationSummary summary) -> summary.lastMessageAt() == null
                        ? Instant.EPOCH : summary.lastMessageAt()).reversed());
        return summaries;
    }

    public List<AdminContestAiMessageResponse> messages(Long contestId, Long userId, String conversationId, Long viewerUserId) {
        AiConversationEntity conversation = conversationMapper.selectById(conversationId);
        if (conversation == null
                || conversation.getDeletedAt() != null
                || !contestId.equals(conversation.getContestId())
                || !userId.equals(conversation.getUserId())) {
            throw new DomainException(ErrorCode.NOT_FOUND, "AI conversation not found");
        }
        List<AiMessageEntity> messages = messageMapper.selectList(new LambdaQueryWrapper<AiMessageEntity>()
                .eq(AiMessageEntity::getConversationId, conversationId)
                .orderByAsc(AiMessageEntity::getCreatedAt)
                .orderByAsc(AiMessageEntity::getId));
        Map<Long, RunWindow> windows = runWindows(contestId);
        recordTranscriptView(contestId, userId, conversationId, viewerUserId);
        return messages.stream()
                .filter(message -> withinWindow(windows, conversation.getContestRunId(), toInstant(message.getCreatedAt())))
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

    private LambdaQueryWrapper<AiConversationEntity> contestConversationQuery(Long contestId, Long contestRunId, Long userId) {
        return new LambdaQueryWrapper<AiConversationEntity>()
                .eq(AiConversationEntity::getContestId, contestId)
                .eq(contestRunId != null, AiConversationEntity::getContestRunId, contestRunId)
                .eq(userId != null, AiConversationEntity::getUserId, userId)
                .isNull(AiConversationEntity::getDeletedAt);
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
            // Viewing the transcript must not fail because audit persistence is unavailable.
        }
    }

    private Instant toInstant(LocalDateTime value) {
        return value == null ? null : value.atZone(ZONE).toInstant();
    }

    private record RunWindow(Instant start, Instant endInclusive) {
    }

    /** Run windows keyed by runId; empty map means the fetch failed and filtering is skipped. */
    private Map<Long, RunWindow> runWindows(Long contestId) {
        Map<Long, RunWindow> windows = new HashMap<>();
        for (ContestRunWindow window : problemServiceClient.contestRunWindows(contestId)) {
            if (window.runId() == null || window.startAt() == null || window.endAt() == null) {
                continue;
            }
            windows.put(window.runId(), new RunWindow(window.startAt(), window.endAt().plusSeconds(WINDOW_GRACE_SECONDS)));
        }
        return windows;
    }

    private boolean withinWindow(Map<Long, RunWindow> windows, Long runId, Instant at) {
        if (windows.isEmpty()) {
            return true;
        }
        if (at == null) {
            return false;
        }
        if (runId != null) {
            RunWindow window = windows.get(runId);
            return window != null && !at.isBefore(window.start()) && !at.isAfter(window.endInclusive());
        }
        // Records without a run id are accepted inside any window of this contest.
        for (RunWindow window : windows.values()) {
            if (!at.isBefore(window.start()) && !at.isAfter(window.endInclusive())) {
                return true;
            }
        }
        return false;
    }
}
