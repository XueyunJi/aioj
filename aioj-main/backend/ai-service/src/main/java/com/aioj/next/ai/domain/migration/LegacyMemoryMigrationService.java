package com.aioj.next.ai.domain.migration;

import com.aioj.next.ai.persistence.entity.AiLearningProfileEntity;
import com.aioj.next.ai.persistence.entity.AiLearningWeaknessEntity;
import com.aioj.next.ai.persistence.entity.AiMemoryClaimEntity;
import com.aioj.next.ai.persistence.entity.AiProfileSignalEntity;
import com.aioj.next.ai.persistence.entity.AiUserMemoryEntity;
import com.aioj.next.ai.persistence.mapper.AiLearningProfileMapper;
import com.aioj.next.ai.persistence.mapper.AiLearningWeaknessMapper;
import com.aioj.next.ai.persistence.mapper.AiMemoryClaimMapper;
import com.aioj.next.ai.persistence.mapper.AiProfileSignalMapper;
import com.aioj.next.ai.persistence.mapper.AiUserMemoryMapper;
import com.baomidou.mybatisplus.core.conditions.query.QueryWrapper;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;

/**
 * Agent Core V3 round M: one-shot, re-runnable adaptation of legacy
 * {@code ai_user_memories} rows into {@code ai_memory_claims} (B3 frozen
 * decisions, design doc section "M：数据迁移轮").
 *
 * Frozen mapping rules:
 * <ul>
 *   <li>M-Q2: rows already referenced by any claim (legacy_memory_id) are left
 *       untouched, including one-to-many fan-out created by the merge pipeline.</li>
 *   <li>M-Q3: a SUPERSEDED legacy row whose linked claim is still ACTIVE is only
 *       reported (STATUS_MISMATCH), never auto-corrected.</li>
 *   <li>M-Q4a: memory_type=content rows are generic teaching snippets, not user
 *       memories; they are skipped and listed in the report.</li>
 *   <li>M-Q5: every user is migrated uniformly, including eval accounts.</li>
 *   <li>M-Q6: new claims get the deterministic key {@code legacy_<id>}, making
 *       re-runs idempotent via the legacy_memory_id lookup.</li>
 *   <li>M-Q7: profile/weakness/signals tables are reconciliation reads only.</li>
 * </ul>
 *
 * Migrated claims bypass the candidate/merge pipeline on purpose: legacy rows
 * were already user-facing memory, so they are inserted as final claims with
 * source_mode=LEGACY_MIGRATION. The merge pipeline may still consolidate them
 * with future candidates later.
 */
@Service
public class LegacyMemoryMigrationService {

    public static final String SOURCE_MODE = "LEGACY_MIGRATION";
    public static final String MIGRATION_FLAG_JSON_FIELD = "legacyMemoryType";

    private static final Logger log = LoggerFactory.getLogger(LegacyMemoryMigrationService.class);
    private static final int BATCH_SIZE = 500;
    private static final String STATUS_ACTIVE = "ACTIVE";
    private static final String STATUS_SUPERSEDED = "SUPERSEDED";
    private static final String TYPE_CONTENT = "content";

    private static final Map<String, String> TYPE_TO_CATEGORY = Map.of(
            "rule", "RULE",
            "preferred_language", "PREFERENCE",
            "guidance_preference", "PREFERENCE",
            "answer_style_preference", "PREFERENCE",
            "teaching_style", "PREFERENCE",
            "weakness", "WEAKNESS",
            "learning_weakness", "WEAKNESS",
            "manual_note", "MANUAL_NOTE");

    private final AiUserMemoryMapper userMemoryMapper;
    private final AiMemoryClaimMapper claimMapper;
    private final AiLearningProfileMapper profileMapper;
    private final AiLearningWeaknessMapper weaknessMapper;
    private final AiProfileSignalMapper signalMapper;
    private final ObjectMapper objectMapper;

    public LegacyMemoryMigrationService(AiUserMemoryMapper userMemoryMapper,
                                        AiMemoryClaimMapper claimMapper,
                                        AiLearningProfileMapper profileMapper,
                                        AiLearningWeaknessMapper weaknessMapper,
                                        AiProfileSignalMapper signalMapper,
                                        ObjectMapper objectMapper) {
        this.userMemoryMapper = userMemoryMapper;
        this.claimMapper = claimMapper;
        this.profileMapper = profileMapper;
        this.weaknessMapper = weaknessMapper;
        this.signalMapper = signalMapper;
        this.objectMapper = objectMapper;
    }

    /**
     * Runs the migration. With {@code apply=false} nothing is written and the
     * report's migrated counter means "would migrate". Re-running after an
     * apply must report migrated=0 with alreadyMapped raised accordingly —
     * that is the dual-run comparison check.
     */
    public LegacyMemoryMigrationReport migrate(boolean apply) {
        LegacyMemoryMigrationReport report = new LegacyMemoryMigrationReport(apply);
        long lastId = 0L;
        while (true) {
            List<AiUserMemoryEntity> batch = userMemoryMapper.selectList(new QueryWrapper<AiUserMemoryEntity>()
                    .gt("id", lastId)
                    .orderByAsc("id")
                    .last("LIMIT " + BATCH_SIZE));
            if (batch.isEmpty()) {
                break;
            }
            for (AiUserMemoryEntity memory : batch) {
                lastId = memory.getId();
                report.recordScanned();
                process(memory, apply, report);
            }
            if (batch.size() < BATCH_SIZE) {
                break;
            }
        }
        reconcileProfile(report);
        log.info("legacy memory migration {}", report.summaryLine());
        return report;
    }

