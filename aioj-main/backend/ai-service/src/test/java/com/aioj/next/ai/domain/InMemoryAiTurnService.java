package com.aioj.next.ai.domain;

import com.aioj.next.ai.persistence.entity.AiTurnEntity;

import java.time.LocalDateTime;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicLong;

/**
 * Test double reproducing the ai_turns state machine (including CAS race semantics) in
 * memory, so turn-flow tests observe the same exactly-once behavior as the database
 * implementation without a database.
 */
public class InMemoryAiTurnService extends AiTurnService {
    private static final Set<String> NON_TERMINAL = Set.of(STATUS_RECEIVED, STATUS_BUILDING_CONTEXT, STATUS_GENERATING);

    private final Map<String, AiTurnEntity> byId = new ConcurrentHashMap<>();
    private final AtomicLong ids = new AtomicLong();

    public InMemoryAiTurnService() {
        super(null);
    }

    @Override
    public BeginTurnOutcome beginTurn(String conversationId, String clientTurnId) {
        String effective = clientTurnId == null || clientTurnId.isBlank()
                ? "srv-" + UUID.randomUUID()
                : clientTurnId;
        synchronized (byId) {
            long maxSeq = 0L;
            for (AiTurnEntity existing : byId.values()) {
                if (!existing.getConversationId().equals(conversationId)) {
                    continue;
                }
                if (existing.getClientTurnId().equals(effective)) {
                    return new BeginTurnOutcome(existing, false);
                }
                maxSeq = Math.max(maxSeq, existing.getTurnSeq());
            }
            AiTurnEntity turn = new AiTurnEntity();
            turn.setId("turn-" + ids.incrementAndGet());
            turn.setConversationId(conversationId);
            turn.setClientTurnId(effective);
            turn.setTurnSeq(maxSeq + 1);
            turn.setStatus(STATUS_RECEIVED);
            turn.setStateVersion(0L);
            turn.setCreatedAt(LocalDateTime.now());
            byId.put(turn.getId(), turn);
            return new BeginTurnOutcome(turn, true);
        }
    }

    @Override
    public boolean markBuildingContext(String turnId) {
        return cas(turnId, Set.of(STATUS_RECEIVED), STATUS_BUILDING_CONTEXT, null, null, false);
    }

    @Override
    public boolean advanceToGenerating(String turnId, String providerRequestId) {
        return cas(turnId, Set.of(STATUS_RECEIVED, STATUS_BUILDING_CONTEXT), STATUS_GENERATING, providerRequestId, null, false);
    }

    @Override
    public boolean completeTurn(String turnId) {
        return cas(turnId, Set.of(STATUS_GENERATING), STATUS_COMPLETED, null, null, true);
    }

    @Override
    public boolean failTurn(String turnId, String toStatus, String errorCode) {
        return cas(turnId, NON_TERMINAL, toStatus, null, errorCode, true);
    }

    @Override
    public boolean refuseTurn(String turnId) {
        return cas(turnId, NON_TERMINAL, STATUS_REFUSED, null, ERROR_CONTEST_GUARD_REFUSE, true);
    }

    @Override
    public void attachMessages(String turnId, Long userMessageId, Long assistantMessageId) {
        AiTurnEntity turn = byId.get(turnId);
        if (turn != null) {
            turn.setUserMessageId(userMessageId == null ? null : String.valueOf(userMessageId));
            turn.setAssistantMessageId(assistantMessageId == null ? null : String.valueOf(assistantMessageId));
        }
    }

    @Override
    public AiTurnEntity findById(String turnId) {
        return byId.get(turnId);
    }

    @Override
    public AiTurnEntity findByClientTurnId(String conversationId, String clientTurnId) {
        if (clientTurnId == null || clientTurnId.isBlank()) {
            return null;
        }
        return byId.values().stream()
                .filter(turn -> turn.getConversationId().equals(conversationId) && turn.getClientTurnId().equals(clientTurnId))
                .findFirst()
                .orElse(null);
    }

    private boolean cas(String turnId, Set<String> from, String to, String providerRequestId, String errorCode, boolean stampCompletedAt) {
        synchronized (byId) {
            AiTurnEntity turn = byId.get(turnId);
            if (turn == null || !from.contains(turn.getStatus())) {
                return false;
            }
            turn.setStatus(to);
            if (providerRequestId != null) {
                turn.setProviderRequestId(providerRequestId);
            }
            if (errorCode != null) {
                turn.setErrorCode(errorCode);
            }
            if (stampCompletedAt) {
                turn.setCompletedAt(LocalDateTime.now());
            }
            return true;
        }
    }
}
