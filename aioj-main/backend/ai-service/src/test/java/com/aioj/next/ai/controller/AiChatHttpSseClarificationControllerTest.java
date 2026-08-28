package com.aioj.next.ai.controller;

import com.aioj.next.ai.domain.AiChatContext;
import com.aioj.next.ai.domain.AiChatTurnService;
import com.aioj.next.ai.domain.AiCompletion;
import com.aioj.next.ai.domain.AiContextService;
import com.aioj.next.ai.domain.AiConversationService;
import com.aioj.next.ai.domain.AiMemoryService;
import com.aioj.next.ai.domain.AiLearningProfileService;
import com.aioj.next.ai.domain.AiProvider;
import com.aioj.next.ai.domain.AiQuotaService;
import com.aioj.next.ai.domain.AccountImportParseService;
import com.aioj.next.ai.domain.AiResponsePolicyGuard;
import com.aioj.next.ai.domain.ContestTurnGuard;
import com.aioj.next.ai.domain.ProblemDraftStore;
import com.aioj.next.ai.domain.memory.AiAfterTurnMemoryProfileEventService;
import com.aioj.next.ai.domain.memory.AiMemoryCandidateService;
import com.aioj.next.ai.domain.memory.AiMemoryDebugService;
import com.aioj.next.ai.domain.clarification.ClarificationSchemaRepairer;
import com.aioj.next.ai.domain.response.AiAssistantResponseNormalizer;
import com.aioj.next.ai.persistence.entity.AiConversationEntity;
import com.aioj.next.common.error.GlobalExceptionHandler;
import com.aioj.next.common.security.Role;
import com.aioj.next.common.security.SecurityPrincipal;
import com.aioj.next.contract.ai.AiChatMessageResponse;
import com.aioj.next.contract.ai.AiChatRequest;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Disabled;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.http.MediaType;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.atomic.AtomicLong;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.asyncDispatch;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.request;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@ExtendWith(MockitoExtension.class)
@Disabled("Legacy pipeline SSE test: the memory clarification flow and old context pack are not part of "
        + "Agent Core V3 P0 (decisions D2/Q3). Archive round will remove or port this coverage.")
class AiChatHttpSseClarificationControllerTest {
    private static final Long USER_ID = 7L;
    private static final String CONVERSATION_ID = "c-http-sse";
    private static final String QUESTION = "如果我们假设最小距离为某个值，我们如何检查这个距离是否能够满足选择 m 个星港的条件？";

    @Mock
    private AiProvider aiProvider;
    @Mock
    private AiQuotaService aiQuotaService;
    @Mock
    private AccountImportParseService accountImportParseService;
    @Mock
    private AiConversationService aiConversationService;
    @Mock
    private AiContextService aiContextService;
    @Mock
    private AiMemoryService aiMemoryService;
    @Mock
    private AiLearningProfileService aiLearningProfileService;
    @Mock
    private AiAfterTurnMemoryProfileEventService afterTurnMemoryProfileEventService;
    @Mock
    private AiMemoryCandidateService aiMemoryCandidateService;
    @Mock
    private AiMemoryDebugService aiMemoryDebugService;
    @Mock
    private ProblemDraftStore problemDraftStore;
    @Mock
    private ContestTurnGuard contestTurnGuard;
    @Mock
    private AiResponsePolicyGuard aiResponsePolicyGuard;

    private final ObjectMapper objectMapper = new ObjectMapper().findAndRegisterModules();
    private final List<AiChatContext> providerContexts = new ArrayList<>();
    private MockMvc mockMvc;

