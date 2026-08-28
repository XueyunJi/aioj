package com.aioj.next.ai.agent.profile;

import com.aioj.next.ai.persistence.entity.AiLearningProfileEntity;
import com.aioj.next.ai.persistence.entity.AiLearningProfileEvidenceEntity;
import com.aioj.next.ai.persistence.entity.AiProfileSignalEntity;
import com.aioj.next.ai.persistence.mapper.AiLearningProfileEvidenceMapper;
import com.aioj.next.ai.persistence.mapper.AiLearningProfileMapper;
import com.aioj.next.ai.persistence.mapper.AiProfileSignalMapper;
import com.baomidou.mybatisplus.core.conditions.query.QueryWrapper;
import com.baomidou.mybatisplus.core.conditions.update.UpdateWrapper;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * Agent Core V3 P2-6: folds PENDING ai_profile_signals rows into ai_learning_profile
 * (design doc §6.6/§7.2). Runs inside the PROFILE_AGGREGATE async job (V60).
 * <p>
 * Frozen semantics (Q5/Q6): aggregation never revives a profile whose state is
 * RESOLVED/SUPERSEDED/DISABLED or whose disabledAt is set — the row is left untouched,
 * no evidence is written, and the signals are still marked AGGREGATED. New rows always
 * start as CANDIDATE (same as the legacy judged-analysis upsert); activation stays on
 * the user-confirmation path. Signal marking uses a PENDING-guarded CAS update so a
 * concurrent double-run never re-processes a row, which makes job retries naturally
 * idempotent together with the legacy (profile_id, evidence_type, source_type, source_id)
 * evidence dedupe.
 */
@Service
public class ProfileAggregationService {

    public static final String EVIDENCE_TYPE_PROFILE_SIGNAL = "PROFILE_SIGNAL";

    private static final Logger log = LoggerFactory.getLogger(ProfileAggregationService.class);

    static final int BATCH_SIZE = 100;
    static final int MAX_SIGNALS_PER_RUN = 500;
    private static final int MAX_LABEL_LENGTH = 200;
    private static final int MAX_SUMMARY_LENGTH = 500;
    /** Weight cap of the rolling confidence average: existing evidence never outweighs 10:1. */
    private static final int EVIDENCE_WEIGHT_CAP = 10;

    private static final String CATEGORY_WEAKNESS = "weakness";
    private static final String CATEGORY_MASTERY = "mastery";
    private static final String CATEGORY_PROGRESS = "progress";
    private static final String CATEGORY_OBSERVATION = "observation";

    private static final String STATE_CANDIDATE = "CANDIDATE";
    private static final String STATE_RESOLVED = "RESOLVED";
    private static final String STATE_SUPERSEDED = "SUPERSEDED";
    private static final String STATE_DISABLED = "DISABLED";

    private final AiProfileSignalMapper signalMapper;
    private final AiLearningProfileMapper profileMapper;
    private final AiLearningProfileEvidenceMapper evidenceMapper;
    private final ObjectMapper objectMapper;

    public ProfileAggregationService(
            AiProfileSignalMapper signalMapper,
            AiLearningProfileMapper profileMapper,
            AiLearningProfileEvidenceMapper evidenceMapper,
            ObjectMapper objectMapper
    ) {
        this.signalMapper = signalMapper;
        this.profileMapper = profileMapper;
        this.evidenceMapper = evidenceMapper;
        this.objectMapper = objectMapper;
    }

    /**
     * Aggregates up to {@link #MAX_SIGNALS_PER_RUN} PENDING signals of one user, oldest first,
     * in batches of {@link #BATCH_SIZE}. Returns the number of signals scanned. Any exception
     * propagates so the async job retries; already-AGGREGATED rows are never re-scanned.
     */
    public int aggregatePendingSignals(Long userId) {
        if (userId == null) {
            return 0;
        }
        int processed = 0;
        while (processed < MAX_SIGNALS_PER_RUN) {
            int limit = Math.min(BATCH_SIZE, MAX_SIGNALS_PER_RUN - processed);
            List<AiProfileSignalEntity> batch = signalMapper.selectList(new QueryWrapper<AiProfileSignalEntity>()
                    .eq("user_id", userId)
                    .eq("status", ProfileSignalIngestionService.STATUS_PENDING)
                    .orderByAsc("created_at")
                    .last("LIMIT " + limit));
            if (batch.isEmpty()) {
                break;
            }
            processBatch(userId, batch);
            processed += batch.size();
        }
        return processed;
    }

