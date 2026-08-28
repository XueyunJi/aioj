package com.aioj.next.ai.agent.memory;

import com.aioj.next.ai.domain.AiCompletion;
import com.aioj.next.ai.domain.memory.AiMemoryCandidateService;
import com.aioj.next.ai.domain.memory.AiMemoryMergeService;
import com.aioj.next.ai.domain.memory.MemoryQualityGate;
import com.aioj.next.ai.persistence.entity.AiMemoryCandidateEntity;
import com.aioj.next.ai.persistence.mapper.AiMemoryCandidateMapper;
import com.aioj.next.contract.ai.AiMemoryCandidateActionRequest;
import com.baomidou.mybatisplus.core.conditions.query.QueryWrapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Set;

/**
 * Agent Core V3 P2-2: ingests curator-proposed memory candidates into the legacy
 * quality-gate pipeline (MemoryQualityGate + AiMemoryCandidateService, called only,
 * never modified). Identity/permission-flavored proposals are pre-rejected BEFORE the
 * gate (write isolation) and land as REJECTED rows for audit; they never reach the
 * normal gate path. Dedupe/idempotency is owned by recordExtraction's existing
 * (user + source message + category + key + text + evidence type) lookup when a real
 * source message exists; when sourceMessageId is null (e.g. tool proposals keyed by a
 * String business turnId) a live-candidate dedupe against ai_memory_candidates runs
 * here before recordExtraction (fail-open on lookup errors), for BOTH ingest modes.
 *
 * <p>P2-4 adds {@link IngestMode#TOOL_PROPOSAL}: model-initiated proposals from the
 * {@code memory.propose_candidate} tool are MORE injection-exposed than curator
 * side-channel proposals, so a gate-ACTIVE verdict is downgraded to CANDIDATE before
 * persistence (flagged {@link #FLAG_DOWNGRADED_TOOL_PROPOSAL} for audit) and
 * {@code accept} is never called. Identity/permission pre-rejection and REJECTED /
 * NEEDS_CONFIRMATION recording behave exactly as in {@link IngestMode#CURATOR_AUTO}.
 *
 * <p>P2-7 adds the user-distrust guard (frozen decision Q5/Q6): when
 * {@link MemoryDistrustPolicy} marks a proposal's key as distrusted (user previously
 * rejected/disabled the same key), a gate-ACTIVE verdict is downgraded to CANDIDATE
 * (flagged {@link #FLAG_DISTRUSTED_KEY_NO_AUTO_ACTIVATION}) so automatic flows never
 * re-activate it. Applies to BOTH ingest modes; NEEDS_CONFIRMATION / CANDIDATE /
 * REJECTED verdicts are left untouched.
 */
@Service
public class MemoryCandidateIngestionService {

    public static final String REASON_AUTO_EXTRACTION = "auto_memory_extraction";
    public static final String REJECTED_REASON_IDENTITY_PERMISSION = "identity_permission_isolated";
    public static final String SIGNAL_REASON = "curator_digest";
    public static final String FLAG_DOWNGRADED_TOOL_PROPOSAL = "downgraded_from_active_tool_proposal";
    public static final String FLAG_DISTRUSTED_KEY_NO_AUTO_ACTIVATION = "distrusted_key_no_auto_activation";

    public enum IngestMode {
        /** Curator side channel: gate-ACTIVE candidates are auto-accepted. */
        CURATOR_AUTO,
        /** Model-initiated tool proposal: gate-ACTIVE is downgraded to CANDIDATE, never auto-accepted. */
        TOOL_PROPOSAL
    }

    private static final Logger log = LoggerFactory.getLogger(MemoryCandidateIngestionService.class);

    private static final int MAX_EVIDENCE_CHARS = 500;
    private static final String STATUS_ACTIVE = "ACTIVE";
    private static final String STATUS_CANDIDATE = "CANDIDATE";
    private static final String STATUS_NEEDS_CONFIRMATION = "NEEDS_CONFIRMATION";
    private static final String STATUS_REJECTED = "REJECTED";
    private static final String STATUS_MERGE_QUEUED = "MERGE_QUEUED";
    /** Live statuses consulted by the active-candidate dedupe (sourceMessageId == null path only). */
    private static final List<String> LIVE_DEDUPE_STATUSES =
            List.of(STATUS_CANDIDATE, STATUS_NEEDS_CONFIRMATION, STATUS_ACTIVE, STATUS_MERGE_QUEUED);
    private static final Set<String> IDENTITY_PERMISSION_ZH = Set.of("管理员", "权限", "身份", "角色");
    private static final Set<String> IDENTITY_PERMISSION_EN = Set.of("admin", "role", "permission");

