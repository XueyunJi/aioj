package com.aioj.next.ai.domain;

import com.aioj.next.ai.config.AiProperties;
import com.aioj.next.ai.domain.AiModelCompletionClient.CompletionResult;
import com.aioj.next.ai.domain.clarification.ClarificationSchemaRepairer;
import com.aioj.next.ai.domain.problem.ProblemDraftRepairPatch;
import com.aioj.next.ai.domain.problem.ProblemDraftStressGeneratorResult;
import com.aioj.next.common.error.DomainException;
import com.aioj.next.contract.ai.ProblemDraftRequest;
import com.aioj.next.contract.ai.ProblemDraftResponse;
import com.aioj.next.contract.problem.TestCaseDto;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.util.ArrayDeque;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyDouble;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class OpenAiCompatibleProviderProblemDraftParseTest {
    private final ObjectMapper objectMapper = new ObjectMapper();

    @Test
    void generateProblemDraftParsesNormalJsonAndCombinesStageUsage() {
        ProviderHarness harness = providerReturning(
                planningResult("implementation", List.of("math"), 1000, "plan-model", 10, 1),
                result("{\"testData\":true}", "test-model", 20, 2),
                result("{\"solution\":true}", "solution-model", 30, 3),
                result("""
                        {
                          "title": "A+B Problem",
                          "difficulty": "EASY",
                          "statement": "题目描述 给定两个整数，输出它们的和。输入描述 第一行两个整数。输出描述 输出一个整数。",
                          "notes": "样例说明：1+2=3。",
                          "standardSolutionLanguage": "cpp",
                          "standardSolutionCode": "int main(){return 0;}",
                          "referenceSolutionLanguage": "cpp",
                          "referenceSolutionCode": "int main(){return 0;}",
                          "testcaseGeneratorPython": "from pathlib import Path; Path('testcases').mkdir(exist_ok=True)",
                          "generationPlan": "覆盖基础样例和边界。",
                          "tags": ["implementation", "math"],
                          "testCases": [
                            {"input": "1 2\\n", "expectedOutput": "3\\n", "sample": true},
                            {"input": "2 3\\n", "expectedOutput": "5\\n", "sample": true},
                            {"input": "10 20\\n", "expectedOutput": "30\\n", "sample": true}
                          ],
                          "timeLimitMillis": 2000,
                          "memoryLimitKb": 524288
                        }
                        """, "final-model", 40, 4)
        );

        ProblemDraftResponse response = harness.provider().generateProblemDraft(123L, request());

        assertThat(response.id()).isEqualTo(123L);
        assertThat(response.status()).isEqualTo("PENDING_REVIEW");
        assertThat(response.title()).isEqualTo("A+B Problem");
        assertThat(response.difficulty()).isEqualTo("EASY");
        assertThat(response.standardSolutionLanguage()).isEqualTo("cpp");
        assertThat(response.referenceSolutionLanguage()).isEqualTo("cpp");
        assertThat(response.referenceSolutionCode()).isEqualTo("int main(){return 0;}");
        assertThat(response.tags()).containsExactly("implementation", "math");
        assertThat(response.testCases()).hasSize(3);
        assertThat(response.testCases().get(0).input()).isEqualTo("1 2\n");
        assertThat(response.testCases().get(0).expectedOutput()).isEqualTo("3\n");
        assertThat(response.timeLimitMillis()).isEqualTo(2000);
        assertThat(response.memoryLimitKb()).isEqualTo(524288);
        assertThat(response.model()).isEqualTo("final-model");
        assertThat(response.promptTokens()).isEqualTo(100);
        assertThat(response.completionTokens()).isEqualTo(10);
        verify(harness.completionClient(), times(3)).complete(
                any(AiModelEffectiveConfig.class), anyList(), anyDouble(), anyInt(), eq(true));
        ArgumentCaptor<Integer> schemaMaxTokensCaptor = ArgumentCaptor.forClass(Integer.class);
        verify(harness.completionClient(), times(1)).completeWithJsonSchema(
                any(AiModelEffectiveConfig.class), anyList(), anyDouble(), schemaMaxTokensCaptor.capture(), any(JsonNode.class));
        assertThat(schemaMaxTokensCaptor.getValue()).isEqualTo(OpenAiCompatibleProvider.PROBLEM_DRAFT_FINAL_MAX_TOKENS);
    }

    @Test
    void generateProblemDraftParsesJsonFromMarkdownFence() {
        ProviderHarness harness = providerReturning(
                planningResult("implementation", List.of("array"), 1000, "plan-model", 1, 1),
                result("{}", "test-model", 1, 1),
                result("{}", "solution-model", 1, 1),
                result("""
                        ```json
                        {
                          "title": "Fence Wrapped",
                          "difficulty": "MEDIUM",
                          "statement": "题目描述 给定数组。输入描述 第一行 n。输出描述 输出答案。",
                          "notes": "样例说明。",
                          "standardSolutionLanguage": "python",
                          "standardSolutionCode": "print(input())",
                          "testcaseGeneratorPython": "from pathlib import Path; Path('testcases').mkdir(exist_ok=True)",
                          "generationPlan": "计划",
                          "tags": ["array"],
                          "testCases": [
                            {"input": "1\\n", "expectedOutput": "1\\n", "sample": true},
                            {"input": "2\\n", "expectedOutput": "2\\n", "sample": true},
                            {"input": "3\\n", "expectedOutput": "3\\n", "sample": true}
                          ],
                          "timeLimitMillis": 1000,
                          "memoryLimitKb": 262144
                        }
                        ```
                        """, "final-model", 1, 1)
        );

        ProblemDraftResponse response = harness.provider().generateProblemDraft(124L, request());

        assertThat(response.title()).isEqualTo("Fence Wrapped");
        assertThat(response.standardSolutionLanguage()).isEqualTo("python");
        assertThat(response.testCases()).hasSize(3);
    }

    @Test
    void generateProblemDraftRejectsMissingFieldsWithSchemaErrors() {
        ProviderHarness harness = providerReturning(
                planningResult("implementation", List.of("array"), 1000, "plan-model", 1, 1),
                result("{}", "test-model", 1, 1),
                result("{}", "solution-model", 1, 1),
                result("""
                        {
                          "testCases": [
                            {"input": "1\\n", "expectedOutput": "1\\n", "sample": true}
                          ]
                        }
                        """, "final-model", 1, 1),
                result("""
                        {
                          "testCases": [
                            {"input": "1\\n", "expectedOutput": "1\\n", "sample": true}
                          ]
                        }
                        """, "final-model", 1, 1)
        );

        ProblemDraftResponse response = harness.provider().generateProblemDraft(125L, request());

        assertThat(response.validationStatus()).isEqualTo("INVALID");
        assertThat(response.validationErrors()).contains("schema: title is required");
        assertThat(response.validationErrors()).contains("schema: difficulty is required");
        assertThat(response.validationErrors()).contains("schema: tags is required");
        verify(harness.completionClient(), times(2)).completeWithJsonSchema(
                any(AiModelEffectiveConfig.class), anyList(), anyDouble(), anyInt(), any(JsonNode.class));
    }

    @Test
    void generateProblemDraftIncludesExtendedRequestFieldsInPlanningPrompt() {
        ProviderHarness harness = providerReturning(
                planningResult("sliding window", List.of("array", "two pointers"), 1500, "plan-model", 1, 1),
                result("{}", "test-model", 1, 1),
                result("{}", "solution-model", 1, 1),
                result("""
                        {
                          "title": "Extended Request",
                          "difficulty": "MEDIUM",
                          "statement": "题目描述 给定数组。输入描述 第一行 n。输出描述 输出答案。",
                          "notes": "样例说明。",
                          "standardSolutionLanguage": "cpp",
                          "standardSolutionCode": "int main(){return 0;}",
                          "testcaseGeneratorPython": "from pathlib import Path; Path('testcases').mkdir(exist_ok=True)",
                          "generationPlan": "计划",
                          "tags": ["array"],
                          "testCases": [
                            {"input": "1\\n", "expectedOutput": "1\\n", "sample": true},
                            {"input": "2\\n", "expectedOutput": "2\\n", "sample": true},
                            {"input": "3\\n", "expectedOutput": "3\\n", "sample": true}
                          ],
                          "timeLimitMillis": 1000,
                          "memoryLimitKb": 262144
                        }
                        """, "final-model", 1, 1)
        );
        ProblemDraftRequest request = new ProblemDraftRequest(
                "数组",
                "MEDIUM",
                1500,
                "训练滑动窗口",
                "sliding window",
                List.of("array", "two pointers"),
                "校园打卡",
                "第一行 n",
                "1 <= n <= 200000",
                "避免模板题",
                "cpp",
                "标题突出算法",
                null,
                "覆盖边界和随机",
                12,
                null,
                null,
                true,
                true
        );

        harness.provider().generateProblemDraft(126L, request);

        @SuppressWarnings("unchecked")
        ArgumentCaptor<List<Map<String, String>>> messagesCaptor = ArgumentCaptor.forClass(List.class);
        verify(harness.completionClient(), times(3)).complete(
                any(AiModelEffectiveConfig.class), messagesCaptor.capture(), anyDouble(), anyInt(), eq(true));
        String planningPrompt = messagesCaptor.getAllValues().get(0).get(1).get("content");
        assertThat(planningPrompt).contains("<REQUIREMENT_IR_JSON>");
        assertThat(planningPrompt).contains("expectedComplexity");
        assertThat(planningPrompt).contains("Codeforces rating：1500");
        assertThat(planningPrompt).contains("考察算法：sliding window");
        assertThat(planningPrompt).contains("展示/归档标签（仅作为附加分类参考，不作为题目核心约束）：array, two pointers");
        assertThat(planningPrompt).contains("题目设计优先依据：题目主题、考察算法、输入输出要求、数据范围/边界、质量要求");
        assertThat(planningPrompt).contains("目标隐藏测试点数：12");
        assertThat(planningPrompt).contains("请求自动修复：true");
        assertThat(planningPrompt).contains("请求参考对拍：true");
        String testcasePrompt = messagesCaptor.getAllValues().get(1).get(1).get("content");
        assertThat(testcasePrompt).contains("教师可本地运行打包");
        assertThat(testcasePrompt).contains("testcases/001.in");
        assertThat(testcasePrompt).contains("testcases/001.out");
        assertThat(testcasePrompt).contains("STD_CPP");
        assertThat(testcasePrompt).contains("compile_std");
        assertThat(testcasePrompt).contains("run_std");
        assertThat(testcasePrompt).contains("write_case");
        assertThat(testcasePrompt).contains("内部验证会以生成出的 .in 为准重新物化 .out");
        assertThat(testcasePrompt).doesNotContain("stress_small_001.in");
        String solutionPrompt = messagesCaptor.getAllValues().get(2).get(1).get("content");
        assertThat(solutionPrompt).contains("referenceSolutionLanguage");
        assertThat(solutionPrompt).contains("referenceSolutionCode");
        assertThat(solutionPrompt).contains("小数据可靠暴力");
    }

    @Test
    void generateProblemDraftRetriesPlanningWhenGateFailsThenContinues() {
        ProviderHarness harness = providerReturning(
                planningResult("greedy", List.of("sorting"), 1800, "plan-model", 7, 1),
                planningResult("线段树", List.of("排序"), 1800, "plan-model", 11, 2),
                result("{\"testData\":true}", "test-model", 13, 3),
                result("{\"solution\":true}", "solution-model", 17, 4),
                finalDraftResult("Segment Tree Sorting", "HARD", "final-model", 19, 5)
        );

        ProblemDraftResponse response = harness.provider().generateProblemDraft(127L, algorithmRequest("线段树,排序", 1800));

        assertThat(response.title()).isEqualTo("Segment Tree Sorting");
        assertThat(response.promptTokens()).isEqualTo(67);
        assertThat(response.completionTokens()).isEqualTo(15);
        verify(harness.completionClient(), times(4)).complete(
                any(AiModelEffectiveConfig.class), anyList(), anyDouble(), anyInt(), eq(true));
        verify(harness.completionClient(), times(1)).completeWithJsonSchema(
                any(AiModelEffectiveConfig.class), anyList(), anyDouble(), anyInt(), any(JsonNode.class));
    }

    @Test
    void generateProblemDraftFailsWhenPlanningGateFailsTwiceAndSkipsLaterStages() {
        ProviderHarness harness = providerReturning(
                planningResult("greedy", List.of("sorting"), 1300, "plan-model", 7, 1),
                planningResult("brute force", List.of("array"), 1300, "plan-model", 11, 2)
        );

        assertThatThrownBy(() -> harness.provider().generateProblemDraft(128L, algorithmRequest("线段树,排序", 1800)))
                .isInstanceOf(DomainException.class)
                .hasMessageContaining("Problem design plan gate failed")
                .hasMessageContaining("线段树");

        verify(harness.completionClient(), times(2)).complete(
                any(AiModelEffectiveConfig.class), anyList(), anyDouble(), anyInt(), eq(true));
        verify(harness.completionClient(), times(0)).completeWithJsonSchema(
                any(AiModelEffectiveConfig.class), anyList(), anyDouble(), anyInt(), any(JsonNode.class));
    }

    @Test
    void repairProblemDraftParsesPatchAndUsage() {
        ProviderHarness harness = providerReturning(result("""
                {
                  "changedFields": ["standardSolutionCode", "referenceSolutionCode", "testCases"],
                  "standardSolutionCode": "int main(){return 0;}",
                  "referenceSolutionLanguage": "cpp",
                  "referenceSolutionCode": "int main(){return 0;}",
                  "testCases": [
                    {"input": "1 2\\n", "expectedOutput": "3\\n", "sample": true},
                    {"input": "2 3\\n", "expectedOutput": "5\\n", "sample": true},
                    {"input": "10 20\\n", "expectedOutput": "30\\n", "sample": true}
                  ],
                  "repairReason": "修正样例输出"
                }
                """, "repair-model", 11, 12));

        ProblemDraftRepairPatch patch = harness.provider().repairProblemDraft(
                200L,
                repairDraft(),
                "{\"status\":\"FAILED\"}",
                "{\"topic\":\"数组\"}",
                1,
                2
        );

        assertThat(patch.changedFields()).containsExactly("standardSolutionCode", "referenceSolutionCode", "testCases");
        assertThat(patch.standardSolutionCode()).isEqualTo("int main(){return 0;}");
        assertThat(patch.referenceSolutionLanguage()).isEqualTo("cpp");
        assertThat(patch.referenceSolutionCode()).isEqualTo("int main(){return 0;}");
        assertThat(patch.testCases()).hasSize(3);
        assertThat(patch.testCases().get(1).expectedOutput()).isEqualTo("5\n");
        assertThat(patch.repairReason()).isEqualTo("修正样例输出");
        assertThat(patch.model()).isEqualTo("repair-model");
        assertThat(patch.promptTokens()).isEqualTo(11);
        assertThat(patch.completionTokens()).isEqualTo(12);

        @SuppressWarnings("unchecked")
        ArgumentCaptor<List<Map<String, String>>> messagesCaptor = ArgumentCaptor.forClass(List.class);
        verify(harness.completionClient()).complete(
                any(AiModelEffectiveConfig.class), messagesCaptor.capture(), anyDouble(), anyInt(), eq(true));
        String repairSystemPrompt = messagesCaptor.getValue().get(0).get("content");
        assertThat(repairSystemPrompt).contains("PROVIDER_VALIDATION_ERROR");
        assertThat(repairSystemPrompt).contains("GENERATOR_MISSING_INPUTS");
        assertThat(repairSystemPrompt).contains("GENERATOR_PYTHON_FAILED");
        assertThat(repairSystemPrompt).contains("testcases/001.in");
        assertThat(repairSystemPrompt).contains("001.in/001.out");
        assertThat(repairSystemPrompt).contains("STD_CPP");
        assertThat(repairSystemPrompt).contains("std_exe = compile_std()");
        assertThat(repairSystemPrompt).contains("不能假设平台预先注入 std_exe");
        assertThat(repairSystemPrompt).contains("无交互 stdin 中必须使用默认规模不阻塞");
        assertThat(repairSystemPrompt).contains("Memory Limit Exceeded");
        assertThat(repairSystemPrompt).contains("set(range(...))");
        assertThat(repairSystemPrompt).contains("list(absent)");
        assertThat(repairSystemPrompt).contains("STANDARD_TLE_ON_GENERATED_CASE");
        assertThat(repairSystemPrompt).contains("STANDARD_OUTPUT_MATERIALIZATION_FAILED");
        assertThat(repairSystemPrompt).contains("不要把它误修为 testcaseGeneratorPython 问题");
        assertThat(repairSystemPrompt).contains("REFERENCE_MISMATCH");
        assertThat(repairSystemPrompt).contains("referenceSolutionCode");
        assertThat(repairSystemPrompt).contains("stressTestcaseGeneratorPython");
        assertThat(repairSystemPrompt).contains("同时包含样例不一致、复杂度风险和数据范围/规格风险");
        String repairPrompt = messagesCaptor.getValue().get(1).get("content");
        assertThat(repairPrompt).contains("自动修复轮次：1/2");
        assertThat(repairPrompt).contains("COMPLEXITY_CONSTRAINT_MISMATCH");
        assertThat(repairPrompt).contains("COMPLEXITY_BENCHMARK_TLE");
        assertThat(repairPrompt).contains("不要把责任转嫁给 testcaseGeneratorPython");
        assertThat(repairPrompt).contains("SANDBOX_SAMPLE_MISMATCH 与 DATA_RANGE_OUTPUT_UNBOUNDED");
        assertThat(repairPrompt).contains("Memory Limit Exceeded");
        assertThat(repairPrompt).contains("巨大 range/set/list");
        assertThat(repairPrompt).contains("<VERIFICATION_REPORT_JSON>{\"status\":\"FAILED\"}</VERIFICATION_REPORT_JSON>");
    }

    @Test
    void generateProblemDraftStressGeneratorParsesJsonAndBuildsDedicatedPrompt() {
        ProviderHarness harness = providerReturning(result("""
                {
                  "stressTestcaseGeneratorPython": "from pathlib import Path\\nPath('testcases').mkdir(exist_ok=True)\\n(Path('testcases') / 'stress_small_001.in').write_text('1 2\\\\n')\\n(Path('testcases') / 'stress_small_001.out').write_text('3\\\\n')"
                }
                """, "stress-model", 13, 14));

        ProblemDraftStressGeneratorResult result = harness.provider().generateProblemDraftStressGenerator(
                203L,
                request(),
                repairDraft()
        );

        assertThat(result.stressTestcaseGeneratorPython()).contains("stress_small_001.in");
        assertThat(result.model()).isEqualTo("stress-model");
        assertThat(result.promptTokens()).isEqualTo(13);
        assertThat(result.completionTokens()).isEqualTo(14);

        @SuppressWarnings("unchecked")
        ArgumentCaptor<List<Map<String, String>>> messagesCaptor = ArgumentCaptor.forClass(List.class);
        verify(harness.completionClient()).complete(
                any(AiModelEffectiveConfig.class), messagesCaptor.capture(), anyDouble(), anyInt(), eq(true));
        String prompt = messagesCaptor.getValue().get(1).get("content");
        assertThat(prompt).contains("当前题目草稿已经生成完毕");
        assertThat(prompt).contains("无交互 stdin");
        assertThat(prompt).contains("testcases/stress_small_001.in");
        assertThat(prompt).contains("testcases/stress_small_001.out");
        assertThat(prompt).contains("STD_CPP");
        assertThat(prompt).contains("小规模随机对拍");
    }

    @Test
    void repairProblemDraftParsesPatchFromMarkdownFence() {
        ProviderHarness harness = providerReturning(result("""
                ```json
                {
                  "changedFields": ["testcaseGeneratorPython"],
                  "testcaseGeneratorPython": "from pathlib import Path\\nPath('testcases').mkdir(exist_ok=True)",
                  "repairReason": "补齐测试生成脚本"
                }
                ```
                """, "repair-model", 3, 4));

        ProblemDraftRepairPatch patch = harness.provider().repairProblemDraft(
                201L,
                repairDraft(),
                "{\"status\":\"FAILED\"}",
                "{\"topic\":\"数组\"}",
                1,
                2
        );

        assertThat(patch.changedFields()).containsExactly("testcaseGeneratorPython");
        assertThat(patch.testcaseGeneratorPython()).contains("Path('testcases')");
        assertThat(patch.promptTokens()).isEqualTo(3);
        assertThat(patch.completionTokens()).isEqualTo(4);
    }

    @Test
    void repairProblemDraftReturnsEmptyPatchWhenProviderJsonIsInvalid() {
        ProviderHarness harness = providerReturning(result("not json", "repair-model", 5, 6));

        ProblemDraftRepairPatch patch = harness.provider().repairProblemDraft(
                202L,
                repairDraft(),
                "{\"status\":\"FAILED\"}",
                "{\"topic\":\"数组\"}",
                1,
                2
        );

        assertThat(patch.changedFields()).isEmpty();
        assertThat(patch.repairReason()).contains("Provider returned invalid repair patch");
        assertThat(patch.promptTokens()).isEqualTo(5);
        assertThat(patch.completionTokens()).isEqualTo(6);
    }

    private ProviderHarness providerReturning(CompletionResult... results) {
        AiModelCompletionClient completionClient = mock(AiModelCompletionClient.class);
        ArrayDeque<CompletionResult> queue = new ArrayDeque<>(List.of(results));
        when(completionClient.complete(any(AiModelEffectiveConfig.class), anyList(), anyDouble(), anyInt(), eq(true)))
                .thenAnswer(invocation -> queue.isEmpty() ? results[results.length - 1] : queue.removeFirst());
        when(completionClient.completeWithJsonSchema(any(AiModelEffectiveConfig.class), anyList(), anyDouble(), anyInt(), any(JsonNode.class)))
                .thenAnswer(invocation -> queue.isEmpty() ? results[results.length - 1] : queue.removeFirst());
        OpenAiCompatibleProvider provider = new OpenAiCompatibleProvider(
                new AiProperties(),
                objectMapper,
                new ClarificationSchemaRepairer(),
                scope -> effectiveConfig(),
                completionClient
        );
        return new ProviderHarness(provider, completionClient);
    }

    private AiModelEffectiveConfig effectiveConfig() {
        return new AiModelEffectiveConfig(
                AiModelScope.PROBLEM_DRAFT,
                true,
                false,
                "test",
                "deepseek",
                "https://api.deepseek.com/chat/completions",
                "test-key",
                "",
                "test",
                "DEEPSEEK_API_KEY",
                "deepseek-v4-pro",
                true,
                false,
                "high",
                null,
                null,
                null,
                null,
                null
        );
    }

    private ProblemDraftResponse repairDraft() {
        return new ProblemDraftResponse(
                200L,
                "PENDING_REVIEW",
                "Repair target",
                "EASY",
                "题目描述 给定两个整数。输入描述 第一行两个整数。输出描述 输出一个整数。",
                "notes",
                "cpp",
                "old code",
                "old generator",
                "old plan",
                List.of("array"),
                "VALID",
                List.of(),
                List.of(
                        new TestCaseDto("1 2\n", "3\n", true),
                        new TestCaseDto("2 3\n", "4\n", true),
                        new TestCaseDto("10 20\n", "30\n", true)
                ),
                1000,
                262144,
                null,
                "base-model",
                10,
                20,
                null,
                null,
                null,
                null,
                null,
                null
        );
    }

    private CompletionResult result(String content, String model, long promptTokens, long completionTokens) {
        return new CompletionResult(content, "mock", model, promptTokens, completionTokens);
    }

    private CompletionResult planningResult(String coreAlgorithm, List<String> secondaryAlgorithms, int estimatedCfRating,
                                            String model, long promptTokens, long completionTokens) {
        String secondary = secondaryAlgorithms == null || secondaryAlgorithms.isEmpty()
                ? ""
                : "\"" + String.join("\", \"", secondaryAlgorithms) + "\"";
        String content = """
                {
                  "requirementIR": {
                    "assumptions": [],
                    "riskPoints": []
                  },
                  "problemDesignPlan": {
                    "title": "Planned problem",
                    "difficulty": "HARD",
                    "coreAlgorithm": "%s",
                    "secondaryAlgorithms": [%s],
                    "coreObservation": "%s with %s",
                    "constraints": "1 <= n <= 200000",
                    "expectedTimeComplexity": "O(n log n)",
                    "expectedMemoryComplexity": "O(n)",
                    "boundaryCases": ["minimum input", "maximum input"],
                    "commonWrongApproaches": ["plain quadratic brute force"],
                    "proofObligations": ["prove invariant"],
                    "estimatedCfRating": %d,
                    "tags": ["algorithm"],
                    "timeLimitMillis": 2000,
                    "memoryLimitKb": 262144
                  },
                  "fitCheck": {
                    "matched": true,
                    "algorithmMatched": true,
                    "ratingMatched": true,
                    "constraintsMatched": true,
                    "violations": [],
                    "suggestedFixes": []
                  }
                }
                """.formatted(coreAlgorithm, secondary, coreAlgorithm, secondaryAlgorithms, estimatedCfRating);
        return result(content, model, promptTokens, completionTokens);
    }

    private CompletionResult finalDraftResult(String title, String difficulty, String model,
                                              long promptTokens, long completionTokens) {
        return result("""
                {
                  "title": "%s",
                  "difficulty": "%s",
                  "statement": "题目描述 给定数组，输出答案。输入描述 第一行 n。输出描述 输出一个整数。",
                  "notes": "样例说明。",
                  "standardSolutionLanguage": "cpp",
                  "standardSolutionCode": "int main(){return 0;}",
                  "testcaseGeneratorPython": "from pathlib import Path; Path('testcases').mkdir(exist_ok=True)",
                  "generationPlan": "核心算法、复杂度、边界和 fitCheck 均已记录。",
                  "tags": ["algorithm"],
                  "testCases": [
                    {"input": "1\\n", "expectedOutput": "1\\n", "sample": true},
                    {"input": "2\\n", "expectedOutput": "2\\n", "sample": true},
                    {"input": "3\\n", "expectedOutput": "3\\n", "sample": true}
                  ],
                  "timeLimitMillis": 1000,
                  "memoryLimitKb": 262144
                }
                """.formatted(title, difficulty), model, promptTokens, completionTokens);
    }

    private ProblemDraftRequest algorithmRequest(String algorithm, Integer cfRating) {
        return new ProblemDraftRequest(
                algorithm,
                "HARD",
                cfRating,
                "训练算法匹配",
                algorithm,
                null,
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

    private ProblemDraftRequest request() {
        return new ProblemDraftRequest(
                "数组",
                "EASY",
                null,
                "训练输入输出",
                "implementation",
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
    }

    private record ProviderHarness(OpenAiCompatibleProvider provider, AiModelCompletionClient completionClient) {
    }
}
