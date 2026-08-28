package com.aioj.next.ai.domain.memory;

import com.aioj.next.ai.persistence.entity.AiMemoryClaimEntity;
import com.aioj.next.ai.persistence.entity.AiUserMemoryEntity;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class AiMemoryDebugServiceScorerTest {
    private final AiMemoryDebugService scorer = new AiMemoryDebugService(null, null, null, new ObjectMapper());

    @Test
    void selectsRuleBucketEvenWithoutLexicalMatch() {
        AiMemoryDebugService.Scored scored = scorer.score(
                memory(1L, "rule", "rule", "不要直接给完整答案，先给提示。"),
                claim(101L, "RULE", 2, 0),
                "这题怎么做",
                List.of("dp"),
                "solve_problem",
                "hint"
        );

        assertThat(scored.selected()).isTrue();
        assertThat(scored.score()).isGreaterThanOrEqualTo(0.55);
        assertThat(scored.reasons()).contains("rule_bucket");
    }

    @Test
    void selectsWeaknessWhenProblemTagsOverlap() {
        AiMemoryDebugService.Scored scored = scorer.score(
                memory(2L, "weakness", "weakness", "用户在二分和边界条件上经常混淆 l、r、mid。"),
                claim(102L, "WEAKNESS", 3, 0),
                "这道题怎么判断边界",
                List.of("二分", "binary_search"),
                "solve_problem",
                "hint"
        );

        assertThat(scored.selected()).isTrue();
        assertThat(scored.reasons()).anyMatch(reason -> reason.startsWith("weakness_tag_match="));
    }

    @Test
    void rejectsProfileWhenTaskDoesNotNeedProfile() {
        AiMemoryDebugService.Scored scored = scorer.score(
                memory(3L, "memory", "name_preference", "你可以叫我 Elvis。"),
                claim(103L, "PROFILE", 1, 0),
                "这道题怎么做",
                List.of("array"),
                "solve_problem",
                "hint"
        );

        assertThat(scored.selected()).isFalse();
        assertThat(scored.reasons()).contains("profile_not_needed_for_task");
    }

    @Test
    void appliesContradictionPenalty() {
        AiMemoryDebugService.Scored clean = scorer.score(
                memory(4L, "preference", "guidance_preference", "用户喜欢先看提示再看代码。"),
                claim(104L, "PREFERENCE", 4, 0),
                "给我一点思路提示",
                List.of(),
                "solve_problem",
                "hint"
        );
        AiMemoryDebugService.Scored contradicted = scorer.score(
                memory(5L, "preference", "guidance_preference", "用户喜欢先看提示再看代码。"),
                claim(105L, "PREFERENCE", 4, 3),
                "给我一点思路提示",
                List.of(),
                "solve_problem",
                "hint"
        );

        assertThat(clean.score()).isGreaterThan(contradicted.score());
        assertThat(contradicted.reasons()).anyMatch(reason -> reason.startsWith("contradiction_penalty="));
    }

    private static AiUserMemoryEntity memory(Long id, String category, String type, String content) {
        AiUserMemoryEntity memory = new AiUserMemoryEntity();
        memory.setId(id);
        memory.setCategory(category);
        memory.setMemoryType(type);
        memory.setTitle(type);
        memory.setContent(content);
        memory.setConfidence(new BigDecimal("0.90"));
        memory.setStatus("ACTIVE");
        return memory;
    }

    private static AiMemoryClaimEntity claim(Long id, String category, int supportCount, int contradictionCount) {
        AiMemoryClaimEntity claim = new AiMemoryClaimEntity();
        claim.id = id;
        claim.category = category;
        claim.confidence = new BigDecimal("0.90");
        claim.supportCount = supportCount;
        claim.contradictionCount = contradictionCount;
        claim.pinned = Boolean.FALSE;
        return claim;
    }
}
