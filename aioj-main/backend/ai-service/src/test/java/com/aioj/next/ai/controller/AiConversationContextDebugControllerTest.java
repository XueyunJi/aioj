package com.aioj.next.ai.controller;

import com.aioj.next.ai.domain.AiContextService;
import com.aioj.next.ai.domain.AiConversationService;
import com.aioj.next.ai.domain.AiLearningProfileService;
import com.aioj.next.ai.domain.AiMemoryService;
import com.aioj.next.ai.domain.AiProvider;
import com.aioj.next.ai.domain.AiQuotaService;
import com.aioj.next.ai.domain.AccountImportParseService;
import com.aioj.next.ai.domain.ProblemDraftStore;
import com.aioj.next.ai.domain.memory.AiMemoryCandidateService;
import com.aioj.next.ai.domain.memory.AiMemoryDebugService;
import com.aioj.next.ai.domain.clarification.ClarificationSchemaRepairer;
import com.aioj.next.ai.domain.response.AiAssistantResponseNormalizer;
import com.aioj.next.common.error.DomainException;
import com.aioj.next.common.error.ErrorCode;
import com.aioj.next.common.error.GlobalExceptionHandler;
import com.aioj.next.common.security.Role;
import com.aioj.next.common.security.SecurityPrincipal;
import com.aioj.next.contract.ai.AiConversationContextDebugResponse;
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
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@ExtendWith(MockitoExtension.class)
class AiConversationContextDebugControllerTest {
    private static final Long USER_ID = 7L;

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
    private AiMemoryCandidateService aiMemoryCandidateService;
    @Mock
    private AiMemoryDebugService aiMemoryDebugService;
    @Mock
    private ProblemDraftStore problemDraftStore;

    private MockMvc mockMvc;

    @BeforeEach
    void setUp() {
        AiController controller = new AiController(
                aiProvider,
                null,
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
                new AiAssistantResponseNormalizer(new ObjectMapper().findAndRegisterModules(), new ClarificationSchemaRepairer()),
                new ObjectMapper().findAndRegisterModules()
        );
        mockMvc = MockMvcBuilders.standaloneSetup(controller)
                .setControllerAdvice(new GlobalExceptionHandler())
                .build();
        authenticate(USER_ID);
    }

    @AfterEach
    void tearDown() {
        SecurityContextHolder.clearContext();
    }

