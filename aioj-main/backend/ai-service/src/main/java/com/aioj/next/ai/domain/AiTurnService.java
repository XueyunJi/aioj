package com.aioj.next.ai.domain;

import com.aioj.next.ai.persistence.entity.AiTurnEntity;
import com.aioj.next.ai.persistence.mapper.AiTurnMapper;
import com.aioj.next.common.error.DomainException;
import com.aioj.next.common.error.ErrorCode;
import com.baomidou.mybatisplus.core.conditions.query.QueryWrapper;
import com.baomidou.mybatisplus.core.conditions.update.UpdateWrapper;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.stereotype.Service;

import java.time.LocalDateTime;
import java.util.List;
import java.util.UUID;

/**
 * Aggregate root for one AI chat turn. The database row (never in-memory state) is the
 * consistency source for the exactly-once guarantees of a turn:
 * user message persist / provider call / quota record / assistant message completion.
 */
@Service
public class AiTurnService {
    public static final String STATUS_RECEIVED = "RECEIVED";
    public static final String STATUS_BUILDING_CONTEXT = "BUILDING_CONTEXT";
    public static final String STATUS_GENERATING = "GENERATING";
    public static final String STATUS_COMPLETED = "COMPLETED";
    public static final String STATUS_FAILED_RETRYABLE = "FAILED_RETRYABLE";
    public static final String STATUS_FAILED_FINAL = "FAILED_FINAL";
    public static final String STATUS_CANCELLED = "CANCELLED";
    /** Terminal state for a turn the contest guard refused before any provider work. */
    public static final String STATUS_REFUSED = "REFUSED";

    /** Error code stored on REFUSED turns: the contest leak guard blocked the turn. */
    public static final String ERROR_CONTEST_GUARD_REFUSE = "CONTEST_GUARD_REFUSE";

    private static final int BEGIN_TURN_MAX_ATTEMPTS = 3;

    private final AiTurnMapper turnMapper;

    public AiTurnService(AiTurnMapper turnMapper) {
        this.turnMapper = turnMapper;
    }

    /**
     * Short-transaction turn creation: turn_seq = per-conversation max + 1.
     * A hit on uk_turn_client means a duplicate submission and returns the existing turn;
     * a hit on uk_turn_seq means a concurrent insert for the same conversation and is retried.
     */
    public BeginTurnOutcome beginTurn(String conversationId, String clientTurnId) {
        String effectiveClientTurnId = normalizeClientTurnId(clientTurnId);
        for (int attempt = 0; attempt < BEGIN_TURN_MAX_ATTEMPTS; attempt++) {
            AiTurnEntity turn = new AiTurnEntity();
            turn.setId(UUID.randomUUID().toString().replace("-", ""));
            turn.setConversationId(conversationId);
            turn.setClientTurnId(effectiveClientTurnId);
            turn.setTurnSeq(nextTurnSeq(conversationId));
            turn.setStatus(STATUS_RECEIVED);
            turn.setStateVersion(0L);
            turn.setCreatedAt(LocalDateTime.now());
            try {
                turnMapper.insert(turn);
                return new BeginTurnOutcome(turn, true);
            } catch (DuplicateKeyException ex) {
                AiTurnEntity existing = findByClientTurnId(conversationId, effectiveClientTurnId);
                if (existing != null) {
                    return new BeginTurnOutcome(existing, false);
                }
                // uk_turn_seq race with a concurrent turn of the same conversation: retry with a fresh max.
            }
        }
        throw new DomainException(ErrorCode.CONFLICT, "Concurrent AI turn creation conflict");
    }

    public boolean markBuildingContext(String turnId) {
        return turnMapper.update(null, new UpdateWrapper<AiTurnEntity>()
                .eq("id", turnId)
                .eq("status", STATUS_RECEIVED)
                .set("status", STATUS_BUILDING_CONTEXT)) > 0;
    }

    /**
     * CAS non-terminal -> GENERATING and records the provider request id (turnId).
     * Returns false when the turn already reached a terminal state (e.g. timeout cleanup won).
     */
    public boolean advanceToGenerating(String turnId, String providerRequestId) {
        return turnMapper.update(null, new UpdateWrapper<AiTurnEntity>()
                .eq("id", turnId)
                .in("status", STATUS_RECEIVED, STATUS_BUILDING_CONTEXT)
                .set("status", STATUS_GENERATING)
                .set("provider_request_id", providerRequestId)) > 0;
    }

