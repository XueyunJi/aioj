package com.aioj.next.ai.agent.profile;

import com.aioj.next.ai.persistence.entity.AiProfileSignalEntity;
import com.aioj.next.ai.persistence.mapper.AiProfileSignalMapper;
import com.baomidou.mybatisplus.core.conditions.query.QueryWrapper;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * Agent Core V3 P2-2: persists curator-proposed learning-profile signals into
 * ai_profile_signals as PENDING evidence (V61, design doc §6.6). Writes are idempotent
 * per (source_type, source_id): a turn that already has signals is skipped wholesale,
 * so a retried curate job never double-writes.
 */
@Service
public class ProfileSignalIngestionService {

    public static final String SOURCE_TYPE_CHAT_TURN = "CHAT_TURN";
    public static final String SOURCE_TYPE_JUDGED_SUBMISSION = "JUDGED_SUBMISSION";

    /** Signal lifecycle: PENDING on write, AGGREGATED once the PROFILE_AGGREGATE job folds it into ai_learning_profile. */
    public static final String STATUS_PENDING = "PENDING";
    public static final String STATUS_AGGREGATED = "AGGREGATED";

    private static final Logger log = LoggerFactory.getLogger(ProfileSignalIngestionService.class);

    private final AiProfileSignalMapper signalMapper;
    private final ObjectMapper objectMapper;

    public ProfileSignalIngestionService(AiProfileSignalMapper signalMapper, ObjectMapper objectMapper) {
        this.signalMapper = signalMapper;
        this.objectMapper = objectMapper;
    }

    public record SignalProposal(
            String signal,
            String signalType,
            String knowledgeNode,
            String polarity,
            double score
    ) {
    }

    public int recordChatTurnSignals(Long userId, String turnId, List<SignalProposal> proposals, String sourceType) {
        if (turnId == null || turnId.isBlank()) {
            return 0;
        }
        return recordSignals(userId, turnId, proposals, sourceType);
    }

    /**
     * P2-6: judged-submission sidecar signals (source_type=JUDGED_SUBMISSION, source_id=submissionId).
     * Same (source_type, source_id) wholesale idempotency as chat-turn signals, so a retried
     * judged-submission analysis never double-writes.
     */
    public int recordJudgedSubmissionSignals(Long userId, Long submissionId, List<SignalProposal> proposals) {
        if (submissionId == null) {
            return 0;
        }
        return recordSignals(userId, String.valueOf(submissionId), proposals, SOURCE_TYPE_JUDGED_SUBMISSION);
    }

    private int recordSignals(Long userId, String sourceId, List<SignalProposal> proposals, String sourceType) {
        if (userId == null || proposals == null || proposals.isEmpty()) {
            return 0;
        }
        Long existing = signalMapper.selectCount(new QueryWrapper<AiProfileSignalEntity>()
                .eq("source_type", sourceType)
                .eq("source_id", sourceId));
        if (existing != null && existing > 0) {
            log.debug("profile signals already recorded sourceType={} sourceId={}, skip", sourceType, sourceId);
            return 0;
        }
        int inserted = 0;
        for (SignalProposal proposal : proposals) {
            if (proposal == null || proposal.signal() == null || proposal.signal().isBlank()) {
                continue;
            }
            AiProfileSignalEntity entity = new AiProfileSignalEntity();
            entity.setUserId(userId);
            entity.setSignalType(normalizeUpper(proposal.signalType(), "GENERIC_OBSERVATION"));
            // Normalize at write time (KnowledgeNodeNormalizer) so ai_profile_signals.knowledge_node
            // matches the aggregator's profile_key and profile.search can join by equality.
            String knowledgeNode = KnowledgeNodeNormalizer.normalize(proposal.knowledgeNode());
            entity.setKnowledgeNode(knowledgeNode.isEmpty() ? null : knowledgeNode);
            entity.setPolarity(normalizeUpper(proposal.polarity(), "NEUTRAL"));
            entity.setScore(BigDecimal.valueOf(clamp(proposal.score())).setScale(4, RoundingMode.HALF_UP));
            entity.setSourceType(sourceType);
            entity.setSourceId(sourceId);
            entity.setPayloadJson(payloadJson(proposal.signal().trim()));
            entity.setStatus(STATUS_PENDING);
            entity.setCreatedAt(LocalDateTime.now());
            signalMapper.insert(entity);
            inserted++;
        }
        return inserted;
    }

    private String normalizeUpper(String value, String fallback) {
        String normalized = value == null ? "" : value.trim().toUpperCase(Locale.ROOT);
        return normalized.isEmpty() ? fallback : normalized;
    }

    private double clamp(double score) {
        if (Double.isNaN(score) || Double.isInfinite(score)) {
            return 0;
        }
        return Math.min(1, Math.max(0, score));
    }

    private String payloadJson(String signal) {
        try {
            return objectMapper.writeValueAsString(Map.of("signal", signal));
        } catch (Exception ex) {
            return "{}";
        }
    }
}
