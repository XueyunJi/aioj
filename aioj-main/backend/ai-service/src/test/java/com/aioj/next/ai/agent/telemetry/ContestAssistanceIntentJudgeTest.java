package com.aioj.next.ai.agent.telemetry;

import com.aioj.next.ai.agent.model.CallProfile;
import com.aioj.next.ai.agent.model.GatewayRequest;
import com.aioj.next.ai.agent.model.GatewayResponse;
import com.aioj.next.ai.agent.model.ModelGateway;
import com.aioj.next.ai.domain.AiModelEffectiveConfig;
import com.aioj.next.ai.domain.AiModelScope;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class ContestAssistanceIntentJudgeTest {
    private final ModelGateway modelGateway = mock(ModelGateway.class);
    private final AiModelEffectiveConfig config = mock(AiModelEffectiveConfig.class);
    private ContestAssistanceIntentJudge judge;

    @BeforeEach
    void setUp() {
        judge = new ContestAssistanceIntentJudge(modelGateway, new ObjectMapper());
        when(modelGateway.configFor(AiModelScope.INTENT)).thenReturn(config);
    }

    @Test
    void privateQuestionWinsWhenBothFlagsAreReturnedAndRecordsProviderUsage() {
        when(modelGateway.call(eq(config), any(GatewayRequest.class))).thenReturn(response(
                "{\"privateContestQuestion\":true,\"publicFullCodeRequest\":true}"));

        ContestAssistanceIntentJudge.Judgement judgement = judge.assess("please solve it", List.of(
                new ContestAssistanceIntentJudge.Candidate("PUBLIC", "MESSAGE_OR_CONTEXT_FINGERPRINT"),
                new ContestAssistanceIntentJudge.Candidate("PRIVATE", "MESSAGE_OR_CONTEXT_FINGERPRINT")
        ));

        assertThat(judgement.interceptType())
                .isEqualTo(ContestAssistanceIntentJudge.InterceptType.PRIVATE_CONTEST_QUESTION);
        assertThat(judgement.status()).isEqualTo(ContestAssistanceIntentJudge.Status.COMPLETED);
        assertThat(judgement.usage()).isNotNull();
        assertThat(judgement.usage().promptTokens()).isEqualTo(11);
    }

    @Test
    void publicIdeaRequestIsNotCountedAndOnlySanitizedMetadataIsSent() {
        when(modelGateway.call(eq(config), any(GatewayRequest.class))).thenReturn(response(
                "{\"privateContestQuestion\":false,\"publicFullCodeRequest\":false}"));

        ContestAssistanceIntentJudge.Judgement judgement = judge.assess("explain the idea", List.of(
                new ContestAssistanceIntentJudge.Candidate("PUBLIC", "TRUSTED_ENTRY_CONTEXT")
        ));

        assertThat(judgement.interceptType()).isEqualTo(ContestAssistanceIntentJudge.InterceptType.NONE);
        ArgumentCaptor<GatewayRequest> request = ArgumentCaptor.forClass(GatewayRequest.class);
        verify(modelGateway).call(eq(config), request.capture());
        assertThat(request.getValue().profile()).isEqualTo(CallProfile.STRUCTURED_SMALL);
        String prompt = request.getValue().messages().get(1).content();
        assertThat(prompt).contains("visibility=PUBLIC");
        assertThat(prompt).doesNotContain("statement=");
    }

    @Test
    void publicFullCodeRequestIsCountedOnceForTheTurn() {
        when(modelGateway.call(eq(config), any(GatewayRequest.class))).thenReturn(response(
                "{\"privateContestQuestion\":false,\"publicFullCodeRequest\":true}"));

        ContestAssistanceIntentJudge.Judgement judgement = judge.assess("give me complete code", List.of(
                new ContestAssistanceIntentJudge.Candidate("PUBLIC", "MESSAGE_OR_CONTEXT_FINGERPRINT")
        ));

        assertThat(judgement.interceptType())
                .isEqualTo(ContestAssistanceIntentJudge.InterceptType.PUBLIC_FULL_CODE_REQUEST);
        assertThat(judgement.intercepted()).isTrue();
    }

    @Test
    void invalidOrUnavailableJudgementDoesNotCreateAnInterception() {
        when(modelGateway.call(eq(config), any(GatewayRequest.class)))
                .thenThrow(new RuntimeException("provider unavailable"));

        ContestAssistanceIntentJudge.Judgement judgement = judge.assess("give me complete code", List.of(
                new ContestAssistanceIntentJudge.Candidate("PUBLIC", "MESSAGE_OR_CONTEXT_FINGERPRINT")
        ));

        assertThat(judgement.interceptType()).isEqualTo(ContestAssistanceIntentJudge.InterceptType.UNAVAILABLE);
        assertThat(judgement.intercepted()).isFalse();
        assertThat(judgement.usage()).isNull();
    }

    private GatewayResponse response(String content) {
        return new GatewayResponse(content, List.of(), "stop", 11, 7, 0, "deepseek", "deepseek-intent");
    }
}
