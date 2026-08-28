package com.aioj.next.ai.controller;

import com.aioj.next.ai.domain.ProblemDraftGenerationJobService;
import com.aioj.next.common.api.PageResponse;
import com.aioj.next.common.error.GlobalExceptionHandler;
import com.aioj.next.common.security.Role;
import com.aioj.next.common.security.SecurityPrincipal;
import com.aioj.next.contract.ai.ProblemDraftGenerationJobResponse;
import com.aioj.next.contract.ai.ProblemDraftRegenerateRequest;
import com.aioj.next.contract.ai.ProblemDraftRequest;
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

import java.time.Instant;
import java.util.List;
import java.util.Set;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@ExtendWith(MockitoExtension.class)
class AiProblemDraftGenerationJobControllerTest {
    private static final Long USER_ID = 7L;

    @Mock
    private ProblemDraftGenerationJobService jobService;

    private MockMvc mockMvc;

    @BeforeEach
    void setUp() {
        AiController controller = new AiController(
                null,
                null,
                null,
                null,
                null,
                null,
                null,
                null,
                null,
                null,
                null,
                null,
                null,
                new ObjectMapper().findAndRegisterModules()
        );
        controller.setProblemDraftGenerationJobService(jobService);
        mockMvc = MockMvcBuilders.standaloneSetup(controller)
                .setControllerAdvice(new GlobalExceptionHandler())
                .build();
        authenticate(USER_ID, Role.TEACHER);
    }

    @AfterEach
    void tearDown() {
        SecurityContextHolder.clearContext();
    }

    @Test
    void createProblemDraftGenerationJobUsesCurrentUser() throws Exception {
        when(jobService.create(eq(USER_ID), any(ProblemDraftRequest.class))).thenReturn(job(100L, "QUEUED", null));

        mockMvc.perform(post("/ai/problem-drafts/generation-jobs")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"topic":"线段树,排序","cfRating":2000,"standardSolutionLanguage":"cpp","enableAutoRepair":true}
                                """))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.id").value(100))
                .andExpect(jsonPath("$.data.status").value("QUEUED"));

        verify(jobService).create(eq(USER_ID), any(ProblemDraftRequest.class));
    }

    @Test
    void listProblemDraftGenerationJobsDelegatesFilters() throws Exception {
        when(jobService.list(1L, 20L, "RUNNING", null)).thenReturn(new PageResponse<>(List.of(job(101L, "RUNNING", null)), 1, 1, 20));

        mockMvc.perform(get("/admin/problem-draft-generation-jobs")
                        .param("status", "RUNNING"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.records[0].id").value(101))
                .andExpect(jsonPath("$.data.total").value(1));

        verify(jobService).list(1L, 20L, "RUNNING", null);
    }

    @Test
    void getProblemDraftGenerationJobDelegatesById() throws Exception {
        when(jobService.get(102L)).thenReturn(job(102L, "SUCCEEDED", 900L));

        mockMvc.perform(get("/admin/problem-draft-generation-jobs/102"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.id").value(102))
                .andExpect(jsonPath("$.data.draftId").value(900));

        verify(jobService).get(102L);
    }

    @Test
    void createProblemDraftRegenerationJobUsesCurrentUserAndSourceDraft() throws Exception {
        when(jobService.createRegeneration(eq(USER_ID), eq(200L), any(ProblemDraftRegenerateRequest.class)))
                .thenReturn(regenerationJob(103L, "QUEUED", 200L, null));

        mockMvc.perform(post("/admin/problem-drafts/200/regeneration-job")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"feedback":"只重新跑验证，不要修改题目主体"}
                                """))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.id").value(103))
                .andExpect(jsonPath("$.data.jobType").value("REGENERATE"))
                .andExpect(jsonPath("$.data.sourceDraftId").value(200))
                .andExpect(jsonPath("$.data.status").value("QUEUED"));

        verify(jobService).createRegeneration(eq(USER_ID), eq(200L), any(ProblemDraftRegenerateRequest.class));
    }

    private static ProblemDraftGenerationJobResponse job(Long id, String status, Long draftId) {
        return job(id, "GENERATE", null, status, draftId);
    }

    private static ProblemDraftGenerationJobResponse regenerationJob(Long id, String status, Long sourceDraftId, Long draftId) {
        return job(id, "REGENERATE", sourceDraftId, status, draftId);
    }

    private static ProblemDraftGenerationJobResponse job(Long id, String jobType, Long sourceDraftId, String status, Long draftId) {
        Instant now = Instant.now();
        return new ProblemDraftGenerationJobResponse(
                id,
                USER_ID,
                jobType,
                sourceDraftId,
                status,
                status,
                "线段树,排序",
                "SUCCEEDED".equals(status) ? 6 : 1,
                6,
                "progress",
                draftId,
                null,
                null,
                null,
                now,
                draftId == null ? null : now,
                now,
                now
        );
    }

    private static void authenticate(Long userId, Role role) {
        SecurityPrincipal principal = new SecurityPrincipal(userId, "tester", Set.of(role));
        SecurityContextHolder.getContext().setAuthentication(new UsernamePasswordAuthenticationToken(
                principal,
                null,
                List.of(new SimpleGrantedAuthority("ROLE_" + role.name()))
        ));
    }
}
