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
import com.aioj.next.ai.domain.clarification.ClarificationSchemaRepairer;
import com.aioj.next.ai.domain.context.AiContextBudgetReport;
import com.aioj.next.ai.domain.context.AiContextBuildReport;
import com.aioj.next.ai.domain.context.AiContextSection;
import com.aioj.next.ai.domain.memory.AiAfterTurnMemoryProfileEventService;
import com.aioj.next.ai.domain.memory.AiMemoryCandidateService;
import com.aioj.next.ai.domain.memory.AiMemoryDebugService;
import com.aioj.next.ai.domain.response.AiAssistantResponseNormalizer;
import com.aioj.next.ai.persistence.entity.AiConversationEntity;
import com.aioj.next.common.error.GlobalExceptionHandler;
import com.aioj.next.common.security.Role;
import com.aioj.next.common.security.SecurityPrincipal;
import com.aioj.next.contract.ai.AiChatMessageResponse;
import com.aioj.next.contract.ai.AiChatRequest;
import com.aioj.next.contract.problem.TestCaseDto;
import com.fasterxml.jackson.core.type.TypeReference;
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
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.atomic.AtomicLong;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.asyncDispatch;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.request;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@ExtendWith(MockitoExtension.class)
@Disabled("Legacy pipeline SSE test: the old context pack / submission resolution / structured-output surface "
        + "is not part of Agent Core V3 P0 (decisions D2/Q3). Archive round will remove or port this coverage.")
class AiChatHttpSseStructuredResponseControllerTest {
    private static final Long USER_ID = 7L;
    private static final String CONVERSATION_ID = "c-structured-sse";

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

