package com.aioj.next.ai.domain;

import com.aioj.next.ai.persistence.entity.AiConversationEntity;
import com.aioj.next.ai.persistence.entity.AiMessageEntity;
import com.aioj.next.ai.persistence.mapper.AiConversationMapper;
import com.aioj.next.ai.persistence.mapper.AiMessageMapper;
import com.aioj.next.ai.domain.response.AiAssistantResponseNormalizer;
import com.aioj.next.common.api.PageResponse;
import com.aioj.next.common.error.DomainException;
import com.aioj.next.common.error.ErrorCode;
import com.aioj.next.contract.ai.AiChatMessageResponse;
import com.aioj.next.contract.ai.AiChatRequest;
import com.aioj.next.contract.ai.AiConversationCreateRequest;
import com.aioj.next.contract.ai.AiConversationResponse;
import com.aioj.next.contract.ai.AiConversationUpdateRequest;
import com.baomidou.mybatisplus.core.conditions.query.QueryWrapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.time.ZoneId;
import java.util.List;
import java.util.Locale;
import java.util.Objects;
import java.util.UUID;

@Service
public class AiConversationService {
    private static final Logger log = LoggerFactory.getLogger(AiConversationService.class);
    private static final ZoneId ZONE = ZoneId.systemDefault();
    public static final String MESSAGE_STATUS_RUNNING = "RUNNING";
    public static final String MESSAGE_STATUS_COMPLETED = "COMPLETED";
    public static final String MESSAGE_STATUS_FAILED = "FAILED";

    private final AiConversationMapper conversationMapper;
    private final AiMessageMapper messageMapper;
    private final AiAssistantResponseNormalizer responseNormalizer;

    public AiConversationService(
            AiConversationMapper conversationMapper,
            AiMessageMapper messageMapper,
            AiAssistantResponseNormalizer responseNormalizer
    ) {
        this.conversationMapper = conversationMapper;
        this.messageMapper = messageMapper;
        this.responseNormalizer = responseNormalizer;
    }

    @Transactional
    public AiConversationEntity resolveForWrite(Long userId, AiChatRequest request) {
        if (request.conversationId() == null || request.conversationId().isBlank()) {
            return create(userId, request.problemId(), request.message(), "chat", null, null,
                    request.mode(), request.contestContext());
        }
        AiConversationEntity conversation = conversationMapper.selectById(request.conversationId());
        if (conversation == null) {
            return create(userId, request.problemId(), request.message(), "chat", null, null,
                    request.mode(), request.contestContext());
        }
        ensureOwner(conversation, userId);
        if (conversation.getDeletedAt() != null) {
            return create(userId, request.problemId(), request.message(), "chat", null, null,
                    request.mode(), request.contestContext());
        }
        boolean changed = false;
        if (conversation.getProblemId() == null && request.problemId() != null) {
            conversation.setProblemId(request.problemId());
            conversation.setRecentProblemId(request.problemId());
            changed = true;
        }
        if ((conversation.getMode() == null || conversation.getMode().isBlank()) && request.mode() != null) {
            conversation.setMode(request.mode());
            changed = true;
        }
        if (request.contestContext() != null && conversation.getContestId() == null) {
            conversation.setContestId(request.contestContext().contestId());
            conversation.setContestRunId(request.contestContext().contestRunId());
            conversation.setContestProblemId(request.contestContext().contestProblemId());
            changed = true;
        }
        if (changed) {
            conversation.setUpdatedAt(LocalDateTime.now());
            conversationMapper.updateById(conversation);
        }
        return conversation;
    }