    @BeforeEach
    void setUp() {
        AiController controller = new AiController(
                aiProvider,
                org.mockito.Mockito.mock(com.aioj.next.ai.agent.AgentChatFacade.class),
                aiQuotaService,
                accountImportParseService,
                aiConversationService,
                aiContextService,
                aiMemoryService,
                aiLearningProfileService,
                aiMemoryCandidateService,
                aiMemoryDebugService,
                null,
                problemDraftStore,
                new AiAssistantResponseNormalizer(objectMapper, new ClarificationSchemaRepairer()),
                objectMapper
        );
        mockMvc = MockMvcBuilders.standaloneSetup(controller)
                .setControllerAdvice(new GlobalExceptionHandler())
                .build();
        authenticate(USER_ID);
        when(contestTurnGuard.evaluateAndApply(eq(USER_ID), any(AiChatRequest.class)))
                .thenAnswer(invocation -> ContestTurnGuard.GuardDecision.pass(invocation.getArgument(1)));
        when(aiResponsePolicyGuard.guard(eq(USER_ID), eq(CONVERSATION_ID), any(AiCompletion.class), anyBoolean()))
                .thenAnswer(invocation -> new AiResponsePolicyGuard.GuardedCompletion(invocation.getArgument(2), false, null));

        AiConversationEntity conversation = new AiConversationEntity();
        conversation.setId(CONVERSATION_ID);
        conversation.setUserId(USER_ID);
        conversation.setMode("hint");
        conversation.setTitle("星港建设");
        conversation.setCreatedAt(LocalDateTime.now());
        conversation.setUpdatedAt(LocalDateTime.now());

        AtomicLong messageIds = new AtomicLong(100);
        Map<Long, AiChatMessageResponse> messagesById = new java.util.LinkedHashMap<>();
        when(aiConversationService.resolveForWrite(eq(USER_ID), any(AiChatRequest.class))).thenReturn(conversation);
        when(aiContextService.snapshot(any(AiChatContext.class))).thenReturn("{}");
        when(aiContextService.build(eq(USER_ID), eq(conversation), any(AiChatRequest.class))).thenAnswer(invocation -> {
            AiChatRequest request = invocation.getArgument(2);
            if (request.clarificationAnswer() != null) {
                return new AiChatContext(
                        "[User Rules]\n完整代码默认 C++。",
                        "",
                        "当前题是最大化最小距离，数据范围 n <= 2e5, xi <= 1e9。",
                        "",
                        """
                                [Clarification Answer Just Submitted]
                                - Previous question: 如果我们假设最小距离为某个值，我们如何检查这个距离是否能够满足选择 m 个星港的条件？
                                - User answer: 是先用二分找距离，再检查距离是否合理吗？
                                - 中文说明：用户是在回答你之前的问题，不是在提出一个全新问题。
                                [Current Conversation State]
                                - 用户知道可以二分候选距离
                                - 用户还需要理解 check(d) 的贪心过程
                                - Do not repeat the same question.
                                """
                );
            }
            return new AiChatContext(
                    "",
                    "",
                    "当前题是最大化最小距离。",
                    "",
                    "[Current Conversation State]\n- currentStep: explain_feasibility_check"
            );
        });
        when(aiContextService.prepareCompletionForTurn(eq(USER_ID), eq(conversation), any(AiChatRequest.class), any(AiCompletion.class))).thenAnswer(invocation -> invocation.getArgument(3));
        when(aiConversationService.appendMessage(any(), eq(USER_ID), any(), any(), any(), any(), any(), any()))
                .thenAnswer(invocation -> {
                    AiChatMessageResponse response = new AiChatMessageResponse(
                        messageIds.getAndIncrement(),
                        invocation.getArgument(0),
                        invocation.getArgument(2),
                        invocation.getArgument(6, String.class),
                        invocation.getArgument(3),
                        invocation.getArgument(4),
                        invocation.getArgument(5),
                        Instant.now()
                    );
                    messagesById.put(response.id(), response);
                    return response;
                });
        when(aiConversationService.appendMessageWithStatus(any(), eq(USER_ID), any(), any(), any(), any(), any(), any(), any(), any()))
                .thenAnswer(invocation -> {
                    Instant now = Instant.now();
                    AiChatMessageResponse response = new AiChatMessageResponse(
                            messageIds.getAndIncrement(),
                            invocation.getArgument(0),
                            invocation.getArgument(2),
                            invocation.getArgument(6, String.class),
                            invocation.getArgument(3),
                            invocation.getArgument(4),
                            invocation.getArgument(5),
                            invocation.getArgument(8, String.class),
                            invocation.getArgument(9, String.class),
                            now,
                            null
                    );
                    messagesById.put(response.id(), response);
                    return response;
                });
        when(aiConversationService.completeMessage(eq(USER_ID), any(), any(), any()))
                .thenAnswer(invocation -> {
                    Long messageId = invocation.getArgument(1);
                    AiChatMessageResponse existing = messagesById.get(messageId);
                    AiChatMessageResponse response = new AiChatMessageResponse(
                            messageId,
                            existing.conversationId(),
                            existing.problemId(),
                            existing.clientMessageId(),
                            existing.role(),
                            invocation.getArgument(2, String.class),
                            invocation.getArgument(3, String.class),
                            "COMPLETED",
                            null,
                            existing.createdAt(),
                            Instant.now()
                    );
                    messagesById.put(response.id(), response);
                    return response;
                });
        when(aiProvider.chat(any(AiChatRequest.class), any(AiChatContext.class))).thenAnswer(invocation -> {
            AiChatRequest request = invocation.getArgument(0);
            AiChatContext context = invocation.getArgument(1);
            providerContexts.add(context);
            if (request.clarificationAnswer() == null) {
                return new AiCompletion(
                        "我们先确认 check(d) 的思路。",
                        "mock",
                        "mock-model",
                        120,
                        60,
                        new AiCompletion.Clarification(
                                "clarify_feasibility_check",
                                "helpful",
                                "确认检查方式",
                                QUESTION,
                                new AiCompletion.ClarificationInput("free_text", true, List.of(), true, "free_text", "请写下你的想法"),
                                List.of(),
                                "ask_user",
                                null
                        )
                );
            }
            return new AiCompletion(
                    "对，方向是对的：先二分一个候选最小距离 d，再从左到右扫描，选择满足距离 d 的最靠左可行位置。这里不是每次选最远位置。",
                    "mock",
                    "mock-model",
                    180,
                    80
            );
        });
    }

