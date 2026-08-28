package com.aioj.next.ai.controller;

import com.aioj.next.ai.agent.AgentChatFacade;
import com.aioj.next.ai.domain.AccountImportParseService;
import com.aioj.next.ai.domain.AiContextService;
import com.aioj.next.ai.domain.AiConversationService;
import com.aioj.next.ai.domain.AiLearningProfileService;
import com.aioj.next.ai.domain.AiMemoryService;
import com.aioj.next.ai.domain.AiProvider;
import com.aioj.next.ai.domain.AiQuotaService;
import com.aioj.next.ai.domain.ProblemDraftStore;
import com.aioj.next.ai.domain.memory.AiMemoryCandidateService;
import com.aioj.next.ai.domain.memory.AiMemoryDebugService;
import com.aioj.next.ai.domain.memory.AiMemoryReviewService;
import com.aioj.next.ai.domain.response.AiAssistantResponseNormalizer;
import com.aioj.next.common.error.DomainException;
import com.aioj.next.common.error.ErrorCode;
import com.aioj.next.common.error.GlobalExceptionHandler;
import com.aioj.next.common.security.Role;
import com.aioj.next.common.security.SecurityPrincipal;
import com.aioj.next.contract.ai.ProblemDraftRequest;
import com.aioj.next.contract.ai.ProblemDraftResponse;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
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
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.asyncDispatch;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.request;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@ExtendWith(MockitoExtension.class)
class AiProblemDraftStreamControllerTest {
    private static final Long USER_ID = 7L;

    @Mock
    private AiProvider aiProvider;
    @Mock
    private AgentChatFacade agentChatFacade;
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
    private AiMemoryCandidateService aiMemoryCandidateService;
    @Mock
    private AiMemoryDebugService aiMemoryDebugService;
    @Mock
    private AiMemoryReviewService aiMemoryReviewService;
    @Mock
    private ProblemDraftStore problemDraftStore;
    @Mock
    private AiAssistantResponseNormalizer responseNormalizer;

    private final ObjectMapper objectMapper = new ObjectMapper().findAndRegisterModules();
    private ExecutorService executor;
    private MockMvc mockMvc;

    @BeforeEach
    void setUp() {
        executor = Executors.newSingleThreadExecutor();
        AiController controller = new AiController(
                aiProvider,
                agentChatFacade,
                aiQuotaService,
                accountImportParseService,
                aiConversationService,
                aiContextService,
                aiMemoryService,
                aiLearningProfileService,
                aiMemoryCandidateService,
                aiMemoryDebugService,
                aiMemoryReviewService,
                problemDraftStore,
                responseNormalizer,
                objectMapper,
                executor
        );
        mockMvc = MockMvcBuilders.standaloneSetup(controller)
                .setControllerAdvice(new GlobalExceptionHandler())
                .build();
        authenticate(USER_ID);
    }

    @AfterEach
    void tearDown() {
        SecurityContextHolder.clearContext();
        executor.shutdownNow();
    }