    /**
     * Binds a conversation to the contest run the server-side guard attributed this turn
     * to, so admin contest AI usage records include the conversation. Attribution is
     * decided per turn by the guard: an identical existing binding is a no-op, while a
     * different one self-heals to the current attribution (e.g. the previously bound run
     * has ended while a newer run is open) and is logged. Deliberately does not set
     * contestProblemId: the leak guard treats conversations bound to a contest problem as
     * legitimate in-contest assistance and would stop filtering follow-up messages in
     * this conversation.
     */
    @Transactional
    public void bindContestContext(AiConversationEntity conversation, Long contestId, Long contestRunId, Long problemId) {
        if (conversation == null || contestId == null) {
            return;
        }
        if (conversation.getContestId() != null) {
            if (Objects.equals(conversation.getContestId(), contestId)
                    && Objects.equals(conversation.getContestRunId(), contestRunId)) {
                return;
            }
            Long previousContestRunId = conversation.getContestRunId();
            conversation.setContestId(contestId);
            conversation.setContestRunId(contestRunId);
            if (problemId != null) {
                conversation.setProblemId(problemId);
                conversation.setRecentProblemId(problemId);
            }
            conversation.setUpdatedAt(LocalDateTime.now());
            conversationMapper.updateById(conversation);
            log.info("Rebound AI conversation contest attribution conversation={} fromRun={} toRun={} contest={}",
                    conversation.getId(), previousContestRunId, contestRunId, contestId);
            return;
        }
        conversation.setContestId(contestId);
        conversation.setContestRunId(contestRunId);
        if (problemId != null) {
            if (conversation.getProblemId() == null) {
                conversation.setProblemId(problemId);
            }
            if (conversation.getRecentProblemId() == null) {
                conversation.setRecentProblemId(problemId);
            }
        }
        conversation.setUpdatedAt(LocalDateTime.now());
        conversationMapper.updateById(conversation);
    }

    public void ensureOwner(String conversationId, Long userId) {
        AiConversationEntity conversation = conversationMapper.selectById(conversationId);
        if (conversation == null) {
            throw new DomainException(ErrorCode.NOT_FOUND, "AI conversation not found");
        }
        ensureOwner(conversation, userId);
    }

    @Transactional
    public AiConversationResponse create(Long userId, AiConversationCreateRequest request) {
        Long problemId = request == null ? null : request.problemId();
        String source = normalizeSource(request == null ? null : request.source());
        String sourceRefType = normalizeBlank(request == null ? null : request.sourceRefType());
        String sourceRefId = normalizeBlank(request == null ? null : request.sourceRefId());
        if (problemId != null && sourceRefType != null && sourceRefId != null) {
            AiConversationEntity existing = conversationMapper.selectOne(new QueryWrapper<AiConversationEntity>()
                    .eq("user_id", userId)
                    .eq("problem_id", problemId)
                    .eq("source", source)
                    .eq("source_ref_type", sourceRefType)
                    .eq("source_ref_id", sourceRefId)
                    .isNull("deleted_at")
                    .orderByDesc("updated_at")
                    .last("LIMIT 1"));
            if (existing != null) {
                existing.setRecentProblemId(problemId);
                existing.setUpdatedAt(LocalDateTime.now());
                conversationMapper.updateById(existing);
                return toConversationResponse(existing);
            }
        }
        AiConversationEntity conversation = create(
                userId,
                problemId,
                request == null ? null : request.title(),
                source,
                sourceRefType,
                sourceRefId,
                request == null ? null : request.mode(),
                null
        );
        return toConversationResponse(conversation);
    }

    @Transactional
    public AiConversationResponse update(Long userId, String conversationId, AiConversationUpdateRequest request) {
        AiConversationEntity conversation = conversationMapper.selectById(conversationId);
        if (conversation == null) {
            throw new DomainException(ErrorCode.NOT_FOUND, "AI conversation not found");
        }
        ensureOwner(conversation, userId);
        if (request != null) {
            if (request.title() != null && !request.title().isBlank()) {
                conversation.setTitle(request.title().trim());
            }
            if (request.mode() != null && !request.mode().isBlank()) {
                conversation.setMode(request.mode().trim());
            }
            if (request.recentProblemId() != null) {
                conversation.setRecentProblemId(request.recentProblemId());
            }
        }
        conversation.setUpdatedAt(LocalDateTime.now());
        conversationMapper.updateById(conversation);
        return toConversationResponse(conversation);
    }