        AtomicLong messageIds = new AtomicLong(1000);
        Map<Long, AiChatMessageResponse> messagesById = new LinkedHashMap<>();
        when(aiConversationService.resolveForWrite(eq(USER_ID), any(AiChatRequest.class))).thenReturn(conversation);
        when(aiContextService.snapshot(any(AiChatContext.class))).thenReturn("{}");
        lenient().when(aiContextService.build(eq(USER_ID), eq(conversation), any(AiChatRequest.class))).thenReturn(new AiChatContext(
                "[User Rules]\n完整代码默认 C++。",
                "",
                "当前题是最大化最小距离。",
                "",
                "[Current Conversation State]\n- 算法方向：排序 + 二分答案 + 贪心"
        ));
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
        lenient().when(aiConversationService.getOwnedConversation(eq(USER_ID), eq(CONVERSATION_ID)))
                .thenReturn(conversation);
        lenient().when(aiConversationService.getMessage(eq(USER_ID), any()))
                .thenAnswer(invocation -> messagesById.get(invocation.getArgument(1, Long.class)));
    }

    @AfterEach
    void tearDown() {
        SecurityContextHolder.clearContext();
    }

    @Test
    void streamMessageCarriesVisibleMarkdownOnlyAndClarificationAsSeparateEvent() throws Exception {
        when(aiProvider.chat(any(AiChatRequest.class), any(AiChatContext.class))).thenReturn(new AiCompletion("""
                {
                  "teachingDecision": "SOCRATIC",
                  "stuckLayer": "OPTIMIZATION",
                  "studentLevel": "intermediate",
                  "content": "对，先二分一个候选最小距离 `d`，再用贪心检查。",
                  "clarification": {
                    "id": "clarify_pivot_median_strategy",
                    "title": "确认检查方式",
                    "prompt": "你会如何检查 d 是否可行？",
                    "input": {"kind": "free_text", "required": true, "allowCustom": true, "placeholder": "写下 check(d) 的想法"},
                    "options": []
                  }
                }
                """, "mock", "mock-model", 20, 12));

        String body = performStream(requestWithProblemContext());
        Map<String, String> events = events(body);
        Map<String, Object> message = objectMapper.readValue(events.get("message"), new TypeReference<>() {
        });

        assertThat(events).containsKeys("context", "message", "clarification", "done");
        assertThat(message.get("contentMarkdown")).isEqualTo("对，先二分一个候选最小距离 `d`，再用贪心检查。");
        assertThat(events.get("message")).doesNotContain("teachingDecision", "stuckLayer", "studentLevel", "\"clarification\"");
        assertThat(events.get("clarification")).contains("clarify_pivot_median_strategy").contains("free_text");
    }

    @Test
    void streamContextIncludesProblemContextAndRenderHintsForFrontendCard() throws Exception {
        when(aiProvider.chat(any(AiChatRequest.class), any(AiChatContext.class))).thenReturn(new AiCompletion("""
                {"teachingDecision":"HINT","content":"题目规模提示我们要找单调性。","clarification":{"options":[]}}
                """, "mock", "mock-model", 20, 12));

        String body = performStream(requestWithProblemContext());
        Map<String, String> events = events(body);
        Map<String, Object> context = objectMapper.readValue(events.get("context"), new TypeReference<>() {
        });
        Map<String, Object> problemContext = objectMapper.convertValue(context.get("problemContext"), new TypeReference<>() {
        });
        Map<String, Object> renderHints = objectMapper.convertValue(context.get("renderHints"), new TypeReference<>() {
        });

        assertThat(problemContext).containsEntry("title", "星港建设");
        assertThat(problemContext).containsEntry("difficulty", "MEDIUM");
        assertThat(problemContext.get("tags")).asList().contains("binary_search_on_answer", "greedy");
        assertThat(problemContext.get("constraints")).asList().anySatisfy(item -> assertThat(item.toString()).contains("n <= 2e5"));
        assertThat(renderHints).containsEntry("showProblemContext", "compact");
        assertThat(renderHints.get("problemRefs")).asList().contains("title", "constraints", "tags");
    }

    @Test
    void streamEventsCarryResolvedSubmissionContextSummary() throws Exception {
        when(aiContextService.build(eq(USER_ID), any(AiConversationEntity.class), any(AiChatRequest.class))).thenReturn(new AiChatContext(
                "",
                "",
                "[Selected Submission Context]\nstatus: WRONG_ANSWER\ncodeAllowedToModel: false",
                "",
                """
                        [Submission Focus]
                        - submissionId: 123
                        <CURRENT_SUBMISSION_CODE language="cpp">
                        #include <bits/stdc++.h>
                        int main() { return 0; }
                        </CURRENT_SUBMISSION_CODE>
                        stdoutExcerpt:
                        stdout secret
                        """,
                resolvedSubmissionSummary(),
                resolvedContextBuildReport()
        ));
        when(aiProvider.chat(any(AiChatRequest.class), any(AiChatContext.class))).thenReturn(new AiCompletion("""
                {"teachingDecision":"DIRECT","content":"先围绕这次 WA 的用例摘要排查边界。","clarification":{"options":[]}}
                """, "mock", "mock-model", 20, 12));

        String body = performStream(requestWithSubmissionContext());
        Map<String, String> events = events(body);
        Map<String, Object> context = objectMapper.readValue(events.get("context"), new TypeReference<>() {
        });
        Map<String, Object> message = objectMapper.readValue(events.get("message"), new TypeReference<>() {
        });
        Map<String, Object> contextSubmission = objectMapper.convertValue(context.get("submissionContext"), new TypeReference<>() {
        });
        Map<String, Object> messageSubmission = objectMapper.convertValue(message.get("submissionContext"), new TypeReference<>() {
        });
        Map<String, Object> contextBuildReport = objectMapper.convertValue(context.get("contextBuildReport"), new TypeReference<>() {
        });
        Map<String, Object> budget = objectMapper.convertValue(contextBuildReport.get("budget"), new TypeReference<>() {
        });
        List<Map<String, Object>> sections = objectMapper.convertValue(contextBuildReport.get("sections"), new TypeReference<>() {
        });

        assertThat(contextSubmission)
                .containsEntry("submissionId", "123")
                .containsEntry("problemId", "99")
                .containsEntry("status", "WRONG_ANSWER")
                .containsEntry("language", "cpp")
                .containsEntry("codeAllowedToModel", false)
                .containsEntry("source", "resolved.submissionContext");
        assertThat(contextSubmission.get("caseResults")).asList()
                .anySatisfy(item -> assertThat(item.toString()).contains("case 2 failed"));
        assertThat(messageSubmission).containsEntry("source", "resolved.submissionContext");
        assertThat(sections).anySatisfy(section -> assertThat(section)
                .containsEntry("id", "resolved.submission")
                .containsEntry("type", "submission_context")
                .containsEntry("required", true)
                .containsEntry("sensitivity", "submission_safe"));
        assertThat(sections).anySatisfy(section -> {
            assertThat(section).containsEntry("id", "retrieval.history");
            assertThat(section.get("metadata").toString())
                    .contains("submission_analysis", "same_submission", "4.2")
                    .doesNotContain("int main", "stdout secret", "stderr secret");
        });
        assertThat(budget)
                .containsEntry("model", "mock-16k")
                .containsEntry("modelWindowTokens", 16_000)
                .containsEntry("compressionThresholdTokens", 11_200);
        assertThat(events.get("context")).doesNotContain("int main", "stdout secret", "stderr secret", "codeText", "stdoutExcerpt", "stderrExcerpt");
        assertThat(events.get("message")).doesNotContain("int main", "stdout secret", "stderr secret", "codeText", "stdoutExcerpt", "stderrExcerpt");
    }

    @Test
    void streamEventsCarryClientAndServerMessageIdsForFrontendDeduplication() throws Exception {
        when(aiProvider.chat(any(AiChatRequest.class), any(AiChatContext.class))).thenReturn(new AiCompletion("""
                {"teachingDecision":"DIRECT","content":"下面直接给 C++ 代码。","clarification":{"options":[]}}
                """, "mock", "mock-model", 20, 12));

        String body = performStream(requestWithProblemContextAndClientId());
        Map<String, String> events = events(body);
        Map<String, Object> meta = objectMapper.readValue(events.get("meta"), new TypeReference<>() {
        });
        Map<String, Object> message = objectMapper.readValue(events.get("message"), new TypeReference<>() {
        });
        Map<String, Object> done = objectMapper.readValue(events.get("done"), new TypeReference<>() {
        });

        assertThat(meta).containsEntry("clientMessageId", "client-123");
        assertThat(message).containsEntry("requestClientMessageId", "client-123");
        assertThat(String.valueOf(message.get("clientMessageId"))).isEqualTo("client-123:assistant");
        assertThat(done).containsEntry("clientMessageId", "client-123");
        assertThat(done).containsKeys("userMessageId", "assistantMessageId", "assistantClientMessageId");
    }

    @Test
    void streamResumeByTurnIdReplaysCompletedTurnWithoutRegeneration() throws Exception {
        when(aiProvider.chat(any(AiChatRequest.class), any(AiChatContext.class))).thenReturn(new AiCompletion("""
                {"teachingDecision":"DIRECT","content":"第一次回答：先找单调性。","clarification":{"options":[]}}
                """, "mock", "mock-model", 20, 12));

        String firstBody = performStream(requestWithProblemContextAndClientId());
        Map<String, Object> firstMeta = objectMapper.readValue(events(firstBody).get("meta"), new TypeReference<>() {
        });
        String turnId = String.valueOf(firstMeta.get("turnId"));
        assertThat(turnId).isNotBlank();

        MvcResult started = mockMvc.perform(post("/ai/chat/stream?resumeTurnId=" + turnId)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(requestWithProblemContextAndClientId())))
                .andExpect(request().asyncStarted())
                .andReturn();
        MvcResult completed = mockMvc.perform(asyncDispatch(started))
                .andExpect(status().isOk())
                .andReturn();
        String secondBody = completed.getResponse().getContentAsString(StandardCharsets.UTF_8);

        Map<String, String> resumedEvents = events(secondBody);
        assertThat(resumedEvents.get("meta")).contains(turnId);
        assertThat(resumedEvents.get("message")).contains("第一次回答：先找单调性。");
        verify(aiProvider, times(1)).chat(any(AiChatRequest.class), any(AiChatContext.class));
    }

    private AiChatRequest requestWithProblemContext() {
        return new AiChatRequest(
                CONVERSATION_ID,
                2058726164939169794L,
                "这题怎么入手？",
                "hint",
                new AiChatRequest.ProblemContext(
                        "2058726164939169794",
                        "星港建设",
                        "MEDIUM",
                        """
                                在一条直线上有 n 个候选星港坐标，选择 m 个星港，使任意两个被选星港之间的最小距离最大。
                                数据范围：2 <= m <= n <= 2e5，0 <= xi <= 1e9。
                                """,
                        "最大化最小值时，通常需要检查答案是否具备单调性。",
                        List.of("binary_search_on_answer", "greedy"),
                        List.of(new TestCaseDto("5 3\n1\n2\n8\n4\n9\n", "3\n", true)),
                        1000,
                        262144
                ),
                null,
                null
        );
    }

    private AiChatRequest requestWithSubmissionContext() {
        return new AiChatRequest(
                CONVERSATION_ID,
                99L,
                "我提交了，答案错误",
                "assist",
                null,
                null,
                null,
                "client-submission",
                null,
                null,
                new AiChatRequest.SubmissionContext(123L, "EXPLAIN_ERROR", true, "status: WA")
        );
    }

    private AiChatRequest requestWithProblemContextAndClientId() {
        AiChatRequest base = requestWithProblemContext();
        return new AiChatRequest(
                base.conversationId(),
                base.problemId(),
                "先给代码",
                base.mode(),
                base.problemContext(),
                base.codeContext(),
                base.clarificationAnswer(),
                "client-123",
                null
        );
    }

    private Map<String, Object> resolvedSubmissionSummary() {
        Map<String, Object> summary = new LinkedHashMap<>();
        summary.put("submissionId", "123");
        summary.put("problemId", "99");
        summary.put("scope", "CONTEST");
        summary.put("contestActive", true);
        summary.put("language", "cpp");
        summary.put("status", "WRONG_ANSWER");
        summary.put("judgeMessage", "Wrong answer on case 2");
        summary.put("runTimeMillis", 12);
        summary.put("memoryKb", 2048);
        summary.put("score", 0.0);
        summary.put("maxScore", 100.0);
        summary.put("codeAllowedToModel", false);
        summary.put("codeHash", "sha256-submission");
        summary.put("source", "resolved.submissionContext");
        summary.put("caseResults", List.of(Map.of(
                "caseIndex", 1,
                "caseName", "case 2",
                "status", "WRONG_ANSWER",
                "message", "case 2 failed"
        )));
        summary.put("codeText", "int main() { return 0; }");
        summary.put("stdoutExcerpt", "stdout secret");
        summary.put("stderrExcerpt", "stderr secret");
        return summary;
    }

    private AiContextBuildReport resolvedContextBuildReport() {
        AiContextSection section = new AiContextSection(
                "resolved.submission",
                "submission_context",
                "Server resolved submission context",
                94,
                "problem-service.internal",
                "submission_safe",
                24,
                true,
               "{\"submissionId\":\"123\",\"status\":\"WRONG_ANSWER\",\"codeAllowedToModel\":false}",
               Map.of("submissionId", "123", "status", "WRONG_ANSWER", "codeAllowedToModel", false)
       );
       AiContextSection retrieval = new AiContextSection(
               "retrieval.history",
               "retrieved_history",
               "Retrieved past context",
               50,
               "ai-service.retrieval",
               "memory",
               16,
               false,
               "上一轮 assistant 给过可运行代码，需要反思。",
               Map.of("hits", List.of(Map.of(
                       "ownerType", "submission_analysis",
                       "ownerId", "123",
                       "score", 4.2,
                       "reasons", List.of("same_submission"),
                       "preview", "上一轮 assistant 给过可运行代码，需要反思。"
               )))
       );
       return new AiContextBuildReport(
               List.of(section, retrieval),
               Map.of("problem-service.internal", 1, "ai-service.retrieval", 1),
               40,
               24,
               16,
               1,
               1,
               new AiContextBudgetReport(
                       "mock-16k",
                        16_000,
                        11_200,
                        11_200,
                        9_000,
                        8_000,
                        false,
                        List.of(),
                        List.of(),
                       Map.of("resolved.submission", 24, "retrieval.history", 16),
                       List.of()
               )
       );
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

    private Map<String, String> events(String body) {
        Map<String, String> events = new LinkedHashMap<>();
        for (String block : body.replace("\r\n", "\n").split("\n\n")) {
            String event = "message";
            List<String> data = new ArrayList<>();
            for (String line : block.split("\n")) {
                if (line.startsWith("event: ")) {
                    event = line.substring("event: ".length());
                } else if (line.startsWith("data: ")) {
                    data.add(line.substring("data: ".length()));
                }
            }
            if (!data.isEmpty()) {
                events.put(event, String.join("\n", data));
            }
        }
        return events;
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
