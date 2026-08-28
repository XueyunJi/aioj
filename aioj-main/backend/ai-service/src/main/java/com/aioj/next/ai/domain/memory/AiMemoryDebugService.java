package com.aioj.next.ai.domain.memory;

import com.aioj.next.ai.persistence.entity.AiMemoryClaimEntity;
import com.aioj.next.ai.persistence.entity.AiMemoryRecallLogEntity;
import com.aioj.next.ai.persistence.entity.AiUserMemoryEntity;
import com.aioj.next.ai.persistence.mapper.AiMemoryClaimMapper;
import com.aioj.next.ai.persistence.mapper.AiMemoryRecallLogMapper;
import com.aioj.next.ai.persistence.mapper.AiUserMemoryMapper;
import com.aioj.next.contract.ai.AiMemoryDebugResponse;
import com.baomidou.mybatisplus.core.conditions.query.QueryWrapper;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

@Service
public class AiMemoryDebugService {
    private static final String STATUS_ACTIVE = "ACTIVE";

    private final AiUserMemoryMapper memoryMapper;
    private final AiMemoryClaimMapper claimMapper;
    private final AiMemoryRecallLogMapper recallLogMapper;
    private final ObjectMapper objectMapper;

    public AiMemoryDebugService(
            AiUserMemoryMapper memoryMapper,
            AiMemoryClaimMapper claimMapper,
            AiMemoryRecallLogMapper recallLogMapper,
            ObjectMapper objectMapper
    ) {
        this.memoryMapper = memoryMapper;
        this.claimMapper = claimMapper;
        this.recallLogMapper = recallLogMapper;
        this.objectMapper = objectMapper;
    }

    public AiMemoryDebugResponse debug(Long userId, String query, Long problemId, List<String> problemTags, String mode) {
        String normalizedQuery = normalize(query);
        List<String> tags = problemTags == null ? List.of() : problemTags.stream().map(this::normalize).filter(item -> !item.isBlank()).toList();
        String intent = inferIntent(normalizedQuery, mode);
        List<AiMemoryClaimEntity> claims = claimMapper.selectList(new QueryWrapper<AiMemoryClaimEntity>()
                .eq("user_id", userId)
                .eq("status", STATUS_ACTIVE)
                .orderByDesc("updated_at")
                .last("LIMIT 160"));
        Map<Long, AiMemoryClaimEntity> claimsByLegacy = new LinkedHashMap<>();
        for (AiMemoryClaimEntity claim : claims) {
            if (claim.legacyMemoryId != null) {
                claimsByLegacy.putIfAbsent(claim.legacyMemoryId, claim);
            }
        }
        List<Scored> scored = memoryMapper.selectList(new QueryWrapper<AiUserMemoryEntity>()
                        .eq("user_id", userId)
                        .eq("status", STATUS_ACTIVE)
                        .orderByDesc("updated_at")
                        .last("LIMIT 160"))
                .stream()
                .map(memory -> score(memory, claimsByLegacy.get(memory.getId()), normalizedQuery, tags, intent, mode))
                .sorted(Comparator.comparingDouble(Scored::score).reversed())
                .toList();
        List<AiMemoryDebugResponse.RecallItem> selected = scored.stream()
                .filter(item -> item.selected)
                .limit(12)
                .map(Scored::toResponse)
                .toList();
        List<AiMemoryDebugResponse.RecallItem> rejected = scored.stream()
                .filter(item -> !item.selected)
                .limit(20)
                .map(Scored::toResponse)
                .toList();
        logRecall(userId, selected, true);
        logRecall(userId, rejected, false);
        return new AiMemoryDebugResponse(
                new AiMemoryDebugResponse.QueryContext(normalizedQuery, intent, normalize(mode), problemId, tags),
                selected,
                rejected
        );
    }

    Scored score(AiUserMemoryEntity memory, AiMemoryClaimEntity claim, String query, List<String> tags, String intent, String mode) {
        String category = claim == null ? v2Category(memory.getCategory(), memory.getMemoryType()) : claim.category;
        String text = normalize(memory.getTitle() + "\n" + memory.getMemoryType() + "\n" + memory.getContent()).toLowerCase(Locale.ROOT);
        List<String> reasons = new ArrayList<>();
        double score = 0;
        double lexical = lexicalScore(query, text);
        if (lexical > 0) {
            score += lexical;
            reasons.add("lexical_match=" + round(lexical));
        }
        if ("RULE".equals(category)) {
            score += 0.85;
            reasons.add("rule_bucket");
        }
        if ("PREFERENCE".equals(category) && intentMatchesPreference(intent, mode, text)) {
            score += 0.45;
            reasons.add("preference_intent_match");
        }
        if ("WEAKNESS".equals(category)) {
            double tagMatch = tagOverlap(tags, text);
            if (tagMatch > 0) {
                score += 0.75 + tagMatch;
                reasons.add("weakness_tag_match=" + round(tagMatch));
            } else if ("solve_problem".equals(intent) || "debug_code".equals(intent)) {
                score += 0.18;
                reasons.add("weakness_learning_context");
            }
        }
        if ("PROFILE".equals(category)) {
            if (profileUseful(query, intent)) {
                score += 0.22;
                reasons.add("profile_useful");
            } else {
                score -= 0.35;
                reasons.add("profile_not_needed_for_task");
            }
        }
        if (claim != null) {
            double reliability = Math.min(0.5, safeInt(claim.supportCount) * 0.08 + safeDecimal(claim.confidence) * 0.24);
            score += reliability;
            reasons.add("reliability=" + round(reliability));
            if (Boolean.TRUE.equals(claim.pinned)) {
                score += 0.3;
                reasons.add("pinned");
            }
            if (safeInt(claim.contradictionCount) > 0) {
                double penalty = Math.min(0.4, safeInt(claim.contradictionCount) * 0.12);
                score -= penalty;
                reasons.add("contradiction_penalty=" + round(penalty));
            }
        } else if (memory.getConfidence() != null) {
            double confidence = memory.getConfidence().doubleValue() * 0.18;
            score += confidence;
            reasons.add("legacy_confidence=" + round(confidence));
        }
        if (memory.getLastUsedAt() != null) {
            score += 0.04;
            reasons.add("recently_used");
        }
        boolean selected = score >= threshold(category);
        if (!selected && reasons.isEmpty()) {
            reasons.add("below_threshold");
        }
        return new Scored(memory, claim, category, Math.max(0, round(score)), selected, reasons);
    }

