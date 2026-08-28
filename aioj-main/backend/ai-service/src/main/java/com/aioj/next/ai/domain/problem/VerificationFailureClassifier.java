package com.aioj.next.ai.domain.problem;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

import java.util.ArrayList;
import java.util.Iterator;
import java.util.List;
import java.util.Locale;
import java.util.Map;

public class VerificationFailureClassifier {
    private static final List<String> CONTENT_FIELDS = List.of(
            "title", "difficulty", "statement", "notes", "standardSolutionLanguage", "standardSolutionCode",
            "referenceSolutionLanguage", "referenceSolutionCode", "testcaseGeneratorPython",
            "stressTestcaseGeneratorPython", "generationPlan", "tags", "testCases", "timeLimitMillis", "memoryLimitKb"
    );
    private static final List<String> STANDARD_FIELDS = List.of(
            "standardSolutionLanguage", "standardSolutionCode", "generationPlan", "timeLimitMillis", "memoryLimitKb"
    );
    private static final List<String> GENERATOR_FIELDS = List.of("testcaseGeneratorPython");
    private static final List<String> STRESS_GENERATOR_FIELDS = List.of("stressTestcaseGeneratorPython");
    private static final List<String> COMBINED_GENERATOR_FIELDS = List.of(
            "testcaseGeneratorPython", "stressTestcaseGeneratorPython"
    );
    private static final List<String> REFERENCE_FIELDS = List.of(
            "standardSolutionCode", "referenceSolutionLanguage", "referenceSolutionCode",
            "stressTestcaseGeneratorPython", "testCases", "notes", "generationPlan"
    );
    private static final List<String> SAMPLE_FIELDS = List.of("testCases", "notes", "standardSolutionCode");
    private static final List<String> SPEC_FIELDS = List.of(
            "statement", "notes", "generationPlan", "testCases", "standardSolutionCode",
            "standardSolutionLanguage"
    );
    private static final List<String> SPEC_SAMPLE_STANDARD_FIELDS = List.of(
            "statement", "notes", "generationPlan", "testCases", "standardSolutionCode",
            "standardSolutionLanguage"
    );

    private final ObjectMapper objectMapper;

    public VerificationFailureClassifier(ObjectMapper objectMapper) {
        this.objectMapper = objectMapper == null ? new ObjectMapper() : objectMapper;
    }

    public RepairTask classify(String verificationReportJson) {
        Evidence evidence = evidence(verificationReportJson);
        List<String> codes = evidence.codes();
        if (containsAny(codes, "GENERATOR_PYTHON_FAILED", "GENERATOR_MISSING_INPUTS")
                && containsAny(codes, "REFERENCE_GENERATOR_FAILED", "REFERENCE_INPUTS_REQUIRED", "REFERENCE_GENERATOR_REQUIRED")) {
            return task("GENERATOR_AND_STRESS_GENERATOR_ERROR", "official hidden and reference stress generators", 0.95,
                    COMBINED_GENERATOR_FIELDS, without(CONTENT_FIELDS, COMBINED_GENERATOR_FIELDS), evidence.summary());
        }
        if (containsAny(codes, "GENERATOR_PYTHON_FAILED", "GENERATOR_MISSING_INPUTS")) {
            return task("OFFICIAL_GENERATOR_ERROR", "official hidden input generator", 0.95,
                    GENERATOR_FIELDS, without(CONTENT_FIELDS, GENERATOR_FIELDS), evidence.summary());
        }
        if (containsAny(codes, "REFERENCE_GENERATOR_FAILED", "REFERENCE_INPUTS_REQUIRED", "REFERENCE_GENERATOR_REQUIRED")) {
            return task("STRESS_GENERATOR_ERROR", "reference stress generator", 0.95,
                    STRESS_GENERATOR_FIELDS, without(CONTENT_FIELDS, STRESS_GENERATOR_FIELDS), evidence.summary());
        }
        if (containsAny(codes, "DATA_RANGE_OUTPUT_UNBOUNDED")
                && (containsAny(codes, "SANDBOX_SAMPLE_MISMATCH", "SANDBOX_SAMPLE_FAILED")
                || containsAny(codes, "COMPLEXITY_CONSTRAINT_MISMATCH",
                "COMPLEXITY_RISK_HIGH_NAIVE_LOOP", "COMPLEXITY_BENCHMARK_TLE"))) {
            return task("SPEC_SAMPLE_AND_STANDARD_REPAIR",
                    "samples, data range, and standard solution consistency", 0.9,
                    SPEC_SAMPLE_STANDARD_FIELDS, without(CONTENT_FIELDS, SPEC_SAMPLE_STANDARD_FIELDS), evidence.summary());
        }
        if (containsAny(codes, "DATA_RANGE_OUTPUT_UNBOUNDED")) {
            return task("DATA_RANGE_OR_SPEC_RISK", "statement/data range and standard solution consistency", 0.9,
                    SPEC_FIELDS, without(CONTENT_FIELDS, SPEC_FIELDS), evidence.summary());
        }
        if (containsAny(codes, "STANDARD_TLE_ON_GENERATED_CASE", "STANDARD_RUNTIME_ON_GENERATED_CASE",
                "STANDARD_OUTPUT_MATERIALIZATION_FAILED", "COMPLEXITY_CONSTRAINT_MISMATCH",
                "COMPLEXITY_RISK_HIGH_NAIVE_LOOP", "COMPLEXITY_BENCHMARK_TLE")) {
            return task("STANDARD_COMPLEXITY_OR_RUNTIME", "standard solution and complexity notes", 0.9,
                    STANDARD_FIELDS, without(CONTENT_FIELDS, STANDARD_FIELDS), evidence.summary());
        }
        if (containsAny(codes, "SANDBOX_COMPILE_FAILED", "REFERENCE_COMPILE_FAILED")) {
            List<String> allowed = List.of("standardSolutionLanguage", "standardSolutionCode",
                    "referenceSolutionLanguage", "referenceSolutionCode");
            return task("SOLUTION_COMPILE_FAILED", "solution source code", 0.9,
                    allowed, without(CONTENT_FIELDS, allowed), evidence.summary());
        }
        if (containsAny(codes, "REFERENCE_MISMATCH", "REFERENCE_RUNTIME_FAILED", "REFERENCE_REQUIRED")) {
            return task("REFERENCE_MISMATCH", "standard/reference solver consistency", 0.85,
                    REFERENCE_FIELDS, without(CONTENT_FIELDS, REFERENCE_FIELDS), evidence.summary());
        }
        if (containsAny(codes, "SANDBOX_SAMPLE_MISMATCH", "SANDBOX_SAMPLE_FAILED")) {
            return task("STATEMENT_OR_SAMPLE_MISMATCH", "samples and standard solution", 0.85,
                    SAMPLE_FIELDS, without(CONTENT_FIELDS, SAMPLE_FIELDS), evidence.summary());
        }
        if (codes.stream().anyMatch(code -> code.endsWith("_REQUIRED")
                || code.equals("PROVIDER_VALIDATION_ERROR")
                || code.equals("TEST_CASE_COUNT"))) {
            return task("SCHEMA_OR_REQUIRED_FIELD", "missing or malformed draft fields", 0.75,
                    CONTENT_FIELDS, List.of(), evidence.summary());
        }
        return task("UNKNOWN_REQUIRES_MANUAL_REVIEW", "manual review", 0.2,
                List.of(), CONTENT_FIELDS, evidence.summary());
    }

