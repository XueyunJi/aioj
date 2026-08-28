package com.aioj.next.ai.domain;

import com.aioj.next.contract.ai.AiChatRequest;
import com.aioj.next.contract.ai.ContestPostmortemAnalysisRequest;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class ContestPostmortemAnalysisServiceTest {
    @Test
    void successfulAnalysisRecordsUsageAndReturnsMarkdown() {
        AiProvider provider = mock(AiProvider.class);
        AiQuotaService quotaService = mock(AiQuotaService.class);
        when(provider.chat(any(AiChatRequest.class), any(AiChatContext.class), eq(AiModelScope.REPORT_ANALYSIS)))
                .thenReturn(new AiCompletion("## 赛况总览\n整体完成度较高。", "mock", "mock-model", 42, 16));
        ContestPostmortemAnalysisService service = new ContestPostmortemAnalysisService(provider, quotaService,
                new AiCapacityService(new com.aioj.next.ai.config.AiProperties()));

        var response = service.analyze(request());

        assertThat(response.success()).isTrue();
        assertThat(response.markdown()).contains("赛况总览");
        verify(quotaService).record(7L, "mock", "mock-model", 42, 16, true);
    }

    @Test
    void providerFailureKeepsStructuredFailedResponse() {
        AiProvider provider = mock(AiProvider.class);
        AiQuotaService quotaService = mock(AiQuotaService.class);
        when(provider.providerName()).thenReturn("mock");
        when(provider.model()).thenReturn("mock-model");
        when(provider.chat(any(AiChatRequest.class), any(AiChatContext.class), eq(AiModelScope.REPORT_ANALYSIS)))
                .thenThrow(new IllegalStateException("upstream unavailable"));
        ContestPostmortemAnalysisService service = new ContestPostmortemAnalysisService(provider, quotaService,
                new AiCapacityService(new com.aioj.next.ai.config.AiProperties()));

        var response = service.analyze(request());

        assertThat(response.success()).isFalse();
        assertThat(response.errorMessage()).contains("upstream unavailable");
        verify(quotaService).record(eq(7L), eq("mock"), eq("mock-model"), eq(0L), eq(0L), eq(false));
    }

    private ContestPostmortemAnalysisRequest request() {
        return new ContestPostmortemAnalysisRequest(
                7L,
                301L,
                501L,
                "Spring Contest",
                "Final Run",
                "ACM",
                """
                        {"submissionCount":8,"statusDistribution":{"ACCEPTED":4,"WRONG_ANSWER":4}}
                        """,
                "提交数 8，AC 4，WA 4。"
        );
    }
}