    @Transactional
    public String updateAutomaticMode(Long userId, String conversationId, AiChatRequest request, AiCompletion completion) {
        AiConversationEntity conversation = conversationMapper.selectById(conversationId);
        if (conversation == null) {
            return null;
        }
        ensureOwner(conversation, userId);
        String automaticMode = automaticModeFor(request, completion);
        if (automaticMode == null || automaticMode.isBlank()) {
            return conversation.getMode();
        }
        if (!automaticMode.equals(conversation.getMode())) {
            conversation.setMode(automaticMode);
            conversation.setUpdatedAt(LocalDateTime.now());
            conversationMapper.updateById(conversation);
        }
        return automaticMode;
    }

    @Transactional
    public AiChatMessageResponse appendMessage(
            String conversationId,
            Long userId,
            Long problemId,
            String role,
            String content,
            String model,
            String clientMessageId,
            String contextSnapshot
    ) {
        return appendMessage(conversationId, userId, problemId, role, content, model, clientMessageId,
                contextSnapshot, null);
    }

    @Transactional
    public AiChatMessageResponse appendMessage(
            String conversationId,
            Long userId,
            Long problemId,
            String role,
            String content,
            String model,
            String clientMessageId,
            String contextSnapshot,
            AiChatRequest.ContestContext contestContext
    ) {
        return appendMessageWithStatus(
                conversationId,
                userId,
                problemId,
                role,
                content,
                model,
                clientMessageId,
                contextSnapshot,
                MESSAGE_STATUS_COMPLETED,
                null,
                contestContext
        );
    }

    @Transactional
    public AiChatMessageResponse appendMessageWithStatus(
            String conversationId,
            Long userId,
            Long problemId,
            String role,
            String content,
            String model,
            String clientMessageId,
            String contextSnapshot,
            String status,
            String errorMessage
    ) {
        return appendMessageWithStatus(conversationId, userId, problemId, role, content, model, clientMessageId,
                contextSnapshot, status, errorMessage, null);
    }

    @Transactional
    public AiChatMessageResponse appendMessageWithStatus(
            String conversationId,
            Long userId,
            Long problemId,
            String role,
            String content,
            String model,
            String clientMessageId,
            String contextSnapshot,
            String status,
            String errorMessage,
            AiChatRequest.ContestContext contestContext
    ) {
        LocalDateTime now = LocalDateTime.now();
        String normalizedStatus = normalizeStatus(status);
        AiMessageEntity message = new AiMessageEntity();
        message.setConversationId(conversationId);
        message.setUserId(userId);
        message.setProblemId(problemId);
        if (contestContext != null) {
            message.setContestId(contestContext.contestId());
            message.setContestRunId(contestContext.contestRunId());
            message.setContestProblemId(contestContext.contestProblemId());
        }
        message.setClientMessageId(clientMessageId);
        message.setRole(role);
        message.setContent(content == null ? "" : content);
        message.setModel(model);
        message.setContextSnapshot(contextSnapshot);
        message.setStatus(normalizedStatus);
        message.setErrorMessage(errorMessage);
        message.setCreatedAt(now);
        message.setUpdatedAt(now);
        if (MESSAGE_STATUS_COMPLETED.equals(normalizedStatus) || MESSAGE_STATUS_FAILED.equals(normalizedStatus)) {
            message.setCompletedAt(now);
        }
        messageMapper.insert(message);

        AiConversationEntity conversation = conversationMapper.selectById(conversationId);
        if (conversation != null) {
            conversation.setUpdatedAt(now);
            conversationMapper.updateById(conversation);
        }
        return toMessageResponse(message);
    }