    private Evidence evidence(String json) {
        if (json == null || json.isBlank()) {
            return new Evidence(List.of(), List.of("verification report is empty"));
        }
        List<String> codes = new ArrayList<>();
        List<String> summary = new ArrayList<>();
        try {
            collect(objectMapper.readTree(json), codes, summary);
        } catch (Exception ex) {
            summary.add("verification report JSON parse failed: " + ex.getClass().getSimpleName());
        }
        return new Evidence(distinct(codes), distinct(summary).stream().limit(8).toList());
    }

    private void collect(JsonNode node, List<String> codes, List<String> summary) {
        if (node == null || node.isNull()) {
            return;
        }
        if (node.isObject()) {
            JsonNode code = node.get("code");
            JsonNode field = node.get("field");
            JsonNode message = node.get("message");
            if (code != null && code.isTextual()) {
                String normalized = code.asText("").trim().toUpperCase(Locale.ROOT);
                if (!normalized.isBlank()) {
                    codes.add(normalized);
                    summary.add(normalized + (field != null && field.isTextual() ? "@" + field.asText() : "")
                            + (message != null && message.isTextual() ? ": " + trim(message.asText(), 180) : ""));
                }
            }
            Iterator<Map.Entry<String, JsonNode>> fields = node.properties().iterator();
            while (fields.hasNext()) {
                collect(fields.next().getValue(), codes, summary);
            }
            return;
        }
        if (node.isArray()) {
            node.forEach(child -> collect(child, codes, summary));
        }
    }

    private RepairTask task(String category, String scope, double confidence, List<String> allowed,
                            List<String> forbidden, List<String> evidence) {
        return new RepairTask(category, scope, confidence, allowed, forbidden, evidence);
    }

    private boolean containsAny(List<String> codes, String... expected) {
        for (String value : expected) {
            if (codes.contains(value)) {
                return true;
            }
        }
        return false;
    }

    private List<String> without(List<String> all, List<String> allowed) {
        return all.stream().filter(field -> !allowed.contains(field)).toList();
    }

    private List<String> distinct(List<String> values) {
        return values.stream()
                .filter(value -> value != null && !value.isBlank())
                .distinct()
                .toList();
    }

    private String trim(String value, int maxLength) {
        if (value == null || value.length() <= maxLength) {
            return value;
        }
        return value.substring(0, maxLength) + "...";
    }

    private record Evidence(List<String> codes, List<String> summary) {
    }
}
