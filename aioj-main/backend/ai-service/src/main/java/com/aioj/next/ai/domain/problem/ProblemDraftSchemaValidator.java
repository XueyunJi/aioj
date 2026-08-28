package com.aioj.next.ai.domain.problem;

import com.fasterxml.jackson.databind.JsonNode;

import java.util.ArrayList;
import java.util.List;
import java.util.Set;

public class ProblemDraftSchemaValidator {
    private static final Set<String> DIFFICULTIES = Set.of("EASY", "MEDIUM", "HARD", "CHALLENGE");
    private static final Set<String> LANGUAGES = Set.of("cpp", "python", "java");
    private static final List<String> REQUIRED_TEXT_FIELDS = List.of(
            "title",
            "statement",
            "notes",
            "standardSolutionCode",
            "testcaseGeneratorPython",
            "generationPlan"
    );

    public List<String> validate(JsonNode root) {
        List<String> errors = new ArrayList<>();
        if (root == null || root.isMissingNode() || root.isNull()) {
            errors.add("schema: root object is required");
            return errors;
        }
        if (!root.isObject()) {
            errors.add("schema: root must be a JSON object");
            return errors;
        }
        for (String field : REQUIRED_TEXT_FIELDS) {
            requireText(root, field, errors);
        }
        validateDifficulty(root, errors);
        validateLanguage(root, errors);
        validateOptionalLanguage(root, "referenceSolutionLanguage", errors);
        validateOptionalText(root, "referenceSolutionCode", errors);
        validateOptionalText(root, "stressTestcaseGeneratorPython", errors);
        validateInteger(root, "cfRating", 800, 3500, false, errors);
        validateInteger(root, "timeLimitMillis", 1, Integer.MAX_VALUE, true, errors);
        validateInteger(root, "memoryLimitKb", 1, Integer.MAX_VALUE, true, errors);
        validateTags(root.get("tags"), errors);
        validateTestCases(root.get("testCases"), errors);
        return errors;
    }

    public void requireValid(JsonNode root) {
        List<String> errors = validate(root);
        if (!errors.isEmpty()) {
            throw new SchemaValidationException(errors);
        }
    }

    private void validateDifficulty(JsonNode root, List<String> errors) {
        JsonNode value = root.get("difficulty");
        if (value == null || value.isNull()) {
            errors.add("schema: difficulty is required");
            return;
        }
        if (!value.isTextual() || !DIFFICULTIES.contains(value.asText())) {
            errors.add("schema: difficulty must be one of EASY, MEDIUM, HARD, CHALLENGE");
        }
    }

    private void validateLanguage(JsonNode root, List<String> errors) {
        JsonNode value = root.get("standardSolutionLanguage");
        if (value == null || value.isNull()) {
            errors.add("schema: standardSolutionLanguage is required");
            return;
        }
        if (!value.isTextual() || !LANGUAGES.contains(value.asText())) {
            errors.add("schema: standardSolutionLanguage must be one of cpp, python, java");
        }
    }

    private void validateOptionalLanguage(JsonNode root, String field, List<String> errors) {
        JsonNode value = root.get(field);
        if (value == null || value.isNull()) {
            return;
        }
        if (!value.isTextual() || !LANGUAGES.contains(value.asText())) {
            errors.add("schema: " + field + " must be one of cpp, python, java");
        }
    }

    private void validateOptionalText(JsonNode root, String field, List<String> errors) {
        JsonNode value = root.get(field);
        if (value == null || value.isNull()) {
            return;
        }
        if (!value.isTextual() || value.asText().isBlank()) {
            errors.add("schema: " + field + " must be a non-empty string");
        }
    }

    private void requireText(JsonNode root, String field, List<String> errors) {
        JsonNode value = root.get(field);
        if (value == null || value.isNull()) {
            errors.add("schema: " + field + " is required");
            return;
        }
        if (!value.isTextual() || value.asText().isBlank()) {
            errors.add("schema: " + field + " must be a non-empty string");
        }
    }

    private void validateInteger(JsonNode root, String field, int min, int max, boolean required, List<String> errors) {
        JsonNode value = root.get(field);
        if (value == null || value.isNull()) {
            if (required) {
                errors.add("schema: " + field + " is required");
            }
            return;
        }
        if (!value.isIntegralNumber() || value.asLong() < min || value.asLong() > max) {
            errors.add("schema: " + field + " must be an integer from " + min + " to " + max);
        }
    }

    private void validateTags(JsonNode node, List<String> errors) {
        if (node == null || node.isNull()) {
            errors.add("schema: tags is required");
            return;
        }
        if (!node.isArray()) {
            errors.add("schema: tags must be an array");
            return;
        }
        for (int index = 0; index < node.size(); index++) {
            JsonNode item = node.get(index);
            if (!item.isTextual() || item.asText().isBlank()) {
                errors.add("schema: tags[" + index + "] must be a non-empty string");
            }
        }
    }

    private void validateTestCases(JsonNode node, List<String> errors) {
        if (node == null || node.isNull()) {
            errors.add("schema: testCases is required");
            return;
        }
        if (!node.isArray()) {
            errors.add("schema: testCases must be an array");
            return;
        }
        if (node.size() < 3 || node.size() > 5) {
            errors.add("schema: testCases must include 3 to 5 sample cases");
        }
        for (int index = 0; index < node.size(); index++) {
            JsonNode item = node.get(index);
            if (!item.isObject()) {
                errors.add("schema: testCases[" + index + "] must be an object");
                continue;
            }
            requireCaseText(item, index, "input", errors);
            requireCaseText(item, index, "expectedOutput", errors);
            JsonNode sample = item.get("sample");
            if (sample == null || sample.isNull()) {
                errors.add("schema: testCases[" + index + "].sample is required");
            } else if (!sample.isBoolean() || !sample.asBoolean()) {
                errors.add("schema: testCases[" + index + "].sample must be true");
            }
        }
    }

    private void requireCaseText(JsonNode item, int index, String field, List<String> errors) {
        JsonNode value = item.get(field);
        if (value == null || value.isNull()) {
            errors.add("schema: testCases[" + index + "]." + field + " is required");
            return;
        }
        if (!value.isTextual() || value.asText().isBlank()) {
            errors.add("schema: testCases[" + index + "]." + field + " must be a non-empty string");
        }
    }

    public static class SchemaValidationException extends IllegalArgumentException {
        private final List<String> errors;

        public SchemaValidationException(List<String> errors) {
            super(String.join("; ", errors));
            this.errors = List.copyOf(errors);
        }

        public List<String> errors() {
            return errors;
        }
    }
}