    @Transactional
    public AiChatMessageResponse completeMessage(Long userId, Long messageId, String content, String model) {
        AiMessageEntity message = messageMapper.selectById(messageId);
        ensureMessageOwner(message, userId);
        LocalDateTime now = LocalDateTime.now();
        message.setContent(content == null ? "" : content);
        message.setModel(model);
        message.setStatus(MESSAGE_STATUS_COMPLETED);
        message.setErrorMessage(null);
        message.setUpdatedAt(now);
        message.setCompletedAt(now);
        messageMapper.updateById(message);
        touchConversation(message.getConversationId(), now);
        return toMessageResponse(message);
    }

    @Transactional
    public AiChatMessageResponse failMessage(Long userId, Long messageId, String errorMessage) {
        AiMessageEntity message = messageMapper.selectById(messageId);
        ensureMessageOwner(message, userId);
        LocalDateTime now = LocalDateTime.now();
        message.setStatus(MESSAGE_STATUS_FAILED);
        message.setErrorMessage(truncate(errorMessage == null || errorMessage.isBlank() ? "AI provider call failed" : errorMessage, 500));
        message.setUpdatedAt(now);
        message.setCompletedAt(now);
        messageMapper.updateById(message);
        touchConversation(message.getConversationId(), now);
        return toMessageResponse(message);
    }

    public PageResponse<AiConversationResponse> list(Long userId, long page, long pageSize, Long problemId,
                                                     String source, String sourceRefType, String sourceRefId,
                                                     String keyword, boolean includeDeleted) {
        long current = Math.max(1, page);
        long size = Math.min(Math.max(1, pageSize), 100);
        long offset = (current - 1) * size;
        QueryWrapper<AiConversationEntity> countQuery = new QueryWrapper<AiConversationEntity>()
                .eq("user_id", userId);
        applyListFilters(countQuery, problemId, source, sourceRefType, sourceRefId, keyword, includeDeleted);
        long total = conversationMapper.selectCount(countQuery);
        QueryWrapper<AiConversationEntity> listQuery = new QueryWrapper<AiConversationEntity>()
                .eq("user_id", userId);
        applyListFilters(listQuery, problemId, source, sourceRefType, sourceRefId, keyword, includeDeleted);
        List<AiConversationResponse> records = conversationMapper.selectList(listQuery
                        .orderByDesc("updated_at")
                        .last("LIMIT " + size + " OFFSET " + offset))
                .stream()
                .map(this::toConversationResponse)
                .toList();
        return new PageResponse<>(records, total, current, size);
    }

    public List<AiChatMessageResponse> messages(Long userId, String conversationId) {
        ensureOwner(conversationId, userId);
        return messageMapper.selectList(new QueryWrapper<AiMessageEntity>()
                        .eq("conversation_id", conversationId)
                        .orderByAsc("created_at"))
                .stream()
                .map(this::toMessageResponse)
                .toList();
    }

    public AiChatMessageResponse getMessage(Long userId, Long messageId) {
        AiMessageEntity message = messageMapper.selectById(messageId);
        ensureMessageOwner(message, userId);
        return toMessageResponse(message);
    }

    public AiConversationEntity getOwnedConversation(Long userId, String conversationId) {
        AiConversationEntity conversation = conversationMapper.selectById(conversationId);
        if (conversation == null) {
            throw new DomainException(ErrorCode.NOT_FOUND, "AI conversation not found");
        }
        ensureOwner(conversation, userId);
        return conversation;
    }

    @Transactional
    public void delete(Long userId, String conversationId) {
        AiConversationEntity conversation = conversationMapper.selectById(conversationId);
        if (conversation == null) {
            throw new DomainException(ErrorCode.NOT_FOUND, "AI conversation not found");
        }
        ensureOwner(conversation, userId);
        conversation.setDeletedAt(LocalDateTime.now());
        conversation.setUpdatedAt(LocalDateTime.now());
        conversationMapper.updateById(conversation);
    }

    @Transactional
    public void batchDelete(Long userId, List<String> conversationIds) {
        if (conversationIds == null || conversationIds.isEmpty()) {
            return;
        }
        for (String conversationId : conversationIds.stream().distinct().toList()) {
            delete(userId, conversationId);
        }
    }

