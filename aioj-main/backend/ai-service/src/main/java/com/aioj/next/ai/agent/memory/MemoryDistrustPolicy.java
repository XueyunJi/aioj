package com.aioj.next.ai.agent.memory;

import com.aioj.next.ai.persistence.entity.AiMemoryCandidateEntity;
import com.aioj.next.ai.persistence.entity.AiMemoryClaimEntity;
import com.aioj.next.ai.persistence.mapper.AiMemoryCandidateMapper;
import com.aioj.next.ai.persistence.mapper.AiMemoryClaimMapper;
import com.baomidou.mybatisplus.core.conditions.query.QueryWrapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.Locale;
import java.util.Set;

/**
 * Agent Core V3 P2-7: user-distrust policy for automatic memory activation
 * (frozen decision Q5/Q6). A key becomes distrusted when the user rejected it —
 * candidate reject ({@code user_rejected} / {@code memory_clarification_rejected}),
 * memory disable, or profile disable. While distrusted, automatic flows (curator
 * quality-gate直通 AUTO_MEMORY_EXTRACTION, merge auto-revival) must NOT activate the
 * same key; an explicit user re-acceptance clears the distrust (handled in the legacy
 * merge/candidate services). Quality-gate hard rejects, identity/permission isolation
 * and admin rejects are NOT user distrust and never feed this policy.
 *
 * <p>"Same key" means the claims unique tuple
 * {@code (user_id, scope_type, scope_id, category, memory_key)} on the claim side, and
 * {@code (category, memory_key)} on the candidate side (falling back to exact
 * normalized canonicalText when memoryKey is blank).
 *
 * <p>Both lookups are fail-safe: a database error is treated as "distrusted" so an
 * automatic flow errs on the side of NOT activating.
 */
@Component
public class MemoryDistrustPolicy {

    private static final Logger log = LoggerFactory.getLogger(MemoryDistrustPolicy.class);

    /** Reject reasons that count as user distrust. Hard gate rejects / identity isolation / admin rejects are excluded. */
    private static final Set<String> USER_DISTRUST_REJECT_REASONS = Set.of(
            "user_rejected",
            "memory_clarification_rejected"
    );
    private static final String STATUS_DISABLED = "DISABLED";
    private static final String STATUS_REJECTED = "REJECTED";
    private static final String DEFAULT_SCOPE_TYPE = "GLOBAL";
    private static final int CANDIDATE_SCAN_LIMIT = 100;

    private final AiMemoryClaimMapper claimMapper;
    private final AiMemoryCandidateMapper candidateMapper;

    public MemoryDistrustPolicy(AiMemoryClaimMapper claimMapper, AiMemoryCandidateMapper candidateMapper) {
        this.claimMapper = claimMapper;
        this.candidateMapper = candidateMapper;
    }

    public boolean isDistrusted(
            Long userId,
            String scopeType,
            String scopeId,
            String category,
            String memoryKey,
            String canonicalText
    ) {
        if (userId == null) {
            return false;
        }
        return hasDisabledClaim(userId, scopeType, scopeId, category, memoryKey)
                || hasUserRejectedCandidate(userId, category, memoryKey, canonicalText);
    }

    /** Claim side: an existing DISABLED claim on the exact five-tuple marks the key distrusted. */
    private boolean hasDisabledClaim(Long userId, String scopeType, String scopeId, String category, String memoryKey) {
        String key = normalize(memoryKey);
        if (key.isBlank()) {
            return false;
        }
        try {
            QueryWrapper<AiMemoryClaimEntity> query = new QueryWrapper<AiMemoryClaimEntity>()
                    .eq("user_id", userId)
                    .eq("scope_type", normalize(scopeType).isBlank() ? DEFAULT_SCOPE_TYPE : normalize(scopeType))
                    .eq("category", category)
                    .eq("memory_key", memoryKey)
                    .eq("status", STATUS_DISABLED);
            if (normalize(scopeId).isBlank()) {
                query.isNull("scope_id");
            } else {
                query.eq("scope_id", normalize(scopeId));
            }
            Long count = claimMapper.selectCount(query);
            return count != null && count > 0;
        } catch (Exception ex) {
            // Fail-safe: on lookup failure treat the key as distrusted (never auto-activate).
            log.warn("memory distrust claim lookup failed userId={} key={} error={}", userId, key, ex.toString());
            return true;
        }
    }

    /**
     * Candidate side: user-rejected candidates on the same (category, memoryKey) — or same
     * normalized canonicalText when the key is blank — mark the key distrusted. The reason
     * whitelist is applied in SQL; key/text matching happens in memory over the newest rows.
     */
    private boolean hasUserRejectedCandidate(Long userId, String category, String memoryKey, String canonicalText) {
        try {
            List<AiMemoryCandidateEntity> rejected = candidateMapper.selectList(new QueryWrapper<AiMemoryCandidateEntity>()
                    .eq("user_id", userId)
                    .eq("category", category)
                    .eq("status", STATUS_REJECTED)
                    .in("rejected_reason", USER_DISTRUST_REJECT_REASONS)
                    .orderByDesc("updated_at")
                    .last("LIMIT " + CANDIDATE_SCAN_LIMIT));
            if (rejected == null || rejected.isEmpty()) {
                return false;
            }
            String key = normalize(memoryKey);
            if (!key.isBlank()) {
                for (AiMemoryCandidateEntity candidate : rejected) {
                    if (candidate != null && key.equalsIgnoreCase(normalize(candidate.memoryKey))) {
                        return true;
                    }
                }
                return false;
            }
            String text = normalizeText(canonicalText);
            if (text.isBlank()) {
                return false;
            }
            for (AiMemoryCandidateEntity candidate : rejected) {
                if (candidate != null && text.equals(normalizeText(candidate.canonicalText))) {
                    return true;
                }
            }
            return false;
        } catch (Exception ex) {
            // Fail-safe: on lookup failure treat the key as distrusted (never auto-activate).
            log.warn("memory distrust candidate lookup failed userId={} category={} error={}",
                    userId, category, ex.toString());
            return true;
        }
    }

    private String normalizeText(String value) {
        return normalize(value).toLowerCase(Locale.ROOT).replaceAll("\\s+", " ").trim();
    }

    private String normalize(String value) {
        return value == null ? "" : value.trim();
    }
}