    private void processBatch(Long userId, List<AiProfileSignalEntity> batch) {
        List<AiProfileSignalEntity> skipped = new ArrayList<>();
        Map<GroupKey, List<AiProfileSignalEntity>> groups = new LinkedHashMap<>();
        for (AiProfileSignalEntity signal : batch) {
            String node = signal.getKnowledgeNode();
            if (node == null || node.isBlank() || KnowledgeNodeNormalizer.normalize(node).isEmpty()) {
                // Never aggregate garbage rows into profiles; still close them out.
                skipped.add(signal);
                continue;
            }
            groups.computeIfAbsent(new GroupKey(categoryFor(signal.getSignalType()), node.trim()), key -> new ArrayList<>())
                    .add(signal);
        }
        markAggregated(skipped);
        for (Map.Entry<GroupKey, List<AiProfileSignalEntity>> entry : groups.entrySet()) {
            aggregateGroup(userId, entry.getKey(), entry.getValue());
        }
    }

    private void aggregateGroup(Long userId, GroupKey key, List<AiProfileSignalEntity> signals) {
        String profileKey = KnowledgeNodeNormalizer.normalize(key.knowledgeNode());
        LocalDateTime now = LocalDateTime.now();
        AiLearningProfileEntity profile = profileMapper.selectOne(new QueryWrapper<AiLearningProfileEntity>()
                .eq("user_id", userId)
                .eq("category", key.category())
                .eq("profile_key", profileKey)
                .isNull("deleted_at")
                .last("LIMIT 1"));
        if (profile != null && isTerminal(profile)) {
            markAggregated(signals);
            return;
        }
        double groupAverage = averageScore(signals);
        if (profile == null) {
            profile = new AiLearningProfileEntity();
            profile.userId = userId;
            profile.category = key.category();
            profile.profileKey = profileKey;
            profile.label = truncate(key.knowledgeNode(), MAX_LABEL_LENGTH);
            profile.state = STATE_CANDIDATE;
            profile.confidence = decimal(groupAverage);
            profile.evidenceCount = 0;
            profile.createdAt = now;
            profile.updatedAt = now;
            profileMapper.insert(profile);
        } else {
            int weight = Math.min(profile.evidenceCount == null ? 0 : Math.max(0, profile.evidenceCount), EVIDENCE_WEIGHT_CAP);
            double oldConfidence = profile.confidence == null ? 0.0 : profile.confidence.doubleValue();
            profile.confidence = decimal(clamp((oldConfidence * weight + groupAverage) / (weight + 1)));
        }
        for (AiProfileSignalEntity signal : signals) {
            writeEvidenceIfAbsent(userId, profile, signal, now);
        }
        syncEvidenceStats(profile);
        profile.updatedAt = now;
        profileMapper.updateById(profile);
        markAggregated(signals);
    }

    private void writeEvidenceIfAbsent(Long userId, AiLearningProfileEntity profile, AiProfileSignalEntity signal, LocalDateTime now) {
        String sourceType = signal.getSourceType() == null ? "UNKNOWN" : signal.getSourceType();
        String sourceId = signal.getSourceId();
        AiLearningProfileEvidenceEntity existing = evidenceMapper.selectOne(new QueryWrapper<AiLearningProfileEvidenceEntity>()
                .eq("profile_id", profile.id)
                .eq("evidence_type", EVIDENCE_TYPE_PROFILE_SIGNAL)
                .eq("source_type", sourceType)
                .eq(sourceId != null, "source_id", sourceId)
                .isNull(sourceId == null, "source_id")
                .last("LIMIT 1"));
        if (existing != null) {
            return; // idempotent: this signal source already contributed evidence
        }
        AiLearningProfileEvidenceEntity evidence = new AiLearningProfileEvidenceEntity();
        evidence.userId = userId;
        evidence.profileId = profile.id;
        evidence.evidenceType = EVIDENCE_TYPE_PROFILE_SIGNAL;
        evidence.sourceType = sourceType;
        evidence.sourceId = sourceId;
        evidence.summary = truncate(signalText(signal), MAX_SUMMARY_LENGTH);
        evidence.confidence = signal.getScore() == null ? BigDecimal.ZERO : signal.getScore();
        evidence.createdAt = now;
        evidenceMapper.insert(evidence);
    }

