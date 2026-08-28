package com.aioj.next.ai.domain.problem;

import com.aioj.next.ai.config.AiProperties;
import com.aioj.next.contract.ai.ProblemDraftRequest;
import com.aioj.next.contract.ai.ProblemDraftResponse;
import com.aioj.next.contract.problem.TestCaseDto;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

@Component
public class ProblemDraftStaticValidator implements ProblemDraftVerifier {
    private final List<String> allowedTags;

    public ProblemDraftStaticValidator(AiProperties properties) {
        List<String> configuredTags = properties == null || properties.getProblemDraft() == null
                ? List.of()
                : properties.getProblemDraft().getAllowedTags();
        this.allowedTags = configuredTags == null ? List.of() : configuredTags.stream()
                .filter(tag -> tag != null && !tag.isBlank())
                .map(tag -> tag.trim().toLowerCase(Locale.ROOT))
                .distinct()
                .toList();
    }

    public VerificationReport validate(ProblemDraftResponse draft) {
        return verify(draft, VerificationOptions.defaults(allowedTags));
    }

    public VerificationReport validate(ProblemDraftResponse draft, ProblemDraftRequest request) {
        return verify(draft, VerificationOptions.fromRequest(request, allowedTags));
    }

    @Override
    public VerificationReport verify(ProblemDraftResponse draft, VerificationOptions options) {
        List<VerificationError> errors = new ArrayList<>();
        List<VerificationWarning> warnings = new ArrayList<>();
        if (draft == null) {
            errors.add(error("NO_DRAFT", "Provider returned no draft", null));
            return VerificationReport.of(errors, warnings);
        }
        validateRequiredFields(draft, options, errors);
        validateTags(draft.tags(), errors);
        validateTestCases(draft.testCases(), errors);
        validateCfRating(draft, options, warnings);
        return VerificationReport.of(errors, warnings);
    }

    public List<String> allowedTags() {
        return allowedTags;
    }

    private void validateRequiredFields(ProblemDraftResponse draft, VerificationOptions options, List<VerificationError> errors) {
        if (draft.title() == null || draft.title().isBlank()) {
            errors.add(error("TITLE_REQUIRED", "title is required", "title"));
        }
        if (resolvedDifficulty(draft, options) == null) {
            errors.add(error("DIFFICULTY_REQUIRED", "difficulty is required", "difficulty"));
        }
        if (draft.statement() == null || draft.statement().isBlank()) {
            errors.add(error("STATEMENT_REQUIRED", "statement is required", "statement"));
        } else if (statementContainsSeparatedContent(draft.statement())) {
            errors.add(error("STATEMENT_CONTAINS_SEPARATED_CONTENT",
                    "statement must only contain problem description, input description, and output description",
                    "statement"));
        }
        if (draft.standardSolutionCode() == null || draft.standardSolutionCode().isBlank()) {
            errors.add(error("STANDARD_SOLUTION_REQUIRED", "standardSolutionCode is required", "standardSolutionCode"));
        }
        if (options != null && options.enableReferenceCheck()) {
            if (draft.referenceSolutionLanguage() == null || draft.referenceSolutionLanguage().isBlank()) {
                errors.add(error("REFERENCE_LANGUAGE_REQUIRED", "referenceSolutionLanguage is required when reference check is enabled", "referenceSolutionLanguage"));
            }
            if (draft.referenceSolutionCode() == null || draft.referenceSolutionCode().isBlank()) {
                errors.add(error("REFERENCE_SOLUTION_REQUIRED", "referenceSolutionCode is required when reference check is enabled", "referenceSolutionCode"));
            }
            if (draft.stressTestcaseGeneratorPython() == null || draft.stressTestcaseGeneratorPython().isBlank()) {
                errors.add(error("REFERENCE_GENERATOR_REQUIRED",
                        "stressTestcaseGeneratorPython is required when reference check is enabled",
                        "stressTestcaseGeneratorPython"));
            }
        }
        if (draft.testcaseGeneratorPython() == null || draft.testcaseGeneratorPython().isBlank()) {
            errors.add(error("TESTCASE_GENERATOR_REQUIRED", "testcaseGeneratorPython is required", "testcaseGeneratorPython"));
        }
    }

