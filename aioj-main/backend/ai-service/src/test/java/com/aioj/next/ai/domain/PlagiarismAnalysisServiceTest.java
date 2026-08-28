package com.aioj.next.ai.domain;

import com.aioj.next.contract.ai.AiChatRequest;
import com.aioj.next.contract.ai.PlagiarismAnalysisRequest;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class PlagiarismAnalysisServiceTest {
    @Test
    void successfulAnalysisRecordsUsageAndKeepsConclusionBounded() {
        AiProvider provider = mock(AiProvider.class);
        AiQuotaService quotaService = mock(AiQuotaService.class);
        when(provider.chat(any(AiChatRequest.class), any(AiChatContext.class), eq(AiModelScope.REPORT_ANALYSIS)))
                .thenReturn(new AiCompletion("这是风险线索，需要人工复核。", "mock", "mock-model", 20, 8));
        PlagiarismAnalysisService service = new PlagiarismAnalysisService(provider, quotaService,
                new AiCapacityService(new com.aioj.next.ai.config.AiProperties()));

        var response = service.analyze(request());

        assertThat(response.success()).isTrue();
        assertThat(response.analysis()).contains("风险线索");
        verify(quotaService).record(7L, "mock", "mock-model", 20, 8, true);
    }

    @Test
    void providerFailureReturnsStructuredFailedResponse() {
        AiProvider provider = mock(AiProvider.class);
        AiQuotaService quotaService = mock(AiQuotaService.class);
        when(provider.providerName()).thenReturn("mock");
        when(provider.model()).thenReturn("mock-model");
        when(provider.chat(any(AiChatRequest.class), any(AiChatContext.class), eq(AiModelScope.REPORT_ANALYSIS)))
                .thenThrow(new IllegalStateException("upstream unavailable"));
        PlagiarismAnalysisService service = new PlagiarismAnalysisService(provider, quotaService,
                new AiCapacityService(new com.aioj.next.ai.config.AiProperties()));

        var response = service.analyze(request());

        assertThat(response.success()).isFalse();
        assertThat(response.errorMessage()).contains("upstream unavailable");
        verify(quotaService).record(eq(7L), eq("mock"), eq("mock-model"), eq(0L), eq(0L), eq(false));
    }

    private PlagiarismAnalysisRequest request() {
        return new PlagiarismAnalysisRequest(
                7L,
                301L,
                "Spring Contest",
                501L,
                601L,
                "A",
                "Two Sum",
                "cpp",
                "HIGH",
                0.82,
                new PlagiarismAnalysisRequest.Participant(11L, 101L, 1001L, "alice", "Alice"),
                new PlagiarismAnalysisRequest.Participant(12L, 102L, 1002L, "bob", "Bob"),
                List.of(new PlagiarismAnalysisRequest.Fragment(1, 30, "for (...) sum += a[i];", "for (...) ans += b[i];"))
        );
    }
}
