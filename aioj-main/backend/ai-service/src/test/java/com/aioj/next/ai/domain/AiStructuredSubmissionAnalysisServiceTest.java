package com.aioj.next.ai.domain;

import com.aioj.next.ai.config.AiProperties;
import com.aioj.next.contract.ai.AiChatRequest;
import com.aioj.next.contract.ai.AiProblemContextResponse;
import com.aioj.next.contract.ai.AiSubmissionCaseContext;
import com.aioj.next.contract.ai.AiSubmissionContextResponse;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.time.Instant;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyDouble;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class AiStructuredSubmissionAnalysisServiceTest {
    private AiModelConfigResolver configResolver;
    private AiModelCompletionClient completionClient;
    private AiQuotaService quotaService;
    private AiStructuredSubmissionAnalysisService service;

    @BeforeEach
    void setUp() {
        configResolver = mock(AiModelConfigResolver.class);
        completionClient = mock(AiModelCompletionClient.class);
        quotaService = mock(AiQuotaService.class);
        service = new AiStructuredSubmissionAnalysisService(
                new ObjectMapper(),
                configResolver,
                completionClient,
                quotaService,
                new AiCapacityService(new AiProperties())
        );
    }

    @Test
    void modelJsonWrongAnswerProducesStructuredTagsAndSafePrompt() {
        AiModelEffectiveConfig config = config(true, true);
        when(configResolver.effectiveConfig(AiModelScope.MEMORY_EXTRACTION)).thenReturn(config);
        when(completionClient.complete(any(), anyList(), anyDouble(), anyInt(), eq(true)))
                .thenReturn(new AiModelCompletionClient.CompletionResult("""
                        {
                          "summary":"二分答案 check 单调性判断错误。",
                          "rootCauseTags":["wrong_answer"],
                          "algorithmTags":["binary_search"],
                          "bugPatternTags":["monotonicity","boundary"],
                          "complexityTags":[],
                          "nextSteps":["重新证明 check 的单调性"],
                          "evidenceItems":["WA on sample 2"],
                          "confidence":0.82,
                          "profileKey":"wrong_answer_binary_search",
                          "profileLabel":"提交分析候选弱点：二分答案",
                          "profileEligible":true,
                          "masteryEvidence":false
                        }
                        """, "mock", "mock-16k", 31, 17));

        AiStructuredSubmissionAnalysisService.AnalysisResult result = service.analyze(
                7L,
                request("我提交了，答案错误。```cpp\nint main(){return 0;}\n```\npassword: leak"),
                completion("""
                        先看 check 单调性。
                        stdout:
                        hidden raw output
                        sk-proj-secret
                        """),
                context("WRONG_ANSWER")
        );

        assertThat(result.profileEligible()).isTrue();
        assertThat(result.masteryEvidence()).isFalse();
        assertThat(result.rootCauseTags()).containsExactly("wrong_answer");
        assertThat(result.algorithmTags()).containsExactly("binary_search");
        assertThat(result.bugPatternTags()).contains("monotonicity", "boundary");
        assertThat(result.profileKey()).isEqualTo("wrong_answer_binary_search");

        ArgumentCaptor<List<Map<String, String>>> messages = ArgumentCaptor.forClass(List.class);
        verify(completionClient).complete(eq(config), messages.capture(), anyDouble(), anyInt(), eq(true));
        String prompt = messages.getValue().get(1).get("content");
        assertThat(prompt)
                .contains("codeHash=sha256-submission")
                .contains("submit-ready code omitted")
                .contains("raw output omitted")
                .contains("secret-like text omitted")
                .doesNotContain("int main")
                .doesNotContain("hidden raw output")
                .doesNotContain("sk-proj-secret")
                .doesNotContain("source should never be sent")
                .doesNotContain("stdout secret should never be sent");
    }

    @Test
    void fallbackTimeLimitProducesComplexityCandidate() {
        when(configResolver.effectiveConfig(AiModelScope.MEMORY_EXTRACTION)).thenReturn(config(true, false));

        AiStructuredSubmissionAnalysisService.AnalysisResult result = service.analyze(
                7L,
                request("这次超时了，复杂度可能太高。"),
                completion("需要按最大数据范围重新估算复杂度。"),
                context("TIME_LIMIT_EXCEEDED")
        );

        assertThat(result.modelGenerated()).isFalse();
        assertThat(result.profileEligible()).isTrue();
        assertThat(result.complexityTags()).contains("time_complexity");
        assertThat(result.profileKey()).contains("complexity");
        verify(completionClient, never()).complete(any(), anyList(), anyDouble(), anyInt(), eq(true));
    }

    @Test
    void acceptedJsonCanProduceMasteryButNotWeaknessProfile() {
        AiModelEffectiveConfig config = config(true, true);
        when(configResolver.effectiveConfig(AiModelScope.MEMORY_EXTRACTION)).thenReturn(config);
        when(completionClient.complete(any(), anyList(), anyDouble(), anyInt(), eq(true)))
                .thenReturn(new AiModelCompletionClient.CompletionResult("""
                        {
                          "summary":"学生解释了 check 单调性和边界。",
                          "rootCauseTags":["accepted"],
                          "algorithmTags":["binary_search"],
                          "bugPatternTags":[],
                          "complexityTags":[],
                          "nextSteps":["复用 check 不变量"],
                          "evidenceItems":["解释了通过原因"],
                          "confidence":0.91,
                          "profileKey":"accepted_binary_search",
                          "profileLabel":"二分答案掌握证据",
                          "profileEligible":true,
                          "masteryEvidence":true
                        }
                        """, "mock", "mock-16k", 22, 13));

        AiStructuredSubmissionAnalysisService.AnalysisResult result = service.analyze(
                7L,
                request("这次已经通过，我能解释 check 单调性。"),
                completion("通过原因是 check 单调，并且边界更新正确。"),
                context("ACCEPTED")
        );

        assertThat(result.masteryEvidence()).isTrue();
        assertThat(result.profileEligible()).isFalse();
        assertThat(result.profileKey()).isEqualTo("accepted_binary_search");
    }

    @Test
    void compileErrorFallbackDoesNotCreateAlgorithmWeakness() {
        when(configResolver.effectiveConfig(AiModelScope.MEMORY_EXTRACTION)).thenReturn(config(true, false));

        AiStructuredSubmissionAnalysisService.AnalysisResult result = service.analyze(
                7L,
                request("编译错误了。"),
                completion("先看编译器提示。"),
                context("COMPILE_ERROR")
        );

        assertThat(result.profileEligible()).isFalse();
        assertThat(result.masteryEvidence()).isFalse();
        assertThat(result.rootCauseTags()).contains("compile_error");
    }

    @Test
    void malformedJsonFallsBackWithoutLeakingUnsafeText() {
        when(configResolver.effectiveConfig(AiModelScope.MEMORY_EXTRACTION)).thenReturn(config(true, true));
        when(completionClient.complete(any(), anyList(), anyDouble(), anyInt(), eq(true)))
                .thenReturn(new AiModelCompletionClient.CompletionResult("not json", "mock", "mock-16k", 11, 3));

        AiStructuredSubmissionAnalysisService.AnalysisResult result = service.analyze(
                7L,
                request("我提交了，答案错误。"),
                completion("```cpp\nint main(){return 0;}\n```\ntoken: leak"),
                context("WRONG_ANSWER")
        );

        assertThat(result.modelGenerated()).isFalse();
        assertThat(result.summary()).doesNotContain("int main").doesNotContain("token: leak");
        verify(quotaService).record(eq(7L), eq("mock"), eq("mock-16k"), eq(11L), eq(3L), eq(true), any());
    }

    private AiModelEffectiveConfig config(boolean enabled, boolean hasKey) {
        return new AiModelEffectiveConfig(
                AiModelScope.MEMORY_EXTRACTION,
                enabled,
                false,
                "test",
                "mock",
                "http://mock.test/v1/chat/completions",
                hasKey ? "sk-test" : "",
                hasKey ? "sk-***" : "",
                "env",
                "OPENAI_API_KEY",
                "mock-16k",
                true,
                false,
                null,
                null,
                null,
                null,
                null,
                null
        );
    }

    private AiChatRequest request(String message) {
        return new AiChatRequest(
                "conversation-1",
                99L,
                message,
                "assist",
                null,
                null,
                null,
                "client-1",
                null,
                null,
                new AiChatRequest.SubmissionContext(123L, "DEBUG", true, null)
        );
    }

    private AiCompletion completion(String content) {
        return new AiCompletion(content, "mock", "mock-model", 10, 20);
    }

    private AiSubmissionContextResponse context(String status) {
        return new AiSubmissionContextResponse(
                123L,
                7L,
                99L,
                null,
                null,
                null,
                "PRACTICE",
                false,
                "cpp",
                status,
                "Wrong answer on sample 2",
                "stdout secret should never be sent",
                "stderr secret should never be sent",
                0,
                12,
                2048,
                0.0,
                100.0,
                true,
                "source should never be sent: int main(){return 0;}",
                "sha256-submission",
                List.of(new AiSubmissionCaseContext(2, "sample 2", status, 0.0, 1.0, 12, 2048, "check(mid) failed")),
                new AiProblemContextResponse(
                        99L,
                        null,
                        null,
                        null,
                        "Binary Search Practice",
                        "MEDIUM",
                        "Full statement not needed here",
                        "Find the answer with binary search.",
                        List.of("binary_search"),
                        List.of("n <= 100000"),
                        List.of(),
                        1000,
                        262144,
                        "PROBLEM",
                        Instant.now()
                ),
                Instant.now(),
                Instant.now(),
                null
        );
    }
}
