package com.aioj.next.ai.agent.policy;

import com.aioj.next.ai.domain.OperationAuditWriter;
import com.aioj.next.ai.domain.response.GuardDecisionAuditItem;
import com.aioj.next.ai.domain.response.GuardTurnMessagesResponse;
import com.aioj.next.ai.persistence.entity.AiGuardDecisionEntity;
import com.aioj.next.ai.persistence.entity.AiMessageEntity;
import com.aioj.next.ai.persistence.entity.AiTurnEntity;
import com.aioj.next.ai.persistence.mapper.AiGuardDecisionMapper;
import com.aioj.next.ai.persistence.mapper.AiMessageMapper;
import com.aioj.next.ai.domain.AiTurnService;
import com.aioj.next.common.api.PageResponse;
import com.aioj.next.common.error.DomainException;
import com.aioj.next.common.error.ErrorCode;
import com.aioj.next.common.security.SecuritySupport;
import com.baomidou.mybatisplus.core.conditions.query.QueryWrapper;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.time.Instant;
import java.time.LocalDateTime;
import java.time.OffsetDateTime;
import java.time.ZoneId;
import java.time.format.DateTimeParseException;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * P3-7 staff audit query over ai_guard_decisions (design doc §5.6): paged listing
 * filtered by run/user/layer/decision/degraded/time, plus turn -> ai_messages
 * linkage so teachers/admins can review what a guarded turn actually said.
 * Staff reads are themselves audited via OperationAuditWriter.
 */
@Service
public class GuardDecisionAuditService {

    private static final Logger log = LoggerFactory.getLogger(GuardDecisionAuditService.class);
    private static final long PAGE_SIZE_MAX = 100;
    private static final ZoneId ZONE = ZoneId.systemDefault();

    private final AiGuardDecisionMapper guardDecisionMapper;
    private final AiTurnService aiTurnService;
    private final AiMessageMapper messageMapper;
    private final OperationAuditWriter auditWriter;
    private final ObjectMapper objectMapper;

    public GuardDecisionAuditService(AiGuardDecisionMapper guardDecisionMapper,
                                     AiTurnService aiTurnService,
                                     AiMessageMapper messageMapper,
                                     OperationAuditWriter auditWriter,
                                     ObjectMapper objectMapper) {
        this.guardDecisionMapper = guardDecisionMapper;
        this.aiTurnService = aiTurnService;
        this.messageMapper = messageMapper;
        this.auditWriter = auditWriter;
        this.objectMapper = objectMapper;
    }

    public PageResponse<GuardDecisionAuditItem> list(
            Long contestRunId,
            Long userId,
            String layer,
            String decision,
            Boolean degraded,
            String from,
            String to,
            long page,
            long pageSize
    ) {
        String layerValue = parseEnum(GuardLayer.class, layer, "layer");
        String decisionValue = parseEnum(GuardDecision.class, decision, "decision");
        LocalDateTime fromTime = parseTime(from, "from");
        LocalDateTime toTime = parseTime(to, "to");
        if (fromTime != null && toTime != null && fromTime.isAfter(toTime)) {
            throw new DomainException(ErrorCode.BAD_REQUEST, "from must not be after to");
        }
        long current = Math.max(1, page);
        long size = Math.min(Math.max(1, pageSize), PAGE_SIZE_MAX);
        long offset = (current - 1) * size;

        QueryWrapper<AiGuardDecisionEntity> countQuery = buildFilter(
                contestRunId, userId, layerValue, decisionValue, degraded, fromTime, toTime);
        long total = guardDecisionMapper.selectCount(countQuery);

        QueryWrapper<AiGuardDecisionEntity> pageQuery = buildFilter(
                contestRunId, userId, layerValue, decisionValue, degraded, fromTime, toTime);
        pageQuery.orderByDesc("created_at").orderByDesc("id");
        pageQuery.last("LIMIT " + size + " OFFSET " + offset);
        List<GuardDecisionAuditItem> records = guardDecisionMapper.selectList(pageQuery)
                .stream()
                .map(this::toItem)
                .toList();
        return new PageResponse<>(records, total, current, size);
    }

    public GuardTurnMessagesResponse turnMessages(String turnId) {
        AiTurnEntity turn = aiTurnService.findById(turnId);
        if (turn == null) {
            throw new DomainException(ErrorCode.NOT_FOUND, "AI turn not found");
        }
        AiMessageEntity userMessage = loadMessage(turn.getUserMessageId());
        AiMessageEntity assistantMessage = loadMessage(turn.getAssistantMessageId());
        Long turnUserId = userMessage != null ? userMessage.getUserId()
                : assistantMessage != null ? assistantMessage.getUserId() : null;
        recordTurnMessagesView(turn, turnUserId);
        return new GuardTurnMessagesResponse(
                turn.getId(),
                turn.getConversationId(),
                turnUserId,
                turn.getStatus(),
                toInstant(turn.getCreatedAt()),
                toTurnMessage(userMessage),
                toTurnMessage(assistantMessage)
        );
    }