    /** Mirrors the legacy syncEvidenceStats semantics (count + latest created_at). */
    private void syncEvidenceStats(AiLearningProfileEntity profile) {
        Long count = evidenceMapper.selectCount(new QueryWrapper<AiLearningProfileEvidenceEntity>()
                .eq("profile_id", profile.id));
        profile.evidenceCount = count == null ? 0 : (int) Math.min(Integer.MAX_VALUE, count);
        AiLearningProfileEvidenceEntity latest = evidenceMapper.selectOne(new QueryWrapper<AiLearningProfileEvidenceEntity>()
                .eq("profile_id", profile.id)
                .orderByDesc("created_at")
                .last("LIMIT 1"));
        profile.lastEvidenceAt = latest == null ? null : latest.createdAt;
    }

    /** PENDING-guarded CAS: a concurrent run that already flipped the row wins; ours is a no-op. */
    private void markAggregated(List<AiProfileSignalEntity> signals) {
        for (AiProfileSignalEntity signal : signals) {
            if (signal.getId() == null) {
                continue;
            }
            signalMapper.update(null, new UpdateWrapper<AiProfileSignalEntity>()
                    .eq("id", signal.getId())
                    .eq("status", ProfileSignalIngestionService.STATUS_PENDING)
                    .set("status", ProfileSignalIngestionService.STATUS_AGGREGATED));
        }
    }

    private String categoryFor(String signalType) {
        String normalized = signalType == null ? "" : signalType.trim().toUpperCase(Locale.ROOT);
        return switch (normalized) {
            case "WEAKNESS", "MISCONCEPTION" -> CATEGORY_WEAKNESS;
            case "MASTERY" -> CATEGORY_MASTERY;
            case "PROGRESS" -> CATEGORY_PROGRESS;
            default -> CATEGORY_OBSERVATION;
        };
    }

    private String signalText(AiProfileSignalEntity signal) {
        String payload = signal.getPayloadJson();
        if (payload != null && !payload.isBlank()) {
            try {
                JsonNode node = objectMapper.readTree(payload);
                String text = node.path("signal").asText("");
                if (!text.isBlank()) {
                    return text;
                }
            } catch (Exception ex) {
                log.debug("profile signal payload not parseable id={} error={}", signal.getId(), ex.toString());
            }
        }
        return signal.getSignalType() == null ? "profile signal" : signal.getSignalType().toLowerCase(Locale.ROOT);
    }

    private double averageScore(List<AiProfileSignalEntity> signals) {
        double sum = 0.0;
        int count = 0;
        for (AiProfileSignalEntity signal : signals) {
            sum += signal.getScore() == null ? 0.0 : signal.getScore().doubleValue();
            count++;
        }
        return count == 0 ? 0.0 : clamp(sum / count);
    }

    private double clamp(double value) {
        if (Double.isNaN(value) || Double.isInfinite(value)) {
            return 0.0;
        }
        return Math.min(1.0, Math.max(0.0, value));
    }

    private BigDecimal decimal(double value) {
        return BigDecimal.valueOf(value).setScale(4, RoundingMode.HALF_UP);
    }

    private boolean isTerminal(AiLearningProfileEntity profile) {
        if (profile.disabledAt != null) {
            return true;
        }
        String state = profile.state == null ? "" : profile.state.trim().toUpperCase(Locale.ROOT);
        return STATE_RESOLVED.equals(state) || STATE_SUPERSEDED.equals(state) || STATE_DISABLED.equals(state);
    }

    private String truncate(String value, int maxLength) {
        if (value == null) {
            return "";
        }
        String normalized = value.trim();
        return normalized.length() <= maxLength ? normalized : normalized.substring(0, maxLength);
    }

    private record GroupKey(String category, String knowledgeNode) {
    }
}