    @AfterEach
    void tearDown() {
        SecurityContextHolder.clearContext();
    }

    @Test
    void streamEndpointEmitsClarificationAndThenContinuesFromStructuredClarificationAnswer() throws Exception {
        String firstBody = performStream(new AiChatRequest(
                CONVERSATION_ID,
                null,
                "这题怎么入手？",
                "hint",
                null,
                null,
                null
        ));

        assertThat(firstBody).contains("event: context");
        assertThat(firstBody).contains("event: clarification");
        assertThat(firstBody).contains("clarify_feasibility_check");
        assertThat(firstBody).contains("free_text");
        assertThat(firstBody).doesNotContain("event: error");

        String secondBody = performStream(new AiChatRequest(
                CONVERSATION_ID,
                null,
                "已补充：是先用二分找距离，再检查距离是否合理吗？",
                "hint",
                null,
                null,
                new AiChatRequest.ClarificationAnswer(
                        "clarify_feasibility_check",
                        QUESTION,
                        "是先用二分找距离，再检查距离是否合理吗？",
                        List.of(),
                        null
                )
        ));

        assertThat(secondBody).contains("event: context");
        assertThat(secondBody).contains("Clarification Answer Just Submitted");
        assertThat(secondBody).contains("用户是在回答你之前的问题");
        assertThat(secondBody).contains("event: message");
        assertThat(secondBody).contains("contentMarkdown");
        assertThat(secondBody).contains("二分一个候选最小距离 d");
        assertThat(secondBody).contains("从左到右扫描");
        assertThat(secondBody).contains("最靠左可行位置");
        assertThat(secondBody).contains("不是每次选最远位置");
        assertThat(secondBody).doesNotContain("event: clarification");

        assertThat(providerContexts).hasSize(2);
        assertThat(providerContexts.get(1).conversationContextPack())
                .contains("Clarification Answer Just Submitted")
                .contains("用户知道可以二分候选距离")
                .contains("用户还需要理解 check(d) 的贪心过程")
                .contains("Do not repeat the same question");
        verify(aiQuotaService, times(2)).assertAvailable(USER_ID);
    }

    private String performStream(AiChatRequest requestBody) throws Exception {
        MvcResult started = mockMvc.perform(post("/ai/chat/stream")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(requestBody)))
                .andExpect(request().asyncStarted())
                .andReturn();
        MvcResult completed = mockMvc.perform(asyncDispatch(started))
                .andExpect(status().isOk())
                .andReturn();
        return completed.getResponse().getContentAsString(StandardCharsets.UTF_8);
    }

    private void authenticate(Long userId) {
        SecurityPrincipal principal = new SecurityPrincipal(userId, "elvis", Set.of(Role.STUDENT));
        UsernamePasswordAuthenticationToken authentication = new UsernamePasswordAuthenticationToken(
                principal,
                "n/a",
                List.of(new SimpleGrantedAuthority("ROLE_STUDENT"))
        );
        SecurityContextHolder.getContext().setAuthentication(authentication);
    }
}