    private QueryWrapper<AiGuardDecisionEntity> buildFilter(
            Long contestRunId, Long userId, String layer, String decision,
            Boolean degraded, LocalDateTime from, LocalDateTime to) {
        QueryWrapper<AiGuardDecisionEntity> query = new QueryWrapper<>();
        if (contestRunId != null) {
            query.eq("contest_run_id", contestRunId);
        }
        if (userId != null) {
            query.eq("user_id", userId);
        }
        if (layer != null) {
            query.eq("layer", layer);
        }
        if (decision != null) {
            query.eq("decision", decision);
        }
        if (degraded != null) {
            query.eq("degraded", degraded);
        }
        if (from != null) {
            query.ge("created_at", from);
        }
        if (to != null) {
            query.le("created_at", to);
        }
        return query;
    }

    private GuardDecisionAuditItem toItem(AiGuardDecisionEntity entity) {
        return new GuardDecisionAuditItem(
                String.valueOf(entity.getId()),
                entity.getTurnId(),
                entity.getUserId(),
                entity.getConversationId(),
                entity.getContestRunId(),
                entity.getLayer(),
                entity.getDecision(),
                entity.getReasonCode(),
                parseJson(entity.getMatchedProblemRefs()),
                parseJson(entity.getDetailJson()),
                entity.getDegraded(),
                entity.getLatencyMs(),
                toInstant(entity.getCreatedAt())
        );
    }

    private GuardTurnMessagesResponse.GuardTurnMessage toTurnMessage(AiMessageEntity message) {
        if (message == null) {
            return null;
        }
        return new GuardTurnMessagesResponse.GuardTurnMessage(
                String.valueOf(message.getId()),
                message.getRole(),
                message.getContent(),
                message.getModel(),
                toInstant(message.getCreatedAt())
        );
    }

    private AiMessageEntity loadMessage(String messageId) {
        if (messageId == null || messageId.isBlank()) {
            return null;
        }
        try {
            return messageMapper.selectById(Long.parseLong(messageId.trim()));
        } catch (NumberFormatException ex) {
            return null;
        }
    }

    private JsonNode parseJson(String json) {
        if (json == null || json.isBlank()) {
            return null;
        }
        try {
            return objectMapper.readTree(json);
        } catch (Exception ex) {
            log.warn("guard decision audit row contains unparseable JSON: {}", ex.toString());
            return null;
        }
    }

    private static <E extends Enum<E>> String parseEnum(Class<E> type, String value, String field) {
        if (value == null || value.isBlank()) {
            return null;
        }
        try {
            return Enum.valueOf(type, value.trim()).name();
        } catch (IllegalArgumentException ex) {
            throw new DomainException(ErrorCode.BAD_REQUEST, "Invalid " + field + ": " + value);
        }
    }

    private static LocalDateTime parseTime(String value, String field) {
        if (value == null || value.isBlank()) {
            return null;
        }
        String trimmed = value.trim();
        try {
            return LocalDateTime.ofInstant(OffsetDateTime.parse(trimmed).toInstant(), ZONE);
        } catch (DateTimeParseException ignored) {
            // fall through to local date-time form
        }
        try {
            return LocalDateTime.parse(trimmed);
        } catch (DateTimeParseException ex) {
            throw new DomainException(ErrorCode.BAD_REQUEST, "Invalid " + field + " time: " + value);
        }
    }

    private void recordTurnMessagesView(AiTurnEntity turn, Long targetUserId) {
        try {
            Map<String, Object> summary = new LinkedHashMap<>();
            summary.put("turnId", turn.getId());
            summary.put("conversationId", turn.getConversationId());
            auditWriter.record(
                    "AI_GUARD_TURN_MESSAGES_VIEW",
                    "AI_GUARD_TURN",
                    null,
                    "VIEWED",
                    summary,
                    SecuritySupport.currentUserId(),
                    null,
                    null,
                    targetUserId
            );
        } catch (RuntimeException ex) {
            // Viewing must not fail because audit persistence is unavailable.
            log.warn("Guard turn messages view audit failed turn={} error={}", turn.getId(), ex.toString());
        }
    }

    private static Instant toInstant(LocalDateTime value) {
        return value == null ? null : value.atZone(ZONE).toInstant();
    }
}