    private final MemoryQualityGate qualityGate;
    private final AiMemoryCandidateService candidateService;
    private final MemoryDistrustPolicy distrustPolicy;
    private final AiMemoryCandidateMapper candidateMapper;
    private final AiMemoryMergeService mergeService;

    public MemoryCandidateIngestionService(MemoryQualityGate qualityGate, AiMemoryCandidateService candidateService,
                                           MemoryDistrustPolicy distrustPolicy, AiMemoryCandidateMapper candidateMapper,
                                           AiMemoryMergeService mergeService) {
        this.qualityGate = qualityGate;
        this.candidateService = candidateService;
        this.distrustPolicy = distrustPolicy;
        this.candidateMapper = candidateMapper;
        this.mergeService = mergeService;
    }

    public record CandidateProposal(
            String text,
            String category,
            String memoryKey,
            double confidence,
            Boolean longTerm,
            String evidenceType
    ) {
    }

    public record ItemResult(
            String text,
            Long candidateId,
            String finalStatus,
            String rejectedReason,
            boolean preRejected,
            boolean deduplicated
    ) {
        /** Backward-compatible constructor: non-deduped results keep the 5-arg shape. */
        public ItemResult(String text, Long candidateId, String finalStatus, String rejectedReason, boolean preRejected) {
            this(text, candidateId, finalStatus, rejectedReason, preRejected, false);
        }
    }

    public record IngestResult(int active, int needsConfirmation, int rejected, int preRejected,
                               List<ItemResult> items) {
        public IngestResult {
            items = items == null ? List.of() : List.copyOf(items);
        }

        public IngestResult(int active, int needsConfirmation, int rejected, int preRejected) {
            this(active, needsConfirmation, rejected, preRejected, List.of());
        }
    }

    public IngestResult ingest(Long userId, String conversationId, Long sourceMessageId,
                               List<CandidateProposal> proposals, String userMessage, String assistantMessage) {
        return ingest(userId, conversationId, sourceMessageId, proposals, userMessage, assistantMessage,
                IngestMode.CURATOR_AUTO);
    }