    @Test
    void contextDebugReturnsCurrentUsersConversationOnly() throws Exception {
        AiConversationContextDebugResponse.ContextSection section = new AiConversationContextDebugResponse.ContextSection(
                "debug.state",
                "conversation_state",
                "Current conversation state",
                90,
                "ai-service.state",
                "internal",
                8,
                true,
                "星港建设",
                Map.of(
                        "problemId", "99",
                        "hits", List.of(Map.of(
                                "ownerType", "submission_analysis",
                                "score", 4.2,
                                "reasons", List.of("same_submission")
                        ))
                )
        );
        AiConversationContextDebugResponse.ContextBuildReport report = new AiConversationContextDebugResponse.ContextBuildReport(
                List.of(section),
                Map.of("ai-service.state", 1),
                8,
                8,
                0,
                1,
                0,
                new AiConversationContextDebugResponse.ContextBudgetReport(
                        "mock-16k",
                        16_000,
                        11_200,
                        11_200,
                        9_000,
                        8_000,
                        true,
                        List.of("retrieval.history"),
                        List.of(),
                        Map.of("conversation.state", 8),
                        List.of()
                )
        );
        AiConversationContextDebugResponse response = new AiConversationContextDebugResponse(
                "c-owned",
                String.valueOf(USER_ID),
                Map.of("problem", Map.of("title", "星港建设")),
                List.of(),
                List.of(),
                List.of(),
                List.of(),
                "[Current Conversation State]\n星港建设",
                List.of(section),
                Map.of("ai-service.state", 1),
                report,
                new AiConversationContextDebugResponse.TokenEstimate(0, 8, 0, 0, 8),
                List.of()
        );
        when(aiContextService.contextDebug(USER_ID, "c-owned")).thenReturn(response);

        mockMvc.perform(get("/ai/conversations/c-owned/context-debug"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(0))
                .andExpect(jsonPath("$.data.conversationId").value("c-owned"))
                .andExpect(jsonPath("$.data.userId").value(String.valueOf(USER_ID)))
                .andExpect(jsonPath("$.data.contextPackPreview").value("[Current Conversation State]\n星港建设"))
                .andExpect(jsonPath("$.data.sections[0].type").value("conversation_state"))
                .andExpect(jsonPath("$.data.sections[0].metadata.hits[0].ownerType").value("submission_analysis"))
                .andExpect(jsonPath("$.data.sourceSummary['ai-service.state']").value(1))
                .andExpect(jsonPath("$.data.contextBuildReport.requiredSectionCount").value(1))
                .andExpect(jsonPath("$.data.contextBuildReport.budget.model").value("mock-16k"))
                .andExpect(jsonPath("$.data.contextBuildReport.budget.compressionThresholdTokens").value(11200))
                .andExpect(jsonPath("$.data.contextBuildReport.budget.compressionApplied").value(true));

        verify(aiConversationService).ensureOwner("c-owned", USER_ID);
    }

    @Test
    void contextDebugRejectsCrossUserConversationWithForbiddenApiResponse() throws Exception {
        doThrow(new DomainException(ErrorCode.FORBIDDEN, "AI conversation belongs to another user"))
                .when(aiConversationService)
                .ensureOwner("c-other-user", USER_ID);

        mockMvc.perform(get("/ai/conversations/c-other-user/context-debug"))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.code").value(ErrorCode.FORBIDDEN.code()))
                .andExpect(jsonPath("$.message").value("当前账号没有权限执行该操作。"))
                .andExpect(jsonPath("$.errorKey").value("auth.forbidden"));
    }

    @Test
    void contextDebugMissingConversationReturnsNotFoundApiResponse() throws Exception {
        doThrow(new DomainException(ErrorCode.NOT_FOUND, "AI conversation not found"))
                .when(aiConversationService)
                .ensureOwner("missing", USER_ID);

        mockMvc.perform(get("/ai/conversations/missing/context-debug"))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value(ErrorCode.NOT_FOUND.code()))
                .andExpect(jsonPath("$.message").value("请求的资源不存在或已被删除。"))
                .andExpect(jsonPath("$.errorKey").value("resource.notFound"));
    }

    @Test
    void learningProfileListReturnsEmptyPayload() throws Exception {
        when(aiLearningProfileService.list(USER_ID, null, null)).thenReturn(List.of());

        mockMvc.perform(get("/ai/learning-profile"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(0))
                .andExpect(jsonPath("$.data").isArray())
                .andExpect(jsonPath("$.data").isEmpty());
    }

    @Test
    void learningProfileEvidenceMissingOrCrossUserProfileReturnsNotFound() throws Exception {
        doThrow(new DomainException(ErrorCode.NOT_FOUND, "Learning profile not found"))
                .when(aiLearningProfileService)
                .evidence(USER_ID, 999L);

        mockMvc.perform(get("/ai/learning-profile/999/evidence"))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value(ErrorCode.NOT_FOUND.code()))
                .andExpect(jsonPath("$.errorKey").value("resource.notFound"));
    }

    @Test
    void learningProfilePatchInvalidStateReturnsBadRequest() throws Exception {
        doThrow(new DomainException(ErrorCode.BAD_REQUEST, "Unsupported learning profile state"))
                .when(aiLearningProfileService)
                .update(eq(USER_ID), eq(10L), any());

        mockMvc.perform(patch("/ai/learning-profile/10")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"state\":\"BROKEN\"}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value(ErrorCode.BAD_REQUEST.code()))
                .andExpect(jsonPath("$.errorKey").value("request.unsupported"));
    }

    @Test
    void learningProfileDeleteSoftDeletesCurrentUsersProfile() throws Exception {
        mockMvc.perform(delete("/ai/learning-profile/10"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(0));

        verify(aiLearningProfileService).delete(USER_ID, 10L);
    }

    @Test
    void learningProfileDeleteMissingOrCrossUserProfileReturnsNotFound() throws Exception {
        doThrow(new DomainException(ErrorCode.NOT_FOUND, "Learning profile not found"))
                .when(aiLearningProfileService)
                .delete(USER_ID, 999L);

        mockMvc.perform(delete("/ai/learning-profile/999"))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value(ErrorCode.NOT_FOUND.code()))
                .andExpect(jsonPath("$.errorKey").value("resource.notFound"));
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
