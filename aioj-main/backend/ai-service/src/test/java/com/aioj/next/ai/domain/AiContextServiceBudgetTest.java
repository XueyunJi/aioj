package com.aioj.next.ai.domain;

import com.aioj.next.ai.config.AiProperties;
import com.aioj.next.ai.domain.context.AiContextBudgetReport;
import com.aioj.next.ai.domain.context.AiContextReportBuilder;
import com.aioj.next.ai.domain.context.AiConversationContextV2Service;
import com.aioj.next.ai.persistence.entity.AiConversationEntity;
import com.aioj.next.ai.persistence.mapper.AiConversationMapper;
import com.aioj.next.ai.persistence.mapper.AiConversationProblemMapper;
import com.aioj.next.ai.persistence.mapper.AiMessageMapper;
import com.aioj.next.contract.ai.AiChatRequest;
import com.aioj.next.contract.ai.AiProblemContextResponse;
import com.aioj.next.contract.contest.ContestAiPolicyResponse;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.time.Instant;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class AiContextServiceBudgetTest {
    private static final Long USER_ID = 7L;
    private static final String CONVERSATION_ID = "c-budget-service";

    @Mock
    private AiConversationMapper conversationMapper;
    @Mock
    private AiMessageMapper messageMapper;
    @Mock
    private AiConversationProblemMapper problemMapper;
    @Mock
    private AiMemoryService memoryService;
    @Mock
    private AiRetrievalService retrievalService;
    @Mock
    private AiConversationContextV2Service contextV2Service;
    @Mock
    private AiProblemContextResolver problemContextResolver;
    @Mock
    private AiSubmissionContextResolver submissionContextResolver;

    private final ObjectMapper objectMapper = new ObjectMapper();
    private AiConversationEntity conversation;
    private AiChatRequest request;

    @BeforeEach
    void setUp() {
        conversation = new AiConversationEntity();
        conversation.setId(CONVERSATION_ID);
        conversation.setUserId(USER_ID);
        conversation.setSummary("");
        conversation.setCreatedAt(LocalDateTime.now());
        conversation.setUpdatedAt(LocalDateTime.now());
        request = new AiChatRequest(CONVERSATION_ID, null, "继续调试", "assist", null, null, null);

        lenient().when(submissionContextResolver.resolve(USER_ID, request)).thenReturn(null);
        lenient().when(problemContextResolver.resolve(USER_ID, request)).thenReturn(null);
        lenient().when(problemContextResolver.contextBlock(any(AiProblemContextResponse.class))).thenReturn("");
        lenient().when(problemContextResolver.safeSummary(any(AiProblemContextResponse.class))).thenReturn(Map.of());
        lenient().when(problemMapper.selectList(any())).thenReturn(List.of());
        lenient().when(messageMapper.selectList(any())).thenReturn(List.of());
        lenient().when(retrievalService.search(eq(USER_ID), anyString(), anyList(), eq(5))).thenReturn(List.of());
        lenient().when(contextV2Service.contextPack(eq(USER_ID), eq(CONVERSATION_ID), eq(request), anyString(), anyString()))
                .thenAnswer(invocation -> {
                    String memories = invocation.getArgument(3);
                    String history = invocation.getArgument(4);
                    if ((memories == null || memories.isBlank()) && (history == null || history.isBlank())) {
                        return "[Current Conversation State]\n- required state survives\n\n[Teaching Strategy]\n- keep guidance";
                    }
                    return "[Current Conversation State]\n- required state survives\n\n[Teaching Strategy]\n- keep guidance\n\n"
                            + memories
                            + "\n"
                            + history;
                });
    }

    @Test
    void preProviderCompressionTriggersAtSeventyPercentOfCurrentModelWindowAndRebuildsContext() {
        AtomicInteger recalls = new AtomicInteger();
        when(memoryService.recallForContext(eq(USER_ID), anyString()))
                .thenAnswer(ignored -> recalls.getAndIncrement() == 0 ? "m".repeat(42_000) : "short memory");
        when(contextV2Service.compactBeforeProvider(USER_ID, CONVERSATION_ID))
                .thenReturn(new AiConversationContextV2Service.PreProviderCompressionResult(true, null, 3200, "1", "14"));

        AiChatContext context = service("mock-16k").build(USER_ID, conversation, request, ContestAiPolicyResponse.inactive());

        verify(contextV2Service).compactBeforeProvider(USER_ID, CONVERSATION_ID);
        assertThat(context.contextBuildReport().budget().compressionApplied()).isTrue();
        assertThat(context.contextBuildReport().budget().estimatedPromptTokensBefore())
                .isGreaterThanOrEqualTo(context.contextBuildReport().budget().maxPromptBudgetTokens());
        assertThat(context.contextBuildReport().budget().estimatedPromptTokensAfter())
                .isLessThan(context.contextBuildReport().budget().maxPromptBudgetTokens());
        assertThat(context.conversationContextPack()).contains("[Current Conversation State]", "[Teaching Strategy]");
    }

    @Test
    void sixtyFourKModelDoesNotTriggerCompressionJustBecauseContextExceedsOldSevenThousandTokens() {
        when(memoryService.recallForContext(eq(USER_ID), anyString())).thenReturn("m".repeat(32_000));

        AiChatContext context = service("mock-64k").build(USER_ID, conversation, request, ContestAiPolicyResponse.inactive());

        verify(contextV2Service, never()).compactBeforeProvider(USER_ID, CONVERSATION_ID);
        assertThat(context.contextBuildReport().budget().estimatedPromptTokensBefore()).isGreaterThan(7_000);
        assertThat(context.contextBuildReport().budget().compressionApplied()).isFalse();
        assertThat(context.contextBuildReport().budget().modelWindowTokens()).isEqualTo(64_000);
    }

    @Test
    void invalidPreProviderCompressionDoesNotMarkAppliedAndTrimsOptionalSections() {
        when(memoryService.recallForContext(eq(USER_ID), anyString())).thenReturn("m".repeat(42_000));
        when(contextV2Service.compactBeforeProvider(USER_ID, CONVERSATION_ID))
                .thenReturn(new AiConversationContextV2Service.PreProviderCompressionResult(false, "EMPTY_KEPT_SEGMENTS", 120, "1", "14"));

        AiChatContext context = service("mock-16k").build(USER_ID, conversation, request, ContestAiPolicyResponse.inactive());

        assertThat(context.contextBuildReport().budget().compressionApplied()).isFalse();
        assertThat(context.contextBuildReport().budget().warnings()).contains("COMPRESSION_SKIPPED:EMPTY_KEPT_SEGMENTS");
        assertThat(context.contextBuildReport().budget().trimmedSections()).contains("memory.long_term");
        assertThat(context.contextBuildReport().sections()).anySatisfy(section -> {
            assertThat(section.id()).isEqualTo("conversation.state");
            assertThat(section.required()).isTrue();
        });
        assertThat(context.contextBuildReport().sections()).anySatisfy(section -> {
            assertThat(section.id()).isEqualTo("teaching.strategy");
            assertThat(section.required()).isTrue();
        });
    }

    @Test
    void stillOverBudgetAfterDropsHardTruncatesContextPackInsteadOfOnlyWarning() {
        String huge = "状".repeat(40_000);
        when(contextV2Service.contextPack(eq(USER_ID), eq(CONVERSATION_ID), eq(request), anyString(), anyString()))
                .thenReturn(huge);
        when(contextV2Service.compactBeforeProvider(USER_ID, CONVERSATION_ID))
                .thenReturn(new AiConversationContextV2Service.PreProviderCompressionResult(false, "EMPTY", 0, "1", "1"));
        when(memoryService.recallForContext(eq(USER_ID), anyString())).thenReturn("");

        AiChatContext context = service("mock-16k").build(USER_ID, conversation, request, ContestAiPolicyResponse.inactive());

        AiContextBudgetReport budget = context.contextBuildReport().budget();
        assertThat(budget.trimmedSections()).contains("context_pack.hard_truncated");
        assertThat(budget.warnings()).doesNotContain("BUDGET_STILL_OVER_LIMIT");
        assertThat(budget.estimatedPromptTokensAfter()).isLessThan(budget.maxPromptBudgetTokens());
        assertThat(context.conversationContextPack().length()).isLessThan(huge.length());
    }

    private AiContextService service(String model) {
        AiProperties properties = new AiProperties();
        properties.getContext().setEstimatorSafetyFactor(1.0);
        return new AiContextService(
                conversationMapper,
                messageMapper,
                problemMapper,
                memoryService,
                retrievalService,
                contextV2Service,
                problemContextResolver,
                submissionContextResolver,
                new AiContextReportBuilder(),
                new AiContextBudgetService(properties, new AiModelContextWindowRegistry(properties)),
                scope -> new AiModelEffectiveConfig(
                        AiModelScope.TEXT_GENERATION,
                        true,
                        true,
                        "test",
                        "mock",
                        "",
                        "",
                        "",
                        "",
                        "",
                        model,
                        false,
                        false,
                        "high",
                        null,
                        null,
                        null,
                        Instant.now(),
                        null
                ),
                objectMapper
        );
    }
}