    public IngestResult ingest(Long userId, String conversationId, Long sourceMessageId,
                               List<CandidateProposal> proposals, String userMessage, String assistantMessage,
                               IngestMode mode) {
        IngestMode effectiveMode = mode == null ? IngestMode.CURATOR_AUTO : mode;
        int active = 0;
        int needsConfirmation = 0;
        int rejected = 0;
        int preRejected = 0;
        List<ItemResult> items = new ArrayList<>();
        if (proposals == null) {
            return new IngestResult(0, 0, 0, 0);
        }
        for (CandidateProposal proposal : proposals) {
            if (proposal == null || proposal.text() == null || proposal.text().trim().isEmpty()) {
                continue;
            }
            String text = proposal.text().trim();
            String category = normalizeCategory(proposal.category());
            String memoryKey = normalizeKey(proposal.memoryKey());
            String evidenceType = normalizeEvidenceType(proposal.evidenceType());
            boolean longTerm = proposal.longTerm() == null || proposal.longTerm();
            if (sourceMessageId == null) {
                // Legacy recordExtraction dedupe needs a non-null source message; without one
                // (e.g. tool proposals keyed by a String business turnId) repeated proposals of
                // the same turn/conversation would insert duplicate candidate rows. Guard here
                // instead. Applies to BOTH ingest modes.
                AiMemoryCandidateEntity duplicate = findActiveDuplicate(userId, conversationId, category,
                        proposal.memoryKey(), memoryKey, text);
                if (duplicate != null) {
                    log.info("memory candidate deduplicated against live candidate userId={} key={} candidateId={}",
                            userId, memoryKey, duplicate.id);
                    items.add(new ItemResult(text, duplicate.id, duplicate.status, null, false, true));
                    continue;
                }
            }
            MemoryQualityGate.MemoryCandidate candidate = new MemoryQualityGate.MemoryCandidate(
                    category, memoryKey, text, "{}", "GLOBAL", null, evidenceType,
                    proposal.confidence(), longTerm, false, false, false, false);
            AiCompletion.MemorySignal signal = new AiCompletion.MemorySignal(
                    category, text, proposal.confidence(), SIGNAL_REASON, evidenceType);
            String evidenceText = truncate(userMessage, MAX_EVIDENCE_CHARS);

            MemoryQualityGate.GateResult gate;
            boolean isolated = isIdentityOrPermission(text);
            if (isolated) {
                preRejected++;
                log.info("memory candidate pre-rejected (identity/permission isolation) userId={} key={}", userId, memoryKey);
                gate = new MemoryQualityGate.GateResult(false, false, category, memoryKey, "GLOBAL", null,
                        0, List.of(REJECTED_REASON_IDENTITY_PERMISSION), List.of(),
                        REJECTED_REASON_IDENTITY_PERMISSION, STATUS_REJECTED);
            } else {
                gate = qualityGate.evaluate(candidate, new MemoryQualityGate.MessageContext(userMessage, assistantMessage));
                if (STATUS_ACTIVE.equals(gate.status())
                        && distrustPolicy.isDistrusted(userId, gate.scopeType(), gate.scopeId(),
                                gate.normalizedCategory(), gate.normalizedKey(), candidate.canonicalText())) {
                    // V3 P2-7 frozen decision: a user-distrusted key is never auto-activated,
                    // in EITHER ingest mode. recordExtraction persists gate.status() verbatim,
                    // so downgrade BEFORE the write (same construction as the P2-4 downgrade).
                    List<String> flags = new ArrayList<>(gate.qualityFlags());
                    flags.add(FLAG_DISTRUSTED_KEY_NO_AUTO_ACTIVATION);
                    gate = new MemoryQualityGate.GateResult(gate.accepted(), gate.needsConfirmation(),
                            gate.normalizedCategory(), gate.normalizedKey(), gate.scopeType(), gate.scopeId(),
                            gate.writeScore(), List.copyOf(flags), gate.ambiguityFlags(),
                            gate.rejectedReason(), STATUS_CANDIDATE);
                    log.info("memory candidate downgraded ACTIVE->CANDIDATE (distrusted key) userId={} key={}",
                            userId, gate.normalizedKey());
                }
                if (effectiveMode == IngestMode.TOOL_PROPOSAL && STATUS_ACTIVE.equals(gate.status())) {
                    // Frozen P2-4 decision: tool proposals never auto-activate. recordExtraction
                    // persists gate.status() verbatim, so downgrade BEFORE the write.
                    List<String> flags = new ArrayList<>(gate.qualityFlags());
                    flags.add(FLAG_DOWNGRADED_TOOL_PROPOSAL);
                    gate = new MemoryQualityGate.GateResult(gate.accepted(), gate.needsConfirmation(),
                            gate.normalizedCategory(), gate.normalizedKey(), gate.scopeType(), gate.scopeId(),
                            gate.writeScore(), List.copyOf(flags), gate.ambiguityFlags(),
                            gate.rejectedReason(), STATUS_CANDIDATE);
                    log.info("memory candidate downgraded ACTIVE->CANDIDATE (tool proposal) userId={} key={}",
                            userId, gate.normalizedKey());
                }
            }

            AiMemoryCandidateEntity entity = candidateService.recordExtraction(
                    userId, conversationId, sourceMessageId, signal, candidate, gate, evidenceText);
            // The persisted row (or its dedupe-hit twin) is the truth the caller must report.
            Long candidateId = entity == null ? null : entity.id;
            String finalStatus = entity != null && entity.status != null ? entity.status : gate.status();
            String rejectedReason = gate.rejectedReason() == null || gate.rejectedReason().isBlank()
                    ? null : gate.rejectedReason();
            items.add(new ItemResult(text, candidateId, finalStatus, rejectedReason, isolated));
            switch (gate.status()) {
                case STATUS_ACTIVE -> {
                    active++;
                    // Auto-activation goes straight to the merge queue:
                    // candidateService.accept() rejects non-reviewable rows (including
                    // freshly gate-ACTIVE ones) by design. The merge service admits a
                    // fresh ACTIVE row only for AUTO_MEMORY_EXTRACTION; a dedupe twin
                    // already carrying the memoryMerge marker (post-merge ACTIVE or
                    // MERGE_QUEUED from an earlier attempt) is skipped here.
                    if (entity != null && entity.id != null && STATUS_ACTIVE.equals(entity.status)
                            && !alreadyMergeEnqueued(entity)) {
                        mergeService.enqueueCandidateMerge(userId, entity.id,
                                new AiMemoryCandidateActionRequest(entity.category, null, entity.memoryKey,
                                        entity.canonicalText, REASON_AUTO_EXTRACTION),
                                null, null, "AUTO_MEMORY_EXTRACTION", REASON_AUTO_EXTRACTION);
                    }
                }
                case STATUS_NEEDS_CONFIRMATION -> needsConfirmation++;
                case STATUS_REJECTED -> {
                    if (!isolated) {
                        rejected++;
                    }
                }
                default -> {
                    // CANDIDATE and anything else: recorded only, no automatic action.
                }
            }
        }
        return new IngestResult(active, needsConfirmation, rejected, preRejected, items);
    }

