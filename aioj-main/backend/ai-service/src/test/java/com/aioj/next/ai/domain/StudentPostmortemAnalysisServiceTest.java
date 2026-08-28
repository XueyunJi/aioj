package com.aioj.next.ai.domain;

import com.aioj.next.contract.ai.AiChatRequest;
import com.aioj.next.contract.ai.StudentPostmortemAnalysisRequest;
import com.aioj.next.contract.ai.StudentPostmortemCodeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentCaptor.forClass;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class StudentPostmortemAnalysisServiceTest {

    @Test
    void successfulAnalysisKeepsDeterministicCandidatesAndRecordsUsage() {
        AiProvider provider = mock(AiProvider.class);
        AiQuotaService quotaService = mock(AiQuotaService.class);
        when(provider.chat(any(AiChatRequest.class), any(AiChatContext.class), eq(AiModelScope.REPORT_ANALYSIS)))
                .thenReturn(new AiCompletion("## 个人成绩概览\n本场需要继续复盘二分。", "mock", "mock-model", 80, 32));
        StudentPostmortemAnalysisService service = new StudentPostmortemAnalysisService(provider, quotaService,
                new AiCapacityService(new com.aioj.next.ai.config.AiProperties()), new ObjectMapper());

        var response = service.analyze(request());

        assertThat(response.success()).isTrue();
        assertThat(response.markdown()).contains("个人成绩概览");
        assertThat(response.weaknessCandidates()).isNotEmpty();
        assertThat(response.weaknessCandidates().get(0).knowledgeNode()).isEqualTo("binary_search");
        assertThat(response.practiceSuggestions()).isNotEmpty();
        verify(quotaService).record(7L, "mock", "mock-model", 80, 32, true);
    }

    @Test
    void providerFailureStillReturnsCandidatesAndPracticeSuggestions() {
        AiProvider provider = mock(AiProvider.class);
        AiQuotaService quotaService = mock(AiQuotaService.class);
        when(provider.providerName()).thenReturn("mock");
        when(provider.model()).thenReturn("mock-model");
        when(provider.chat(any(AiChatRequest.class), any(AiChatContext.class), eq(AiModelScope.REPORT_ANALYSIS)))
                .thenThrow(new IllegalStateException("upstream unavailable"));
        StudentPostmortemAnalysisService service = new StudentPostmortemAnalysisService(provider, quotaService,
                new AiCapacityService(new com.aioj.next.ai.config.AiProperties()), new ObjectMapper());

        var response = service.analyze(request());

        assertThat(response.success()).isFalse();
        assertThat(response.markdown()).isNull();
        assertThat(response.weaknessCandidates()).isNotEmpty();
        assertThat(response.practiceSuggestions()).isNotEmpty();
        assertThat(response.errorMessage()).contains("upstream unavailable");
        verify(quotaService).record(eq(7L), eq("mock"), eq("mock-model"), eq(0L), eq(0L), eq(false));
    }

    @Test
    void acmAnalysisIncludesRepresentativeCodeAndAvoidsScoreInstructions() {
        AiProvider provider = mock(AiProvider.class);
        AiQuotaService quotaService = mock(AiQuotaService.class);
        when(provider.chat(any(AiChatRequest.class), any(AiChatContext.class), eq(AiModelScope.REPORT_ANALYSIS)))
                .thenReturn(new AiCompletion("## 个人成绩概览\n已通过 A 题。", "mock", "mock-model", 60, 20));
        StudentPostmortemAnalysisService service = new StudentPostmortemAnalysisService(provider, quotaService,
                new AiCapacityService(new com.aioj.next.ai.config.AiProperties()), new ObjectMapper());

        service.analyze(acmRequest());

        var contextCaptor = forClass(AiChatContext.class);
        verify(provider).chat(any(AiChatRequest.class), contextCaptor.capture(), eq(AiModelScope.REPORT_ANALYSIS));
        String pack = contextCaptor.getValue().conversationContextPack();
        assertThat(pack).contains("ACM mode rules");
        assertThat(pack).contains("Representative ACM Code References");
        assertThat(pack).contains("int main()");
        assertThat(pack).contains("Do not mention total score");
    }

    private StudentPostmortemAnalysisRequest request() {
        return new StudentPostmortemAnalysisRequest(
                7L,
                12L,
                34L,
                56L,
                7L,
                "Spring Contest",
                "Replay Run",
                "IOI",
                """
                        {
                          "submissionCount": 3,
                          "acceptedCount": 1,
                          "totalScore": 35,
                          "maxScore": 100,
                          "weaknessSeeds": ["binary_search: A 星港间距 得分 35/100"],
                          "problems": [
                            {
                              "label": "A",
                              "title": "星港间距",
                              "bestStatus": "WRONG_ANSWER",
                              "bestScore": 35,
                              "maxScore": 100,
                              "tags": ["binary_search", "greedy"]
                            }
                          ]
                        }
                        """,
                "学生在 A 题二分答案与贪心 check 上得分不稳定。",
                List.of()
        );
    }

    private StudentPostmortemAnalysisRequest acmRequest() {
        return new StudentPostmortemAnalysisRequest(
                7L,
                12L,
                34L,
                56L,
                7L,
                "Spring Contest",
                "Replay Run",
                "ACM",
                """
                        {
                          "submissionCount": 2,
                          "acceptedCount": 1,
                          "weaknessSeeds": [],
                          "problems": [
                            {
                              "label": "A",
                              "title": "星港间距",
                              "bestStatus": "ACCEPTED",
                              "submissionCount": 2,
                              "tags": ["binary_search", "greedy"]
                            }
                          ]
                        }
                        """,
                "ACM 赛制：学生提交 2 次，通过 1 题。",
                List.of(new StudentPostmortemCodeReference(
                        101L,
                        201L,
                        "A",
                        "星港间距",
                        "cpp",
                        "ACCEPTED",
                        60000L,
                        24,
                        "int main() { return 0; }"
                ))
        );
    }
}
