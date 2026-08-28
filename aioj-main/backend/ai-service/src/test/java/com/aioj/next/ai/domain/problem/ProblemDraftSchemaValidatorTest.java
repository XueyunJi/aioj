package com.aioj.next.ai.domain.problem;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class ProblemDraftSchemaValidatorTest {
    private final ObjectMapper objectMapper = new ObjectMapper();
    private final ProblemDraftSchemaValidator validator = new ProblemDraftSchemaValidator();

    @Test
    void validateReportsMissingRequiredFields() throws Exception {
        JsonNode root = objectMapper.readTree("""
                {
                  "testCases": []
                }
                """);

        List<String> errors = validator.validate(root);

        assertThat(errors).contains("schema: title is required");
        assertThat(errors).contains("schema: difficulty is required");
        assertThat(errors).contains("schema: standardSolutionLanguage is required");
        assertThat(errors).contains("schema: tags is required");
    }

    @Test
    void validateReportsInvalidEnumsAndSampleFlag() throws Exception {
        JsonNode root = objectMapper.readTree("""
                {
                  "title": "Bad Draft",
                  "difficulty": "EXTREME",
                  "statement": "题目描述 输入描述 输出描述",
                  "notes": "样例说明",
                  "standardSolutionLanguage": "go",
                  "standardSolutionCode": "package main",
                  "testcaseGeneratorPython": "print('ok')",
                  "generationPlan": "计划",
                  "tags": ["implementation"],
                  "testCases": [
                    {"input": "1\\n", "expectedOutput": "1\\n", "sample": false},
                    {"input": "2\\n", "expectedOutput": "2\\n", "sample": true},
                    {"input": "3\\n", "expectedOutput": "3\\n", "sample": true}
                  ],
                  "timeLimitMillis": 1000,
                  "memoryLimitKb": 262144
                }
                """);

        List<String> errors = validator.validate(root);

        assertThat(errors).contains("schema: difficulty must be one of EASY, MEDIUM, HARD, CHALLENGE");
        assertThat(errors).contains("schema: standardSolutionLanguage must be one of cpp, python, java");
        assertThat(errors).contains("schema: testCases[0].sample must be true");
    }
}
