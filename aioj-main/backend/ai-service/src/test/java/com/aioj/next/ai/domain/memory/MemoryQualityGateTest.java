package com.aioj.next.ai.domain.memory;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;

import java.util.stream.Stream;

import static org.assertj.core.api.Assertions.assertThat;

class MemoryQualityGateTest {
    private final MemoryQualityGate gate = new MemoryQualityGate();

    @ParameterizedTest
    @MethodSource("rejectedGoldenCases")
    void rejectsProblemCodeHypotheticalAndTemporaryNoise(String userMessage, String canonicalText, String expectedReason) {
        MemoryQualityGate.GateResult result = gate.evaluate(
                candidate("PREFERENCE", "guidance_preference", canonicalText, "EXPLICIT_PREFERENCE", true),
                new MemoryQualityGate.MessageContext(userMessage, "")
        );

        assertThat(result.accepted()).isFalse();
        assertThat(result.status()).isEqualTo("REJECTED");
        assertThat(result.rejectedReason()).isEqualTo(expectedReason);
    }

    @Test
    void writesExplicitRuleOrPreferenceAsActiveWhenHighQuality() {
        MemoryQualityGate.GateResult language = gate.evaluate(
                candidate("PREFERENCE", "preferred_language", "以后讲题默认用 C++。", "EXPLICIT_PREFERENCE", true),
                new MemoryQualityGate.MessageContext("以后讲题默认用 C++。", "")
        );
        MemoryQualityGate.GateResult rule = gate.evaluate(
                candidate("RULE", "no_direct_full_answer", "记住，我不喜欢你直接给完整答案，先给提示。", "EXPLICIT_REMEMBER", true),
                new MemoryQualityGate.MessageContext("记住，我不喜欢你直接给完整答案，先给提示。", "")
        );

        assertThat(language.accepted()).isTrue();
        assertThat(language.status()).isEqualTo("ACTIVE");
        assertThat(rule.accepted()).isTrue();
        assertThat(rule.status()).isEqualTo("ACTIVE");
    }

    @Test
    void keepsActualProfileAsConfirmationInsteadOfImmediateActive() {
        MemoryQualityGate.GateResult result = gate.evaluate(
                candidate("PROFILE", "name_preference", "你可以叫我 Elvis。", "EXPLICIT_REMEMBER", true),
                new MemoryQualityGate.MessageContext("你可以叫我 Elvis。", "")
        );

        assertThat(result.accepted()).isTrue();
        assertThat(result.needsConfirmation()).isTrue();
        assertThat(result.status()).isEqualTo("NEEDS_CONFIRMATION");
        assertThat(result.ambiguityFlags()).contains("profile_needs_confirmation");
    }

    @Test
    void keepsWeaknessAndLearningGoalAsReviewCandidates() {
        MemoryQualityGate.GateResult weakness = gate.evaluate(
                candidate("WEAKNESS", "binary_search_boundary", "我总是搞混二分的 l 和 r，之后遇到二分多提醒我。", "EXPLICIT_REMEMBER", true),
                new MemoryQualityGate.MessageContext("我总是搞混二分的 l 和 r，之后遇到二分多提醒我。", "")
        );
        MemoryQualityGate.GateResult goal = gate.evaluate(
                candidate("GOAL", "lanqiao_goal", "我正在准备蓝桥杯，之后刷题建议围绕这个来。", "EXPLICIT_REMEMBER", true),
                new MemoryQualityGate.MessageContext("我正在准备蓝桥杯，之后刷题建议围绕这个来。", "")
        );

        assertThat(weakness.accepted()).isTrue();
        assertThat(weakness.status()).isEqualTo("NEEDS_CONFIRMATION");
        assertThat(weakness.ambiguityFlags()).contains("high_impact_weakness");
        assertThat(goal.accepted()).isTrue();
        assertThat(goal.status()).isEqualTo("NEEDS_CONFIRMATION");
        assertThat(goal.ambiguityFlags()).contains("high_impact_goal");
    }

    @Test
    void keepsLowConfidenceOneOffSignalAsCandidate() {
        MemoryQualityGate.GateResult result = gate.evaluate(
                candidate("PREFERENCE", "guidance_preference", "以后可以先给我一点提示。", "EXPLICIT_PREFERENCE", true, 0.62),
                new MemoryQualityGate.MessageContext("以后可以先给我一点提示。", "")
        );

        assertThat(result.accepted()).isTrue();
        assertThat(result.status()).isEqualTo("NEEDS_CONFIRMATION");
        assertThat(result.qualityFlags()).contains("low_confidence");
    }

    @Test
    void rejectsSensitiveAndCodeLikeMemoryWithStableFlags() {
        MemoryQualityGate.GateResult secret = gate.evaluate(
                candidate("PREFERENCE", "api_key", "我的 token 是 sk-secret-value。", "EXPLICIT_REMEMBER", true),
                new MemoryQualityGate.MessageContext("记住我的 token 是 sk-secret-value。", "")
        );
        MemoryQualityGate.GateResult code = gate.evaluate(
                candidate("PREFERENCE", "code_style", "```cpp\nint main(){return 0;}\n```", "EXPLICIT_REMEMBER", true),
                new MemoryQualityGate.MessageContext("记住这段代码。", "")
        );

        assertThat(secret.accepted()).isFalse();
        assertThat(secret.rejectedReason()).isEqualTo("privacy_sensitive");
        assertThat(secret.qualityFlags()).contains("privacy_sensitive");
        assertThat(code.accepted()).isFalse();
        assertThat(code.rejectedReason()).isEqualTo("code_noise");
        assertThat(code.qualityFlags()).contains("code_noise");
    }

    static Stream<Arguments> rejectedGoldenCases() {
        return Stream.of(
                Arguments.of("这道题我想用 Python。", "这道题我想用 Python。", "problem_noise"),
                Arguments.of("假设我叫张三。", "用户姓名是张三。", "hypothetical"),
                Arguments.of("题目里的小明是男生。", "题目里的小明是男生。", "problem_noise"),
                Arguments.of("当前代码的 ans 变量错了。", "当前代码的 ans 变量错了。", "code_noise"),
                Arguments.of("样例输入是 1 2 3。", "样例输入是 1 2 3。", "problem_noise"),
                Arguments.of("我这次只想直接看答案。", "我这次只想直接看答案。", "temporary_preference")
        );
    }

    private static MemoryQualityGate.MemoryCandidate candidate(
            String category,
            String key,
            String text,
            String evidenceType,
            boolean longTerm
    ) {
        return candidate(category, key, text, evidenceType, longTerm, 0.96);
    }

    private static MemoryQualityGate.MemoryCandidate candidate(
            String category,
            String key,
            String text,
            String evidenceType,
            boolean longTerm,
            double confidence
    ) {
        return new MemoryQualityGate.MemoryCandidate(
                category,
                key,
                text,
                "{}",
                "GLOBAL",
                null,
                evidenceType,
                confidence,
                longTerm,
                false,
                false,
                false,
                false
        );
    }
}