    private void validateTags(List<String> tags, List<VerificationError> errors) {
        if (tags == null) {
            errors.add(error("TAGS_ARRAY_REQUIRED", "tags must be an array", "tags"));
        }
    }

    private void validateTestCases(List<TestCaseDto> testCases, List<VerificationError> errors) {
        if (testCases == null || testCases.isEmpty()) {
            errors.add(error("TEST_CASE_COUNT", "testCases must include 3 to 5 sample cases", "testCases"));
            return;
        }
        if (testCases.size() < 3 || testCases.size() > 5) {
            errors.add(error("TEST_CASE_COUNT", "testCases must include 3 to 5 sample cases", "testCases"));
        }
        for (int i = 0; i < testCases.size(); i++) {
            TestCaseDto tc = testCases.get(i);
            if (tc.input() == null || tc.input().isBlank()) {
                errors.add(error("TEST_CASE_INPUT_BLANK", "testCases[" + i + "].input is blank", "testCases[" + i + "].input"));
            } else if (containsPlaceholderCaseText(tc.input())) {
                errors.add(error("TEST_CASE_INPUT_PLACEHOLDER", "testCases[" + i + "].input must be concrete sample data", "testCases[" + i + "].input"));
            }
            if (tc.expectedOutput() == null || tc.expectedOutput().isBlank()) {
                errors.add(error("TEST_CASE_OUTPUT_BLANK", "testCases[" + i + "].expectedOutput is blank", "testCases[" + i + "].expectedOutput"));
            } else if (containsPlaceholderCaseText(tc.expectedOutput())) {
                errors.add(error("TEST_CASE_OUTPUT_PLACEHOLDER", "testCases[" + i + "].expectedOutput must be concrete sample data", "testCases[" + i + "].expectedOutput"));
            }
            if (!tc.sample()) {
                errors.add(error("TEST_CASE_SAMPLE_FLAG", "testCases[" + i + "] must be marked sample=true", "testCases[" + i + "].sample"));
            }
        }
    }

    private void validateCfRating(ProblemDraftResponse draft, VerificationOptions options, List<VerificationWarning> warnings) {
        if (options == null || options.cfRating() == null) {
            return;
        }
        String difficulty = resolvedDifficulty(draft, options);
        ProblemDraftDifficulty.RatingRange range = ProblemDraftDifficulty.rangeForDifficulty(difficulty);
        if (range == null) {
            return;
        }
        int rating = options.cfRating();
        if (rating < range.min() || rating > range.max()) {
            warnings.add(new VerificationWarning(
                    "CF_RATING_DIFFICULTY_MISMATCH",
                    "cfRating " + rating + " is outside " + range.difficulty() + " range " + range.min() + "-" + range.max(),
                    "cfRating"
            ));
        }
    }

    private boolean containsPlaceholderCaseText(String value) {
        String normalized = value == null ? "" : value.toLowerCase(Locale.ROOT);
        return normalized.contains("随机生成")
                || normalized.contains("脚本生成")
                || normalized.contains("由脚本")
                || normalized.contains("此处省略")
                || normalized.contains("省略具体")
                || normalized.contains("generated by script")
                || normalized.contains("omitted");
    }

    private boolean statementContainsSeparatedContent(String statement) {
        if (statement == null || statement.isBlank()) {
            return false;
        }
        String normalized = statement.toLowerCase(Locale.ROOT);
        return normalized.contains("样例输入")
                || normalized.contains("样例输出")
                || normalized.contains("样例说明")
                || normalized.contains("示例输入")
                || normalized.contains("示例输出")
                || normalized.contains("题解说明")
                || normalized.contains("题解")
                || normalized.contains("复杂度")
                || normalized.contains("sample input")
                || normalized.contains("sample output")
                || normalized.contains("sample explanation")
                || normalized.contains("solution explanation")
                || normalized.contains("complexity");
    }

    private VerificationError error(String code, String message, String field) {
        return new VerificationError(code, message, field);
    }

    private String resolvedDifficulty(ProblemDraftResponse draft, VerificationOptions options) {
        if (draft.difficulty() != null && !draft.difficulty().isBlank()) {
            return draft.difficulty();
        }
        if (options == null || (options.requestedDifficulty() == null && options.cfRating() == null)) {
            return null;
        }
        return ProblemDraftDifficulty.effective(options.requestedDifficulty(), options.cfRating());
    }
}