    private void applyListFilters(QueryWrapper<AiConversationEntity> query, Long problemId, String source,
                                  String sourceRefType, String sourceRefId, String keyword, boolean includeDeleted) {
        if (!includeDeleted) {
            query.isNull("deleted_at");
        }
        if (problemId != null) {
            query.eq("problem_id", problemId);
        }
        if (source != null && !source.isBlank()) {
            query.eq("source", source.trim());
        }
        if (sourceRefType != null && !sourceRefType.isBlank()) {
            query.eq("source_ref_type", sourceRefType.trim());
        }
        if (sourceRefId != null && !sourceRefId.isBlank()) {
            query.eq("source_ref_id", sourceRefId.trim());
        }
        if (keyword != null && !keyword.isBlank()) {
            query.like("title", keyword.trim());
        }
    }

    private AiConversationEntity create(Long userId, Long problemId, String titleSeed, String source,
                                        String sourceRefType, String sourceRefId, String mode,
                                        AiChatRequest.ContestContext contestContext) {
        LocalDateTime now = LocalDateTime.now();
        AiConversationEntity conversation = new AiConversationEntity();
        conversation.setId(UUID.randomUUID().toString());
        conversation.setUserId(userId);
        conversation.setProblemId(problemId);
        conversation.setRecentProblemId(problemId);
        if (contestContext != null) {
            conversation.setContestId(contestContext.contestId());
            conversation.setContestRunId(contestContext.contestRunId());
            conversation.setContestProblemId(contestContext.contestProblemId());
        }
        conversation.setSource(normalizeSource(source));
        conversation.setSourceRefType(normalizeBlank(sourceRefType));
        conversation.setSourceRefId(normalizeBlank(sourceRefId));
        conversation.setMode(mode == null || mode.isBlank() ? "assist" : mode.trim());
        conversation.setTitle(titleFrom(titleSeed));
        conversation.setCreatedAt(now);
        conversation.setUpdatedAt(now);
        conversationMapper.insert(conversation);
        return conversation;
    }

    private String normalizeSource(String source) {
        return source == null || source.isBlank() ? "ai_tutor" : source.trim();
    }

    private String normalizeBlank(String value) {
        return value == null || value.isBlank() ? null : value.trim();
    }

    private void ensureOwner(AiConversationEntity conversation, Long userId) {
        if (!userId.equals(conversation.getUserId())) {
            throw new DomainException(ErrorCode.FORBIDDEN, "AI conversation belongs to another user");
        }
    }

    private AiConversationResponse toConversationResponse(AiConversationEntity conversation) {
        return new AiConversationResponse(
                conversation.getId(),
                conversation.getProblemId(),
                conversation.getTitle(),
                conversation.getSource(),
                conversation.getSourceRefType(),
                conversation.getSourceRefId(),
                conversation.getMode(),
                conversation.getSummary(),
                conversation.getRecentProblemId(),
                messageCount(conversation.getId()),
                latestMessagePreview(conversation.getId()),
                conversation.getDeletedAt() == null ? null : conversation.getDeletedAt().atZone(ZONE).toInstant(),
                conversation.getCreatedAt().atZone(ZONE).toInstant(),
                conversation.getUpdatedAt().atZone(ZONE).toInstant()
        );
    }

    private AiChatMessageResponse toMessageResponse(AiMessageEntity message) {
        LocalDateTime createdAt = message.getCreatedAt() == null ? LocalDateTime.now() : message.getCreatedAt();
        LocalDateTime completedAt = message.getCompletedAt();
        return new AiChatMessageResponse(
                message.getId(),
                message.getConversationId(),
                message.getProblemId(),
                message.getClientMessageId(),
                message.getRole(),
                responseNormalizer.normalizeVisibleContent(message.getRole(), message.getContent()),
                message.getModel(),
                normalizeStatus(message.getStatus()),
                message.getErrorMessage(),
                createdAt.atZone(ZONE).toInstant(),
                completedAt == null ? null : completedAt.atZone(ZONE).toInstant()
        );
    }