    private void logRecall(Long userId, List<AiMemoryDebugResponse.RecallItem> items, boolean selected) {
        LocalDateTime now = LocalDateTime.now();
        for (AiMemoryDebugResponse.RecallItem item : items) {
            AiMemoryRecallLogEntity log = new AiMemoryRecallLogEntity();
            log.userId = userId;
            log.claimId = item.claimId();
            log.legacyMemoryId = item.id();
            log.recallScore = BigDecimal.valueOf(item.score()).setScale(4, RoundingMode.HALF_UP);
            log.selected = selected;
            log.usedInPrompt = selected;
            log.reasonJson = toJson(item.reasons());
            log.createdAt = now;
            recallLogMapper.insert(log);
        }
    }

    private String inferIntent(String query, String mode) {
        String lower = query.toLowerCase(Locale.ROOT) + " " + normalize(mode).toLowerCase(Locale.ROOT);
        if (containsAny(lower, "debug", "wa", "re", "tle", "报错", "调试", "哪里错")) return "debug_code";
        if (containsAny(lower, "代码", "实现", "cpp", "java", "python")) return "write_code";
        if (containsAny(lower, "怎么做", "思路", "提示", "题解", "算法")) return "solve_problem";
        if (containsAny(lower, "计划", "目标", "学习", "复习")) return "learning_plan";
        return "general_chat";
    }

    private boolean intentMatchesPreference(String intent, String mode, String text) {
        if ("debug_code".equals(intent) && containsAny(text, "debug", "调试", "反例", "逐行")) return true;
        if ("solve_problem".equals(intent) && containsAny(text, "提示", "思路", "完整答案", "引导")) return true;
        if ("write_code".equals(intent) && containsAny(text, "代码", "语言", "c++", "cpp", "java", "python")) return true;
        return containsAny(text, normalize(mode).toLowerCase(Locale.ROOT));
    }

    private boolean profileUseful(String query, String intent) {
        return "learning_plan".equals(intent) || containsAny(query, "叫我", "称呼", "我的目标", "适合我", "按我的水平");
    }

    private double lexicalScore(String query, String text) {
        double score = 0;
        for (String token : query.toLowerCase(Locale.ROOT).split("\\s+|[，。！？、；：,.!?;:]")) {
            if (token.length() >= 2 && text.contains(token)) {
                score += 0.08;
            }
        }
        return Math.min(0.48, score);
    }

    private double tagOverlap(List<String> tags, String text) {
        if (tags.isEmpty()) {
            return 0;
        }
        double score = 0;
        for (String tag : tags) {
            if (!tag.isBlank() && text.contains(tag.toLowerCase(Locale.ROOT))) {
                score += 0.16;
            }
        }
        return Math.min(0.48, score);
    }

    private double threshold(String category) {
        return switch (category) {
            case "RULE" -> 0.55;
            case "WEAKNESS" -> 0.62;
            case "PROFILE" -> 0.45;
            default -> 0.38;
        };
    }

    private String v2Category(String legacyCategory, String memoryType) {
        return switch (normalize(memoryType).toLowerCase(Locale.ROOT)) {
            case "rule" -> "RULE";
            case "preferred_language", "guidance_preference", "pace_preference", "teaching_style" -> "PREFERENCE";
            case "weakness" -> "WEAKNESS";
            case "code_style", "debugging_preference", "habit" -> "HABIT";
            case "name_preference", "skill_level" -> "PROFILE";
            case "learning_direction" -> "GOAL";
            default -> switch (normalize(legacyCategory).toLowerCase(Locale.ROOT)) {
                case "rule" -> "RULE";
                case "preference", "teaching_style" -> "PREFERENCE";
                case "habit" -> "HABIT";
                case "weakness" -> "WEAKNESS";
                default -> "MANUAL_NOTE";
            };
        };
    }

    private int safeInt(Integer value) {
        return value == null ? 0 : value;
    }

    private double safeDecimal(BigDecimal value) {
        return value == null ? 0 : value.doubleValue();
    }

    private boolean containsAny(String value, String... needles) {
        for (String needle : needles) {
            if (value.contains(needle)) return true;
        }
        return false;
    }

    private String normalize(String value) {
        return value == null ? "" : value.replace("\r\n", "\n").replace('\r', '\n').trim();
    }

    private double round(double value) {
        return Math.round(value * 10_000.0) / 10_000.0;
    }

    private String toJson(Object value) {
        try {
            return objectMapper.writeValueAsString(value);
        } catch (Exception ignored) {
            return "[]";
        }
    }

    record Scored(
            AiUserMemoryEntity memory,
            AiMemoryClaimEntity claim,
            String category,
            double score,
            boolean selected,
            List<String> reasons
    ) {
        AiMemoryDebugResponse.RecallItem toResponse() {
            return new AiMemoryDebugResponse.RecallItem(
                    memory.getId(),
                    claim == null ? null : claim.id,
                    category,
                    memory.getMemoryType(),
                    memory.getTitle(),
                    memory.getContent(),
                    score,
                    selected,
                    reasons
            );
        }
    }
}
