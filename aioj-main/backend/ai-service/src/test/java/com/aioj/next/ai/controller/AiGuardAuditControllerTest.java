package com.aioj.next.ai.controller;

import com.aioj.next.ai.agent.policy.GuardDecisionAuditService;
import com.aioj.next.ai.domain.response.GuardDecisionAuditItem;
import com.aioj.next.ai.domain.response.GuardTurnMessagesResponse;
import com.aioj.next.common.api.PageResponse;
import com.aioj.next.common.error.DomainException;
import com.aioj.next.common.error.ErrorCode;
import com.aioj.next.common.error.GlobalExceptionHandler;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

import java.time.Instant;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@ExtendWith(MockitoExtension.class)
class AiGuardAuditControllerTest {

    @Mock
    private GuardDecisionAuditService auditService;

    private MockMvc mockMvc;

    @BeforeEach
    void setUp() {
        mockMvc = MockMvcBuilders.standaloneSetup(new AiGuardAuditController(auditService))
                .setControllerAdvice(new GlobalExceptionHandler())
                .build();
    }

    @Test
    void controllerIsTeacherOrAdminOnly() {
        PreAuthorize annotation = AiGuardAuditController.class.getAnnotation(PreAuthorize.class);
        assertThat(annotation).isNotNull();
        assertThat(annotation.value()).isEqualTo("hasAnyRole('TEACHER','ADMIN')");
    }

    @Test
    void listPassesFiltersAndReturnsPage() throws Exception {
        GuardDecisionAuditItem item = new GuardDecisionAuditItem(
                "990011223344556677", "t-1", 7L, "c-1", 7701L,
                "L4_OUTPUT", "REFUSE", "private_contest_problem",
                new ObjectMapper().readTree("[{\"problemId\":1001,\"contestRunId\":7701}]"),
                new ObjectMapper().readTree("{\"similarity\":0.91}"),
                true, 12, Instant.parse("2026-08-08T10:00:00Z"));
        when(auditService.list(7701L, 7L, "L4_OUTPUT", "REFUSE", true,
                "2026-08-01T00:00:00Z", "2026-08-09T00:00:00Z", 2L, 50L))
                .thenReturn(new PageResponse<>(List.of(item), 101, 2, 50));

        mockMvc.perform(get("/admin/ai-guard-decisions")
                        .param("contestRunId", "7701")
                        .param("userId", "7")
                        .param("layer", "L4_OUTPUT")
                        .param("decision", "REFUSE")
                        .param("degraded", "true")
                        .param("from", "2026-08-01T00:00:00Z")
                        .param("to", "2026-08-09T00:00:00Z")
                        .param("page", "2")
                        .param("pageSize", "50"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(0))
                .andExpect(jsonPath("$.data.total").value(101))
                .andExpect(jsonPath("$.data.page").value(2))
                .andExpect(jsonPath("$.data.pageSize").value(50))
                .andExpect(jsonPath("$.data.records[0].id").value("990011223344556677"))
                .andExpect(jsonPath("$.data.records[0].contestRunId").value(7701))
                .andExpect(jsonPath("$.data.records[0].matchedProblemRefs[0].contestRunId").value(7701))
                .andExpect(jsonPath("$.data.records[0].detail.similarity").value(0.91));

        verify(auditService).list(7701L, 7L, "L4_OUTPUT", "REFUSE", true,
                "2026-08-01T00:00:00Z", "2026-08-09T00:00:00Z", 2L, 50L);
    }

    @Test
    void listUsesDefaultPaging() throws Exception {
        when(auditService.list(null, null, null, null, null, null, null, 1L, 20L))
                .thenReturn(new PageResponse<>(List.of(), 0, 1, 20));

        mockMvc.perform(get("/admin/ai-guard-decisions"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.total").value(0));

        verify(auditService).list(null, null, null, null, null, null, null, 1L, 20L);
    }

    @Test
    void listMapsBadRequestTo400() throws Exception {
        when(auditService.list(null, null, "L9_NOPE", null, null, null, null, 1L, 20L))
                .thenThrow(new DomainException(ErrorCode.BAD_REQUEST, "Invalid layer: L9_NOPE"));

        mockMvc.perform(get("/admin/ai-guard-decisions").param("layer", "L9_NOPE"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value(40000));
    }

    @Test
    void turnMessagesReturnsTurnAndMessages() throws Exception {
        GuardTurnMessagesResponse response = new GuardTurnMessagesResponse(
                "t-1", "c-1", 7L, "COMPLETED", Instant.parse("2026-08-08T10:00:00Z"),
                new GuardTurnMessagesResponse.GuardTurnMessage(
                        "1001", "user", "这题怎么做", null, Instant.parse("2026-08-08T10:00:00Z")),
                new GuardTurnMessagesResponse.GuardTurnMessage(
                        "1002", "assistant", "先读题面。", "deepseek-chat", Instant.parse("2026-08-08T10:00:05Z")));
        when(auditService.turnMessages("t-1")).thenReturn(response);

        mockMvc.perform(get("/admin/ai-guard-decisions/turns/t-1/messages"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.turnId").value("t-1"))
                .andExpect(jsonPath("$.data.userId").value(7))
                .andExpect(jsonPath("$.data.userMessage.id").value("1001"))
                .andExpect(jsonPath("$.data.userMessage.content").value("这题怎么做"))
                .andExpect(jsonPath("$.data.assistantMessage.model").value("deepseek-chat"));
    }

    @Test
    void turnMessagesMapsNotFoundTo404() throws Exception {
        when(auditService.turnMessages("ghost"))
                .thenThrow(new DomainException(ErrorCode.NOT_FOUND, "AI turn not found"));

        mockMvc.perform(get("/admin/ai-guard-decisions/turns/ghost/messages"))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value(40400));
    }
}