    private void process(AiUserMemoryEntity memory, boolean apply, LegacyMemoryMigrationReport report) {
        String memoryType = memory.getMemoryType();
        if (TYPE_CONTENT.equals(memoryType)) {
            report.recordSkippedContent(new LegacyMemoryMigrationReport.Entry(
                    memory.getId(), memory.getUserId(),
                    LegacyMemoryMigrationReport.Action.SKIPPED_CONTENT_TYPE,
                    "generic teaching snippet, not migrated per M-Q4a"));
            return;
        }
        String category = TYPE_TO_CATEGORY.get(memoryType);
        if (category == null) {
            report.recordSkippedUnknownType(new LegacyMemoryMigrationReport.Entry(
                    memory.getId(), memory.getUserId(),
                    LegacyMemoryMigrationReport.Action.SKIPPED_UNKNOWN_TYPE,
                    "no category mapping for memory_type=" + memoryType));
            return;
        }
        List<AiMemoryClaimEntity> existing = claimMapper.selectList(new QueryWrapper<AiMemoryClaimEntity>()
                .eq("legacy_memory_id", memory.getId()));
        if (!existing.isEmpty()) {
            report.recordAlreadyMapped();
            boolean activeLinked = STATUS_SUPERSEDED.equals(memory.getStatus())
                    && existing.stream().anyMatch(claim -> STATUS_ACTIVE.equals(claim.status));
            if (activeLinked) {
                report.recordStatusMismatch(new LegacyMemoryMigrationReport.Entry(
                        memory.getId(), memory.getUserId(),
                        LegacyMemoryMigrationReport.Action.STATUS_MISMATCH,
                        "legacy SUPERSEDED but linked claim(s) still ACTIVE: " + claimIds(existing)));
            }
            return;
        }
        if (!apply) {
            report.recordMigrated(new LegacyMemoryMigrationReport.Entry(
                    memory.getId(), memory.getUserId(),
                    LegacyMemoryMigrationReport.Action.WOULD_MIGRATE,
                    category + " legacy_" + memory.getId()));
            return;
        }
        try {
            AiMemoryClaimEntity claim = buildClaim(memory, category);
            claimMapper.insert(claim);
            report.recordMigrated(new LegacyMemoryMigrationReport.Entry(
                    memory.getId(), memory.getUserId(),
                    LegacyMemoryMigrationReport.Action.MIGRATED,
                    category + " claimId=" + claim.id));
        } catch (DuplicateKeyException ex) {
            report.recordKeyConflict(new LegacyMemoryMigrationReport.Entry(
                    memory.getId(), memory.getUserId(),
                    LegacyMemoryMigrationReport.Action.KEY_CONFLICT,
                    category + " legacy_" + memory.getId() + ": " + ex.getMessage()));
        }
    }

    private AiMemoryClaimEntity buildClaim(AiUserMemoryEntity memory, String category) {
        LocalDateTime now = LocalDateTime.now();
        AiMemoryClaimEntity claim = new AiMemoryClaimEntity();
        claim.userId = memory.getUserId();
        claim.legacyMemoryId = memory.getId();
        claim.scopeType = "GLOBAL";
        claim.scopeId = null;
        claim.category = category;
        claim.memoryKey = "legacy_" + memory.getId();
        claim.valueJson = valueJson(memory.getMemoryType());
        claim.canonicalText = memory.getContent();
        claim.confidence = memory.getConfidence() == null
                ? BigDecimal.valueOf(0.5).setScale(4)
                : memory.getConfidence();
        claim.stabilityScore = claim.confidence;
        claim.supportCount = 1;
        claim.contradictionCount = 0;
        claim.sourceMode = SOURCE_MODE;
        claim.status = memory.getStatus();
        claim.sensitivityLevel = "LOW";
        claim.ambiguityLevel = "LOW";
        claim.firstSeenAt = memory.getCreatedAt() == null ? now : memory.getCreatedAt();
        claim.lastSeenAt = memory.getUpdatedAt() == null ? now : memory.getUpdatedAt();
        claim.lastUsedAt = memory.getLastUsedAt();
        claim.pinned = Boolean.FALSE;
        claim.version = 1;
        claim.createdAt = now;
        claim.updatedAt = now;
        return claim;
    }

    private String valueJson(String memoryType) {
        try {
            return objectMapper.writeValueAsString(Map.of(MIGRATION_FLAG_JSON_FIELD, memoryType));
        } catch (Exception ex) {
            return null;
        }
    }

    private String claimIds(List<AiMemoryClaimEntity> claims) {
        StringBuilder sb = new StringBuilder();
        for (AiMemoryClaimEntity claim : claims) {
            if (sb.length() > 0) {
                sb.append(',');
            }
            sb.append(claim.id);
        }
        return sb.toString();
    }

    /** M-Q7: read-only reconciliation of profile / weakness / signals stock data. */
    private void reconcileProfile(LegacyMemoryMigrationReport report) {
        List<AiLearningProfileEntity> profiles = profileMapper.selectList(new QueryWrapper<AiLearningProfileEntity>()
                .select("state"));
        for (AiLearningProfileEntity profile : profiles) {
            report.recordProfileState(profile.state);
        }
        List<AiLearningWeaknessEntity> weaknesses = weaknessMapper.selectList(new QueryWrapper<AiLearningWeaknessEntity>()
                .select("status"));
        for (AiLearningWeaknessEntity weakness : weaknesses) {
            report.recordWeaknessStatus(weakness.status);
        }
        List<AiProfileSignalEntity> signals = signalMapper.selectList(new QueryWrapper<AiProfileSignalEntity>()
                .select("status"));
        for (AiProfileSignalEntity signal : signals) {
            report.recordSignalStatus(signal.getStatus());
        }
    }
}
