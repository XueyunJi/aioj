package com.aioj.next.ai.agent.policy;

import com.aioj.next.ai.persistence.entity.AiGuardDecisionEntity;
import com.aioj.next.ai.persistence.mapper.AiGuardDecisionMapper;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.time.LocalDateTime;
import java.util.List;

/**
 * Persists every L1-L4 guard decision into ai_guard_decisions (design doc §5.6),
 * including PASS — this fixes the legacy "guard leaves no audit" hole and feeds
 * post-contest review plus the staff audit query API (P3-7).
 *
 * <p>Audit writes never break a turn: a persistence failure is logged as an
 * error and swallowed. Degraded guard evaluations (fail-closed path) are marked
 * with {@code degraded=true}.</p>
 */
@Service
public class GuardDecisionRecorder {

    private static final Logger log = LoggerFactory.getLogger(GuardDecisionRecorder.class);

    private final AiGuardDecisionMapper mapper;
    private final ObjectMapper objectMapper;

    public GuardDecisionRecorder(AiGuardDecisionMapper mapper, ObjectMapper objectMapper) {
        this.mapper = mapper;
        this.objectMapper = objectMapper;
    }

    /** One matched running-contest problem referenced by a guard decision. */
    public record MatchedProblemRef(Long problemId, Long contestId, Long contestRunId, Long contestProblemId,
                                    String visibility, String aiPolicyMode) {
    }

    public void record(String turnId, Long userId, String conversationId,
                       GuardLayer layer, GuardDecision decision,
                       List<MatchedProblemRef> matchedProblems,
                       String reasonCode, JsonNode detail,
                       boolean degraded, Integer latencyMs) {
        try {
            AiGuardDecisionEntity entity = new AiGuardDecisionEntity();
            entity.setTurnId(turnId);
            entity.setUserId(userId);
            entity.setConversationId(conversationId);
            entity.setLayer(layer.name());
            entity.setDecision(decision.name());
            entity.setMatchedProblemRefs(matchedProblems == null || matchedProblems.isEmpty()
                    ? null
                    : objectMapper.writeValueAsString(matchedProblems));
            entity.setContestRunId(firstContestRunId(matchedProblems));
            entity.setReasonCode(reasonCode);
            entity.setDetailJson(detail == null ? null : detail.toString());
            entity.setDegraded(degraded);
            entity.setLatencyMs(latencyMs);
            entity.setCreatedAt(LocalDateTime.now());
            mapper.insert(entity);
        } catch (Exception ex) {
            log.error("guard decision audit write failed turn={} layer={} decision={} error={}",
                    turnId, layer, decision, ex.toString());
        }
    }

    /** Run filter column (V62): first matched ref with a non-null contestRunId, else null. */
    private static Long firstContestRunId(List<MatchedProblemRef> matchedProblems) {
        if (matchedProblems == null) {
            return null;
        }
        return matchedProblems.stream()
                .filter(ref -> ref != null && ref.contestRunId() != null)
                .map(MatchedProblemRef::contestRunId)
                .findFirst()
                .orElse(null);
    }

    public void record(String turnId, Long userId, String conversationId,
                       GuardLayer layer, GuardDecision decision,
                       String reasonCode, JsonNode detail, boolean degraded, Integer latencyMs) {
        record(turnId, userId, conversationId, layer, decision, List.of(), reasonCode, detail, degraded, latencyMs);
    }
}
