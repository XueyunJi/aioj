package com.aioj.next.ai.agent.profile;

import java.util.Locale;

/**
 * Single source of truth for knowledge-node key normalization, shared by signal
 * ingestion (ai_profile_signals.knowledge_node) and profile aggregation
 * (ai_learning_profile.profile_key) so profile.search can join signals to
 * profiles by exact key equality. snake_case: lowercase, runs of characters
 * that are neither alphanumeric nor CJK collapse to `_`, CJK (\u4e00-\u9fa5)
 * is preserved, capped at 100 chars.
 */
public final class KnowledgeNodeNormalizer {

    static final int MAX_KEY_LENGTH = 100;

    private KnowledgeNodeNormalizer() {
    }

    /** Normalizes a raw knowledge node; null/blank input returns "". */
    public static String normalize(String raw) {
        if (raw == null || raw.isBlank()) {
            return "";
        }
        String normalized = raw.trim().toLowerCase(Locale.ROOT)
                .replaceAll("[^a-z0-9_\\u4e00-\\u9fa5]+", "_")
                .replaceAll("_+", "_")
                .replaceAll("^_|_$", "");
        return normalized.length() <= MAX_KEY_LENGTH ? normalized : normalized.substring(0, MAX_KEY_LENGTH);
    }
}