    /**
     * CAS GENERATING -> COMPLETED. Only the winner of this CAS may record usage and
     * complete the assistant message (exactly-once against the timeout cleanup).
     */
    public boolean completeTurn(String turnId) {
        return turnMapper.update(null, new UpdateWrapper<AiTurnEntity>()
                .eq("id", turnId)
                .eq("status", STATUS_GENERATING)
                .set("status", STATUS_COMPLETED)
                .set("completed_at", LocalDateTime.now())) > 0;
    }

    /**
     * CAS any non-terminal state -> a terminal failure state. Only the winner may record
     * the failed usage and mark the assistant message failed.
     */
    public boolean failTurn(String turnId, String toStatus, String errorCode) {
        return turnMapper.update(null, new UpdateWrapper<AiTurnEntity>()
                .eq("id", turnId)
                .in("status", STATUS_RECEIVED, STATUS_BUILDING_CONTEXT, STATUS_GENERATING)
                .set("status", toStatus)
                .set("error_code", errorCode)
                .set("completed_at", LocalDateTime.now())) > 0;
    }

    /**
     * CAS any non-terminal state -> REFUSED for a turn the contest guard blocked before
     * any provider work. Idempotent: returns false once the turn reached a terminal state,
     * so a same-clientTurnId retry never moves or duplicates the row.
     */
    public boolean refuseTurn(String turnId) {
        return turnMapper.update(null, new UpdateWrapper<AiTurnEntity>()
                .eq("id", turnId)
                .in("status", STATUS_RECEIVED, STATUS_BUILDING_CONTEXT, STATUS_GENERATING)
                .set("status", STATUS_REFUSED)
                .set("error_code", ERROR_CONTEST_GUARD_REFUSE)
                .set("completed_at", LocalDateTime.now())) > 0;
    }

    public void attachMessages(String turnId, Long userMessageId, Long assistantMessageId) {
        turnMapper.update(null, new UpdateWrapper<AiTurnEntity>()
                .eq("id", turnId)
                .set("user_message_id", userMessageId == null ? null : String.valueOf(userMessageId))
                .set("assistant_message_id", assistantMessageId == null ? null : String.valueOf(assistantMessageId)));
    }

    public void attachPolicySnapshot(String turnId, String policySnapshotId) {
        turnMapper.update(null, new UpdateWrapper<AiTurnEntity>()
                .eq("id", turnId)
                .set("policy_snapshot_id", policySnapshotId));
    }

    public AiTurnEntity findById(String turnId) {
        return turnMapper.selectById(turnId);
    }

    public AiTurnEntity findByClientTurnId(String conversationId, String clientTurnId) {
        if (clientTurnId == null || clientTurnId.isBlank()) {
            return null;
        }
        return turnMapper.selectOne(new QueryWrapper<AiTurnEntity>()
                .eq("conversation_id", conversationId)
                .eq("client_turn_id", clientTurnId)
                .last("LIMIT 1"));
    }

    public static boolean isTerminal(String status) {
        return STATUS_COMPLETED.equals(status)
                || STATUS_FAILED_RETRYABLE.equals(status)
                || STATUS_FAILED_FINAL.equals(status)
                || STATUS_CANCELLED.equals(status)
                || STATUS_REFUSED.equals(status);
    }

    private long nextTurnSeq(String conversationId) {
        List<Object> rows = turnMapper.selectObjs(new QueryWrapper<AiTurnEntity>()
                .select("MAX(turn_seq)")
                .eq("conversation_id", conversationId));
        Object max = rows == null || rows.isEmpty() ? null : rows.get(0);
        long current = max instanceof Number number ? number.longValue() : 0L;
        return current + 1;
    }

    private String normalizeClientTurnId(String clientTurnId) {
        if (clientTurnId == null || clientTurnId.isBlank()) {
            // Legacy clients without a client id can never deduplicate; give them a unique key.
            return "srv-" + UUID.randomUUID();
        }
        String normalized = clientTurnId.trim();
        return normalized.length() <= 80 ? normalized : normalized.substring(0, 80);
    }

    public record BeginTurnOutcome(AiTurnEntity turn, boolean created) {
    }
}