    /**
     * Active-candidate dedupe, consulted only when sourceMessageId == null (with a real
     * source message the legacy recordExtraction lookup already dedupes). Scans the 50
     * most recent live candidates of the same user + conversation + category and compares
     * in memory: a non-blank proposal key matches on case-insensitive memoryKey equality;
     * keyless proposals fall back to normalized canonicalText equality. Fail-open: any
     * lookup error is logged and the normal flow proceeds.
     */
    private AiMemoryCandidateEntity findActiveDuplicate(Long userId, String conversationId, String category,
                                                        String rawMemoryKey, String memoryKey, String canonicalText) {
        try {
            List<AiMemoryCandidateEntity> recent = candidateMapper.selectList(new QueryWrapper<AiMemoryCandidateEntity>()
                    .eq("user_id", userId)
                    .eq("source_conversation_id", conversationId)
                    .eq("category", category)
                    .in("status", LIVE_DEDUPE_STATUSES)
                    .orderByDesc("created_at")
                    .last("LIMIT 50"));
            if (recent == null) {
                return null;
            }
            boolean hasKey = rawMemoryKey != null && !rawMemoryKey.isBlank();
            String targetText = normalizeCanonicalText(canonicalText);
            for (AiMemoryCandidateEntity row : recent) {
                if (row == null) {
                    continue;
                }
                if (hasKey) {
                    if (row.memoryKey != null && row.memoryKey.equalsIgnoreCase(memoryKey)) {
                        return row;
                    }
                } else if (!targetText.isEmpty() && normalizeCanonicalText(row.canonicalText).equals(targetText)) {
                    return row;
                }
            }
            return null;
        } catch (Exception ex) {
            log.warn("active-candidate dedupe lookup failed, proceeding without it userId={} error={}",
                    userId, ex.toString());
            return null;
        }
    }

    /**
     * A candidate row that already went through merge enqueue carries a "memoryMerge"
     * marker in valueJson (written by AiMemoryMergeService). Such rows must not be
     * re-enqueued on digest-job retries.
     */
    private boolean alreadyMergeEnqueued(AiMemoryCandidateEntity entity) {
        return entity.valueJson != null && entity.valueJson.contains("\"memoryMerge\"");
    }

    private String normalizeCanonicalText(String value) {
        return value == null ? "" : value.trim().toLowerCase(Locale.ROOT).replaceAll("\\s+", " ");
    }

    private boolean isIdentityOrPermission(String text) {
        for (String keyword : IDENTITY_PERMISSION_ZH) {
            if (text.contains(keyword)) {
                return true;
            }
        }
        String lower = text.toLowerCase(Locale.ROOT);
        for (String keyword : IDENTITY_PERMISSION_EN) {
            if (lower.contains(keyword)) {
                return true;
            }
        }
        return false;
    }

    private String normalizeCategory(String category) {
        String normalized = category == null ? "" : category.trim().toUpperCase(Locale.ROOT);
        return normalized.isEmpty() ? "MANUAL_NOTE" : normalized;
    }

    private String normalizeKey(String memoryKey) {
        String normalized = memoryKey == null ? "" : memoryKey.trim().toLowerCase(Locale.ROOT);
        return normalized.isEmpty() ? "manual_note" : normalized;
    }

    private String normalizeEvidenceType(String evidenceType) {
        String normalized = evidenceType == null ? "" : evidenceType.trim().toUpperCase(Locale.ROOT);
        return normalized.isEmpty() ? "INFERRED" : normalized;
    }

    private String truncate(String value, int maxChars) {
        if (value == null) {
            return "";
        }
        return value.length() > maxChars ? value.substring(0, maxChars) : value;
    }
}
