package com.aioj.next.ai.domain.problem;

import com.aioj.next.contract.ai.ProblemDraftRequest;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class AlgorithmFitCheckerTest {
    private final AlgorithmFitChecker checker = new AlgorithmFitChecker();

    @Test
    void rejectsWhenRequestedAlgorithmIsMissingFromPlan() {
        AlgorithmFitCheckResult result = checker.check(
                request("线段树", 1800),
                planning(plan("排序", List.of("贪心"), "排序后贪心选择", 1800))
        );

        assertThat(result.passed()).isFalse();
        assertThat(result.errors()).anySatisfy(error -> assertThat(error).contains("线段树"));
    }

    @Test
    void acceptsWhenOneRequestedAlgorithmIsCoreAndAnotherIsSecondary() {
        AlgorithmFitCheckResult result = checker.check(
                request("线段树,排序", 1800),
                planning(plan("线段树", List.of("排序"), "线段树维护区间统计，排序离线处理", 1800))
        );

        assertThat(result.passed()).isTrue();
        assertThat(result.errors()).isEmpty();
    }

    @Test
    void rejectsWhenEstimatedRatingIsOutsideTolerance() {
        AlgorithmFitCheckResult low = checker.check(
                request("线段树", 1800),
                planning(plan("线段树", List.of(), "线段树维护答案", 1300))
        );
        AlgorithmFitCheckResult high = checker.check(
                request("线段树", 1800),
                planning(plan("线段树", List.of(), "线段树维护答案", 2300))
        );

        assertThat(low.passed()).isFalse();
        assertThat(high.passed()).isFalse();
        assertThat(low.errors()).anySatisfy(error -> assertThat(error).contains("estimatedCfRating 1300"));
        assertThat(high.errors()).anySatisfy(error -> assertThat(error).contains("estimatedCfRating 2300"));
    }

    @Test
    void rejectsHighRatingPlanMissingRequiredDesignEvidence() {
        ProblemDesignPlan incomplete = new ProblemDesignPlan(
                "Missing evidence",
                "HARD",
                "线段树",
                List.of(),
                "线段树维护答案",
                "",
                "",
                "O(n)",
                List.of(),
                List.of(),
                List.of(),
                1800,
                List.of("线段树"),
                2000,
                262144
        );

        AlgorithmFitCheckResult result = checker.check(request("线段树", 1800), planning(incomplete));

        assertThat(result.passed()).isFalse();
        assertThat(result.errors()).contains(
                "constraints are required for cfRating >= 1700",
                "expectedTimeComplexity is required for cfRating >= 1700",
                "boundaryCases are required for cfRating >= 1700",
                "commonWrongApproaches are required for cfRating >= 1700"
        );
    }

    @Test
    void rejectsModelDeclaredViolations() {
        ProblemDraftPlanningResult planning = new ProblemDraftPlanningResult(
                "{}",
                plan("线段树", List.of(), "线段树维护答案", 1800),
                new ProblemDesignFitCheck(true, false, true, true, List.of("算法不匹配"), List.of("改为线段树"))
        );

        AlgorithmFitCheckResult result = checker.check(request("线段树", 1800), planning);

        assertThat(result.passed()).isFalse();
        assertThat(result.errors()).anySatisfy(error -> assertThat(error).contains("算法不匹配"));
    }

    @Test
    void rejectsObviousLargeConstraintQuadraticContradiction() {
        ProblemDesignPlan plan = new ProblemDesignPlan(
                "Quadratic bad",
                "HARD",
                "线段树",
                List.of(),
                "线段树维护答案",
                "1 <= n <= 200000",
                "O(n^2)",
                "O(n)",
                List.of("最大 n"),
                List.of("暴力枚举"),
                List.of("证明维护不变量"),
                1800,
                List.of("线段树"),
                2000,
                262144
        );

        AlgorithmFitCheckResult result = checker.check(request("线段树", 1800), planning(plan));

        assertThat(result.passed()).isFalse();
        assertThat(result.errors()).anySatisfy(error -> assertThat(error).contains("large constraints conflict"));
    }

    private ProblemDraftPlanningResult planning(ProblemDesignPlan plan) {
        return new ProblemDraftPlanningResult(
                "{}",
                plan,
                new ProblemDesignFitCheck(true, true, true, true, List.of(), List.of())
        );
    }

    private ProblemDesignPlan plan(String coreAlgorithm, List<String> secondaryAlgorithms,
                                   String coreObservation, int estimatedCfRating) {
        return new ProblemDesignPlan(
                "Good plan",
                "HARD",
                coreAlgorithm,
                secondaryAlgorithms,
                coreObservation,
                "1 <= n <= 200000",
                "O(n log n)",
                "O(n)",
                List.of("最小输入", "最大输入"),
                List.of("O(n^2) 暴力"),
                List.of("证明维护不变量"),
                estimatedCfRating,
                List.of("算法"),
                2000,
                262144
        );
    }

    private ProblemDraftRequest request(String algorithm, Integer cfRating) {
        return new ProblemDraftRequest(
                algorithm,
                "HARD",
                cfRating,
                null,
                algorithm,
                List.of(algorithm),
                null,
                "第一行 n",
                "1 <= n <= 200000",
                "避免模板题",
                "cpp",
                null,
                null,
                null,
                12,
                null,
                null,
                false,
                false
        );
    }
}