    private void ensureMessageOwner(AiMessageEntity message, Long userId) {
        if (message == null) {
            throw new DomainException(ErrorCode.NOT_FOUND, "AI message not found");
        }
        if (!userId.equals(message.getUserId())) {
            throw new DomainException(ErrorCode.FORBIDDEN, "AI message belongs to another user");
        }
    }

    private void touchConversation(String conversationId, LocalDateTime updatedAt) {
        AiConversationEntity conversation = conversationMapper.selectById(conversationId);
        if (conversation != null) {
            conversation.setUpdatedAt(updatedAt);
            conversationMapper.updateById(conversation);
        }
    }

    private String normalizeStatus(String status) {
        if (status == null || status.isBlank()) {
            return MESSAGE_STATUS_COMPLETED;
        }
        String normalized = status.trim().toUpperCase(Locale.ROOT);
        return switch (normalized) {
            case MESSAGE_STATUS_RUNNING, MESSAGE_STATUS_FAILED -> normalized;
            default -> MESSAGE_STATUS_COMPLETED;
        };
    }

    private String titleFrom(String message) {
        if (message == null || message.isBlank()) {
            return "New AI conversation";
        }
        String normalized = message.strip().replaceAll("\\s+", " ");
        return normalized.length() <= 80 ? normalized : normalized.substring(0, 80);
    }

    private String automaticModeFor(AiChatRequest request, AiCompletion completion) {
        String text = (normalize(request == null ? null : request.message()) + " "
                + normalize(request == null ? null : request.mode()) + " "
                + normalize(completion == null ? null : completion.teachingDecision()) + " "
                + normalize(completion == null ? null : completion.stuckLayer()))
                .toLowerCase(Locale.ROOT);
        if (containsAny(text, "wa", "re", "tle", "ce", "debug", "报错", "错误", "为什么错", "运行失败")) {
            return "debug";
        }
        if ((containsAny(text, "代码", "程序", "实现", "code")
                && containsAny(text, "讲解", "解释", "思路", "explain", "walkthrough"))
                || containsAny(text, "完整代码", "可提交代码", "先给代码", "code_first")) {
            return "code_explain";
        }
        if (containsAny(text, "边界", "单调", "boundary", "edge")) {
            return "boundary";
        }
        if (containsAny(text, "概念", "原理", "为什么", "concept")) {
            return "concept";
        }
        if (completion != null && completion.hasClarification()
                && containsAny(text, "题面", "输入输出", "约束", "clarify", "clarification")) {
            return "clarify";
        }
        if (containsAny(text, "提示", "怎么入手", "hint", "socratic")) {
            return "hint";
        }
        return "qa";
    }

    private boolean containsAny(String text, String... needles) {
        if (text == null || text.isBlank()) {
            return false;
        }
        for (String needle : needles) {
            if (needle != null && !needle.isBlank() && text.contains(needle.toLowerCase(Locale.ROOT))) {
                return true;
            }
        }
        return false;
    }

    private String normalize(String value) {
        return value == null ? "" : value.strip();
    }

    private String truncate(String value, int max) {
        if (value == null || value.length() <= max) {
            return value;
        }
        return value.substring(0, max);
    }

    private long messageCount(String conversationId) {
        return messageMapper.selectCount(new QueryWrapper<AiMessageEntity>()
                .eq("conversation_id", conversationId));
    }

    private String latestMessagePreview(String conversationId) {
        AiMessageEntity message = messageMapper.selectOne(new QueryWrapper<AiMessageEntity>()
                .eq("conversation_id", conversationId)
                .isNotNull("content")
                .ne("content", "")
                .orderByDesc("created_at")
                .last("LIMIT 1"));
        if (message == null || message.getContent() == null) {
            return "";
        }
        String visibleContent = responseNormalizer.normalizeVisibleContent(message.getRole(), message.getContent());
        String normalized = visibleContent.replaceAll("\\s+", " ").trim();
        return normalized.length() <= 96 ? normalized : normalized.substring(0, 96);
    }
}
