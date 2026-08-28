package com.aioj.next.ai.agent.understanding;

import com.aioj.next.ai.agent.model.CallProfile;
import com.aioj.next.ai.agent.model.GatewayRequest;
import com.aioj.next.ai.agent.model.GatewayResponse;
import com.aioj.next.ai.agent.model.ModelGateway;
import com.aioj.next.ai.config.AiProperties;
import com.aioj.next.ai.domain.AiModelEffectiveConfig;
import com.aioj.next.ai.domain.AiModelScope;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class TurnUnderstandingServiceTest {

    private final ModelGateway modelGateway = mock(ModelGateway.class);
    private TurnUnderstandingService service;

    @BeforeEach
    void setUp() {
        service = new TurnUnderstandingService(modelGateway, new ObjectMapper(), new AiProperties());
    }

    @Test
    void firstTurnSkipsModelCallAndReturnsEmpty() {
        TurnUnderstandingService.TurnUnderstanding result = service.assess("讲一下第2题", false);

        assertThat(result.requiresTools()).isEmpty();
        assertThat(result.dialogueAct()).isNull();
        verify(modelGateway, never()).call(any(), any());
    }

    @Test
    void blankMessageSkipsModelCall() {
        assertThat(service.assess("   ", true).requiresTools()).isEmpty();
        verify(modelGateway, never()).call(any(), any());
    }

    @Test
    void parsesStructuredJudgementAndDropsUnknownValues() {
        when(modelGateway.configFor(any(AiModelScope.class))).thenReturn(mock(AiModelEffectiveConfig.class));
        when(modelGateway.call(any(), any(GatewayRequest.class)))
                .thenReturn(new GatewayResponse("""
                        {
                          "dialogueAct": "FOLLOW_UP",
                          "referenceTypes": ["ORDINAL", "NOT_A_KIND"],
                          "longRangeCue": true,
                          "requiresTools": ["CONTEXT_SEARCH", "HACK_THE_PLANET"]
                        }
                        """, null, null, 10, 5, 0, "deepseek", "deepseek-v4"));

        TurnUnderstandingService.TurnUnderstanding result = service.assess("最开始那批第2题讲一下吧", true);

        assertThat(result.dialogueAct()).isEqualTo("FOLLOW_UP");
        assertThat(result.referenceTypes()).containsExactly("ORDINAL");
        assertThat(result.longRangeCue()).isTrue();
        assertThat(result.requiresTools()).containsExactly("CONTEXT_SEARCH");
        assertThat(result.usage()).isNotNull();
        assertThat(result.usage().promptTokens()).isEqualTo(10);
        assertThat(result.usage().completionTokens()).isEqualTo(5);
    }

    @Test
    void stripsCodeFenceAroundJson() {
        when(modelGateway.configFor(any(AiModelScope.class))).thenReturn(mock(AiModelEffectiveConfig.class));
        when(modelGateway.call(any(), any(GatewayRequest.class)))
                .thenReturn(new GatewayResponse("""
                        ```json
                        {"dialogueAct":"RECALL_HISTORY","referenceTypes":[],"longRangeCue":false,"requiresTools":["MEMORY_SEARCH"]}
                        ```
                        """, null, null, 10, 5, 0, "deepseek", "deepseek-v4"));

        TurnUnderstandingService.TurnUnderstanding result = service.assess("我上次说过我喜欢什么风格？", true);

        assertThat(result.dialogueAct()).isEqualTo("RECALL_HISTORY");
        assertThat(result.requiresTools()).containsExactly("MEMORY_SEARCH");
    }

    @Test
    void unknownDialogueActBecomesNullButKnownToolsSurvive() {
        when(modelGateway.configFor(any(AiModelScope.class))).thenReturn(mock(AiModelEffectiveConfig.class));
        when(modelGateway.call(any(), any(GatewayRequest.class)))
                .thenReturn(new GatewayResponse(
                        "{\"dialogueAct\":\"YOLO\",\"referenceTypes\":[],\"requiresTools\":[\"PROBLEM_FETCH\"]}",
                        null, null, 10, 5, 0, "deepseek", "deepseek-v4"));

        TurnUnderstandingService.TurnUnderstanding result = service.assess("这道题完整题面是什么", true);

        assertThat(result.dialogueAct()).isNull();
        assertThat(result.requiresTools()).containsExactly("PROBLEM_FETCH");
    }

    @Test
    void invalidJsonFailsOpenToEmpty() {
        when(modelGateway.configFor(any(AiModelScope.class))).thenReturn(mock(AiModelEffectiveConfig.class));
        when(modelGateway.call(any(), any(GatewayRequest.class)))
                .thenReturn(new GatewayResponse("not json at all", null, null, 10, 5, 0, "deepseek", "deepseek-v4"));

        assertThat(service.assess("继续", true).requiresTools()).isEmpty();
    }

    @Test
    void providerFailureFailsOpenToEmpty() {
        when(modelGateway.configFor(any(AiModelScope.class))).thenReturn(mock(AiModelEffectiveConfig.class));
        when(modelGateway.call(any(), any(GatewayRequest.class)))
                .thenThrow(new RuntimeException("provider down"));

        assertThat(service.assess("继续", true).requiresTools()).isEmpty();
    }

    @Test
    void usesStructuredSmallProfileAndCuratorScope() {
        AiModelEffectiveConfig config = mock(AiModelEffectiveConfig.class);
        when(modelGateway.configFor(any(AiModelScope.class))).thenReturn(config);
        when(modelGateway.call(any(), any(GatewayRequest.class)))
                .thenReturn(new GatewayResponse("{}", null, null, 10, 5, 0, "deepseek", "deepseek-v4"));

        service.assess("为什么？", true);

        verify(modelGateway).configFor(AiModelScope.AGENT_CURATOR);
        org.mockito.ArgumentCaptor<GatewayRequest> request = org.mockito.ArgumentCaptor.forClass(GatewayRequest.class);
        verify(modelGateway).call(eq(config), request.capture());
        assertThat(request.getValue().profile()).isEqualTo(CallProfile.STRUCTURED_SMALL);
        assertThat(request.getValue().tools()).isEmpty();
    }
}