    @Test
    void generateDraftStreamSendsHeartbeatBeforeDraft() throws Exception {
        ProblemDraftResponse draft = new ProblemDraftResponse(
                42L,
                "PENDING_REVIEW",
                "Stream Draft",
                "MEDIUM",
                "Solve the task.",
                "notes",
                "cpp",
                "int main(){return 0;}",
                "print('ok')",
                "plan",
                List.of("线段树"),
                "VALID",
                List.of(),
                List.of(),
                1000,
                262144,
                null,
                "mock",
                1,
                2,
                Instant.now(),
                null,
                null,
                null,
                null,
                null
        );
        when(problemDraftStore.generate(eq(USER_ID), any(ProblemDraftRequest.class))).thenAnswer(invocation -> {
            Thread.sleep(1_200L);
            return draft;
        });

        MvcResult started = mockMvc.perform(post("/ai/problem-drafts/generate/stream")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"topic":"线段树,排序","cfRating":2000,"standardSolutionLanguage":"cpp","enableAutoRepair":true}
                                """))
                .andExpect(request().asyncStarted())
                .andReturn();
        MvcResult completed = mockMvc.perform(asyncDispatch(started))
                .andExpect(status().isOk())
                .andReturn();

        String body = completed.getResponse().getContentAsString(StandardCharsets.UTF_8);
        assertThat(body).contains("event: meta");
        assertThat(body).contains("event: heartbeat");
        assertThat(body).contains("event: draft");
        assertThat(body).contains("\"title\":\"Stream Draft\"");
        assertThat(body).contains("event: done");
    }

    @Test
    void generateDraftStreamSendsStructuredError() throws Exception {
        when(problemDraftStore.generate(eq(USER_ID), any(ProblemDraftRequest.class)))
                .thenThrow(new DomainException(ErrorCode.TOO_MANY_REQUESTS, "AI service is busy; please try again later"));

        MvcResult started = mockMvc.perform(post("/ai/problem-drafts/generate/stream")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"topic":"线段树,排序","cfRating":2000,"standardSolutionLanguage":"cpp","enableAutoRepair":true}
                                """))
                .andExpect(request().asyncStarted())
                .andReturn();
        MvcResult completed = mockMvc.perform(asyncDispatch(started))
                .andExpect(status().isOk())
                .andReturn();

        String body = completed.getResponse().getContentAsString(StandardCharsets.UTF_8);
        assertThat(body).contains("event: error");
        assertThat(body).contains("\"code\":42900");
        assertThat(body).contains("\"message\":\"AI service is busy; please try again later\"");
        assertThat(body).contains("\"errorKey\":\"request.tooMany\"");
        assertThat(body).contains("\"elapsedMillis\":");
        assertThat(body).contains("event: done");
    }

    @Test
    void generateDraftStreamHeartbeatElapsedMillisIncreases() throws Exception {
        ProblemDraftResponse draft = new ProblemDraftResponse(
                43L,
                "PENDING_REVIEW",
                "Slow Stream Draft",
                "MEDIUM",
                "Solve the task.",
                "notes",
                "cpp",
                "int main(){return 0;}",
                "print('ok')",
                "plan",
                List.of("线段树"),
                "VALID",
                List.of(),
                List.of(),
                1000,
                262144,
                null,
                "mock",
                1,
                2,
                Instant.now(),
                null,
                null,
                null,
                null,
                null
        );
        when(problemDraftStore.generate(eq(USER_ID), any(ProblemDraftRequest.class))).thenAnswer(invocation -> {
            Thread.sleep(2_200L);
            return draft;
        });

        MvcResult started = mockMvc.perform(post("/ai/problem-drafts/generate/stream")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"topic":"线段树,排序","cfRating":2000,"standardSolutionLanguage":"cpp","enableAutoRepair":true}
                                """))
                .andExpect(request().asyncStarted())
                .andReturn();
        MvcResult completed = mockMvc.perform(asyncDispatch(started))
                .andExpect(status().isOk())
                .andReturn();

        List<Long> elapsedMillis = heartbeatElapsedMillis(completed.getResponse().getContentAsString(StandardCharsets.UTF_8));
        assertThat(elapsedMillis).hasSizeGreaterThanOrEqualTo(2);
        assertThat(elapsedMillis.get(elapsedMillis.size() - 1)).isGreaterThan(elapsedMillis.get(0));
    }

    private List<Long> heartbeatElapsedMillis(String body) {
        Matcher matcher = Pattern.compile("\"elapsedMillis\":(\\d+)").matcher(body);
        List<Long> values = new ArrayList<>();
        while (matcher.find()) {
            values.add(Long.parseLong(matcher.group(1)));
        }
        return values;
    }

    private void authenticate(Long userId) {
        SecurityPrincipal principal = new SecurityPrincipal(userId, "elvis", Set.of(Role.TEACHER));
        UsernamePasswordAuthenticationToken authentication = new UsernamePasswordAuthenticationToken(
                principal,
                "n/a",
                List.of(new SimpleGrantedAuthority("ROLE_TEACHER"))
        );
        SecurityContextHolder.getContext().setAuthentication(authentication);
    }
}
