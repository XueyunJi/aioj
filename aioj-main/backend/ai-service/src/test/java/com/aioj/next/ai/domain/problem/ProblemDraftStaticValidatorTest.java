package com.aioj.next.ai.domain.problem;

import com.aioj.next.ai.config.AiProperties;
import com.aioj.next.contract.ai.ProblemDraftRequest;
import com.aioj.next.contract.ai.ProblemDraftResponse;
import com.aioj.next.contract.problem.TestCaseDto;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class ProblemDraftStaticValidatorTest {
    private final ProblemDraftStaticValidator validator = new ProblemDraftStaticValidator(new AiProperties());

    @Test
    void validatePreservesExistingInvalidRules() {
        ProblemDraftResponse draft = draft(
                "title",
                "EASY",
                """
                        题目描述 给定数组。
                        样例输入
                        1
                        样例输出
                        1
                        """,
                List.of(new TestCaseDto("由脚本生成\n", "omitted\n", false)),
                List.of("implementation")
        );

        VerificationReport report = validator.validate(draft);

        assertThat(report.passed()).isFalse();
        assertThat(report.errorMessages()).contains(
                "statement must only contain problem description, input description, and output description",
                "testCases must include 3 to 5 sample cases",
                "testCases[0].input must be concrete sample data",
                "testCases[0].expectedOutput must be concrete sample data",
                "testCases[0] must be marked sample=true"
        );
    }

    @Test
    void validateAcceptsConfiguredChineseTags() {
        ProblemDraftResponse draft = draft(
                "title",
                "MEDIUM",
                "题目描述 输入描述 输出描述",
                validCases(),
                List.of("哈希", "数组", "排序", "sorting")
        );

        VerificationReport report = validator.validate(draft);

        assertThat(report.errorMessages()).isEmpty();
    }

    @Test
    void validateAcceptsArbitraryTags() {
        ProblemDraftResponse draft = draft(
                "title",
                "EASY",
                "题目描述 输入描述 输出描述",
                validCases(),
                List.of("array", "排序", "自定义标签", "not-in-any-whitelist")
        );

        VerificationReport report = validator.validate(draft);

        assertThat(report.errorMessages()).isEmpty();
    }

    @Test
    void validateUsesCfRatingWhenDifficultyIsMissingFromRequest() {
        ProblemDraftResponse draft = draft(
                "title",
                null,
                "题目描述 输入描述 输出描述",
                validCases(),
                List.of("哈希", "数组")
        );
        ProblemDraftRequest request = new ProblemDraftRequest(
                "数组",
                null,
                1250,
                null,
                null,
                null,
                null,
                null,
                null,
                null,
                "cpp",
                null,
                null,
                null,
                null,
                null,
                null,
                null,
                null
        );

        VerificationReport report = validator.validate(draft, request);

        assertThat(report.errorMessages()).isEmpty();
        assertThat(report.warningMessages()).isEmpty();
    }

    @Test
    void validateReportsCfRatingMismatchAsWarningOnly() {
        ProblemDraftResponse draft = draft(
                "title",
                "EASY",
                "题目描述 输入描述 输出描述",
                validCases(),
                List.of("implementation")
        );
        ProblemDraftRequest request = new ProblemDraftRequest(
                "数组",
                "EASY",
                1500,
                null,
                null,
                null,
                null,
                null,
                null,
                null,
                "cpp",
                null,
                null,
                null,
                null,
                null,
                null,
                null,
                null
        );

        VerificationReport report = validator.validate(draft, request);

        assertThat(report.errorMessages()).isEmpty();
        assertThat(report.warningMessages()).contains("cfRating 1500 is outside EASY range 800-1200");
    }

    @Test
    void validateRequiresReferenceSolverOnlyWhenReferenceCheckEnabled() {
        ProblemDraftResponse draft = draft(
                "title",
                "EASY",
                "题目描述 输入描述 输出描述",
                validCases(),
                List.of("array")
        );
        ProblemDraftRequest request = new ProblemDraftRequest(
                "数组",
                "EASY",
                null,
                null,
                null,
                null,
                null,
                null,
                null,
                null,
                "cpp",
                null,
                null,
                null,
                null,
                null,
                null,
                null,
                true
        );

        VerificationReport report = validator.validate(draft, request);

        assertThat(report.errorMessages()).contains(
                "referenceSolutionLanguage is required when reference check is enabled",
                "referenceSolutionCode is required when reference check is enabled",
                "stressTestcaseGeneratorPython is required when reference check is enabled"
        );
    }

    @Test
    void validateDefaultsReferenceCheckForHighRatingWhenUnset() {
        ProblemDraftResponse draft = draft(
                "title",
                "MEDIUM",
                "题目描述 输入描述 输出描述",
                validCases(),
                List.of("array")
        );
        ProblemDraftRequest request = referenceRequest(1700, null);

        VerificationReport report = validator.validate(draft, request);

        assertThat(report.errorMessages()).contains(
                "referenceSolutionLanguage is required when reference check is enabled",
                "referenceSolutionCode is required when reference check is enabled",
                "stressTestcaseGeneratorPython is required when reference check is enabled"
        );
    }

    @Test
    void validateAllowsHighRatingReferenceCheckWhenExplicitlyDisabled() {
        ProblemDraftResponse draft = draft(
                "title",
                "MEDIUM",
                "题目描述 输入描述 输出描述",
                validCases(),
                List.of("array")
        );
        ProblemDraftRequest request = referenceRequest(1700, false);

        VerificationReport report = validator.validate(draft, request);

        assertThat(report.errorMessages()).isEmpty();
    }

    private ProblemDraftResponse draft(
            String title,
            String difficulty,
            String statement,
            List<TestCaseDto> testCases,
            List<String> tags
    ) {
        return new ProblemDraftResponse(
                1L,
                "PENDING_REVIEW",
                title,
                difficulty,
                statement,
                "notes",
                "cpp",
                "int main(){return 0;}",
                "from pathlib import Path\nPath('testcases').mkdir(exist_ok=True)",
                "plan",
                tags,
                "VALID",
                List.of(),
                testCases,
                1000,
                262144,
                null,
                "mock",
                1,
                1,
                Instant.parse("2026-06-01T00:00:00Z"),
                null,
                null,
                null,
                null,
                null
        );
    }

    private List<TestCaseDto> validCases() {
        return List.of(
                new TestCaseDto("1\n", "1\n", true),
                new TestCaseDto("2\n", "2\n", true),
                new TestCaseDto("3\n", "3\n", true)
        );
    }

    private ProblemDraftRequest referenceRequest(Integer cfRating, Boolean enableReferenceCheck) {
        return new ProblemDraftRequest(
                "数组",
                "MEDIUM",
                cfRating,
                null,
                null,
                null,
                null,
                null,
                null,
                null,
                "cpp",
                null,
                null,
                null,
                null,
                null,
                null,
                null,
                enableReferenceCheck
        );
    }
}
