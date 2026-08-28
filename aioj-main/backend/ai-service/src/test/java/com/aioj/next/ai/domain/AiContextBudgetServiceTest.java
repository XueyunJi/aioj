package com.aioj.next.ai.domain;

import com.aioj.next.ai.config.AiProperties;
import com.aioj.next.ai.domain.context.AiContextBuildReport;
import com.aioj.next.ai.domain.context.AiContextReportBuilder;
import com.aioj.next.ai.domain.context.AiContextSection;
import com.aioj.next.contract.ai.AiChatRequest;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

class AiContextBudgetServiceTest {
    @Test
    void resolvesOfficialModelWindowsBeforeSuffixOrDefaultFallbacks() {
        AiProperties properties = new AiProperties();
        AiModelContextWindowRegistry registry = new AiModelContextWindowRegistry(properties);

        assertThat(registry.resolve("deepseek-v4-pro")).isEqualTo(1_000_000);
        assertThat(registry.resolve("deepseek-chat")).isEqualTo(1_000_000);
        assertThat(registry.resolve("kimi-k2.7-code")).isEqualTo(256_000);
        assertThat(registry.resolve("kimi-k2.6")).isEqualTo(256_000);
        assertThat(registry.resolve("moonshot-v1-8k")).isEqualTo(8_000);
        assertThat(registry.resolve("moonshot-v1-32k-vision-preview")).isEqualTo(32_000);
        assertThat(registry.resolve("moonshot-v1-128k")).isEqualTo(128_000);
        assertThat(registry.resolve("mock-16k")).isEqualTo(16_000);
        assertThat(registry.resolve("unknown-model")).isEqualTo(64_000);
    }

    @Test
    void sixteenKModelUsesSeventyPercentThresholdAndSixtyFourKDoesNotUseOldSevenThousandTrigger() {
        AiProperties properties = new AiProperties();
        properties.getContext().setEstimatorSafetyFactor(1.0);
        AiContextBudgetService budgetService = new AiContextBudgetService(
                properties,
                new AiModelContextWindowRegistry(properties)
        );
        AiContextReportBuilder reportBuilder = new AiContextReportBuilder();
        AiContextSection section = reportBuilder.section(
                "memory.long_term",
                "long_term_memory",
                "Long memory",
                50,
                "ai-service.memory",
                "memory",
                false,
                "m".repeat(42_000),
                Map.of()
        );
        AiContextBuildReport report = reportBuilder.build(List.of(section));
        AiChatRequest request = new AiChatRequest("c-budget", null, "继续", "assist", null, null, null);

        AiContextBudgetService.BudgetEvaluation sixteenK = budgetService.evaluate("mock-16k", request, report, "");
        AiContextBudgetService.BudgetEvaluation sixtyFourK = budgetService.evaluate("mock-64k", request, report, "");

        assertThat(sixteenK.modelWindowTokens()).isEqualTo(16_000);
        assertThat(sixteenK.compressionThresholdTokens()).isEqualTo(11_200);
        assertThat(sixteenK.maxPromptBudgetTokens()).isEqualTo(11_200);
        assertThat(sixteenK.overBudget()).isTrue();

        assertThat(sixtyFourK.modelWindowTokens()).isEqualTo(64_000);
        assertThat(sixtyFourK.compressionThresholdTokens()).isEqualTo(44_800);
        assertThat(sixtyFourK.estimatedPromptTokens()).isGreaterThan(7_000);
        assertThat(sixtyFourK.overBudget()).isFalse();
    }

    @Test
    void compositionEstimatorChargesCjkTextSignificantlyMoreThanLegacyCharsOverFour() {
        AiProperties properties = new AiProperties();
        properties.getContext().setEstimatorSafetyFactor(1.0);
        AiContextBudgetService budgetService = new AiContextBudgetService(
                properties,
                new AiModelContextWindowRegistry(properties)
        );
        AiContextReportBuilder reportBuilder = new AiContextReportBuilder();
        // Legacy estimator: 400 / 4 = 100 tokens. Composition: 400 * 0.6 = 240.
        AiContextSection section = reportBuilder.section(
                "memory.long_term",
                "long_term_memory",
                "Chinese memory",
                50,
                "ai-service.memory",
                "memory",
                false,
                "算".repeat(400),
                Map.of()
        );
        AiContextBuildReport report = reportBuilder.build(List.of(section));
        AiChatRequest request = new AiChatRequest("c-estimate", null, "继续", "assist", null, null, null);

        AiContextBudgetService.BudgetEvaluation evaluation = budgetService.evaluate("mock-64k", request, report, "");

        assertThat(evaluation.estimatedBySection().get("memory.long_term")).isEqualTo(240);
    }

    @Test
    void ewmaCalibrationMovesEstimateTowardsProviderReportedUsage() {
        AiProperties properties = new AiProperties();
        properties.getContext().setEstimatorSafetyFactor(1.0);
        AiContextBudgetService budgetService = new AiContextBudgetService(
                properties,
                new AiModelContextWindowRegistry(properties)
        );
        AiContextReportBuilder reportBuilder = new AiContextReportBuilder();
        AiContextSection section = reportBuilder.section(
                "memory.long_term",
                "long_term_memory",
                "ASCII memory",
                50,
                "ai-service.memory",
                "memory",
                false,
                "a".repeat(400),
                Map.of()
        );
        AiContextBuildReport report = reportBuilder.build(List.of(section));
        AiChatRequest request = new AiChatRequest("c-ewma", null, "继续", "assist", null, null, null);

        assertThat(budgetService.estimationRatio()).isEqualTo(1.0);
        AiContextBudgetService.BudgetEvaluation before = budgetService.evaluate("mock-64k", request, report, "");

        // Provider reports 50% more tokens than estimated: ratio must rise.
        budgetService.recordActualUsage(before.estimatedPromptTokens(), (long) (before.estimatedPromptTokens() * 1.5));
        double raised = budgetService.estimationRatio();
        assertThat(raised).isGreaterThan(1.0);
        AiContextBudgetService.BudgetEvaluation after = budgetService.evaluate("mock-64k", request, report, "");
        assertThat(after.estimatedPromptTokens()).isGreaterThan(before.estimatedPromptTokens());

        // Provider then reports fewer tokens than estimated: ratio must fall.
        budgetService.recordActualUsage(after.estimatedPromptTokens(), (long) (after.estimatedPromptTokens() * 0.5));
        assertThat(budgetService.estimationRatio()).isLessThan(raised);

        // Degenerate inputs are ignored instead of corrupting the EWMA.
        double stable = budgetService.estimationRatio();
        budgetService.recordActualUsage(0, 1000);
        budgetService.recordActualUsage(1000, 0);
        assertThat(budgetService.estimationRatio()).isEqualTo(stable);
    }
}
