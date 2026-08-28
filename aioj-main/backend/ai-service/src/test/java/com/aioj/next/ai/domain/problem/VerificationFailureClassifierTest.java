package com.aioj.next.ai.domain.problem;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class VerificationFailureClassifierTest {
    private final VerificationFailureClassifier classifier = new VerificationFailureClassifier(new ObjectMapper());

    @Test
    void classifyOfficialGeneratorFailureLimitsRepairToOfficialGenerator() {
        RepairTask task = classifier.classify("""
                {"sandboxReport":{"errors":[{"code":"GENERATOR_PYTHON_FAILED","field":"testcaseGeneratorPython","message":"NameError"}]}}
                """);

        assertThat(task.category()).isEqualTo("OFFICIAL_GENERATOR_ERROR");
        assertThat(task.allowedFields()).containsExactly("testcaseGeneratorPython");
        assertThat(task.forbiddenFields()).contains("standardSolutionCode", "stressTestcaseGeneratorPython");
    }

    @Test
    void classifyCombinedGeneratorFailuresLimitsRepairToBothGenerators() {
        RepairTask task = classifier.classify("""
                {"sandboxReport":{"errors":[{"code":"GENERATOR_PYTHON_FAILED","field":"testcaseGeneratorPython","message":"generator.py failed"}]},
                 "crossCheckReport":{"errors":[{"code":"REFERENCE_GENERATOR_FAILED","field":"stressTestcaseGeneratorPython","message":"NameError: std_exe is not defined"}]}}
                """);

        assertThat(task.category()).isEqualTo("GENERATOR_AND_STRESS_GENERATOR_ERROR");
        assertThat(task.allowedFields()).containsExactly("testcaseGeneratorPython", "stressTestcaseGeneratorPython");
        assertThat(task.forbiddenFields()).contains("title", "statement", "tags", "standardSolutionCode");
    }

    @Test
    void classifyStandardRuntimeFailureLimitsRepairToStandardFields() {
        RepairTask task = classifier.classify("""
                {"sandboxReport":{"errors":[{"code":"STANDARD_TLE_ON_GENERATED_CASE","field":"standardSolutionCode","message":"case 1 TLE"}]}}
                """);

        assertThat(task.category()).isEqualTo("STANDARD_COMPLEXITY_OR_RUNTIME");
        assertThat(task.allowedFields()).contains("standardSolutionCode", "generationPlan");
        assertThat(task.forbiddenFields()).contains("testcaseGeneratorPython", "stressTestcaseGeneratorPython");
    }

    @Test
    void classifyUnboundedOutputRiskAllowsSpecAndStandardRepair() {
        RepairTask task = classifier.classify("""
                {"complexityReport":{"errors":[{"code":"DATA_RANGE_OUTPUT_UNBOUNDED","field":"statement","message":"output whole ranges"}]}}
                """);

        assertThat(task.category()).isEqualTo("DATA_RANGE_OR_SPEC_RISK");
        assertThat(task.allowedFields()).contains("statement", "generationPlan", "standardSolutionCode", "testCases");
        assertThat(task.forbiddenFields()).contains("testcaseGeneratorPython", "stressTestcaseGeneratorPython",
                "timeLimitMillis", "memoryLimitKb");
    }

    @Test
    void classifyCombinedSampleComplexityAndSpecFailuresKeepsRepairScopedToContentConsistency() {
        RepairTask task = classifier.classify("""
                {"sandboxReport":{"errors":[{"code":"SANDBOX_SAMPLE_MISMATCH","field":"testCases[4].expectedOutput","message":"expected 8 got 9"}]},
                 "complexityReport":{"errors":[
                   {"code":"COMPLEXITY_RISK_HIGH_NAIVE_LOOP","field":"standardSolutionCode","message":"nested loops"},
                   {"code":"DATA_RANGE_OUTPUT_UNBOUNDED","field":"statement","message":"unbounded output"}
                 ]}}
                """);

        assertThat(task.category()).isEqualTo("SPEC_SAMPLE_AND_STANDARD_REPAIR");
        assertThat(task.allowedFields()).contains("statement", "generationPlan", "standardSolutionCode", "testCases");
        assertThat(task.forbiddenFields()).contains("title", "tags", "testcaseGeneratorPython", "stressTestcaseGeneratorPython");
    }

    @Test
    void classifyStressGeneratorFailureLimitsRepairToStressGenerator() {
        RepairTask task = classifier.classify("""
                {"crosscheckReport":{"errors":[{"code":"REFERENCE_INPUTS_REQUIRED","field":"stressTestcaseGeneratorPython"}]}}
                """);

        assertThat(task.category()).isEqualTo("STRESS_GENERATOR_ERROR");
        assertThat(task.allowedFields()).containsExactly("stressTestcaseGeneratorPython");
        assertThat(task.forbiddenFields()).contains("testcaseGeneratorPython", "standardSolutionCode");
    }

    @Test
    void classifyUnknownReportRequiresManualReview() {
        RepairTask task = classifier.classify("""
                {"status":"FAILED"}
                """);

        assertThat(task.category()).isEqualTo("UNKNOWN_REQUIRES_MANUAL_REVIEW");
        assertThat(task.allowedFields()).isEmpty();
        assertThat(task.forbiddenFields()).contains("standardSolutionCode", "testcaseGeneratorPython");
    }
}
