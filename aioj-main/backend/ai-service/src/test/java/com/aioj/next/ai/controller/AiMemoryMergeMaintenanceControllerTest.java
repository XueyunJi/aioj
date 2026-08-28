package com.aioj.next.ai.controller;

import com.aioj.next.ai.domain.memory.AiMemoryMergeService;
import com.aioj.next.common.security.Role;
import com.aioj.next.common.security.SecurityPrincipal;
import com.aioj.next.contract.ai.AiMemoryMergeMaintenanceRequest;
import com.aioj.next.contract.ai.AiMemoryMergeMaintenanceResponse;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

import java.util.List;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@ExtendWith(MockitoExtension.class)
class AiMemoryMergeMaintenanceControllerTest {
    @Mock
    private AiMemoryMergeService mergeService;

    private MockMvc mockMvc;

    @BeforeEach
    void setUp() {
        mockMvc = MockMvcBuilders.standaloneSetup(new AiMemoryMergeMaintenanceController(mergeService)).build();
        SecurityPrincipal principal = new SecurityPrincipal(99L, "admin", Set.of(Role.ADMIN));
        SecurityContextHolder.getContext().setAuthentication(new UsernamePasswordAuthenticationToken(
                principal,
                null,
                List.of(new SimpleGrantedAuthority("ROLE_ADMIN"))
        ));
    }

    @AfterEach
    void tearDown() {
        SecurityContextHolder.clearContext();
    }

    @Test
    void controllerIsAdminOnly() {
        PreAuthorize annotation = AiMemoryMergeMaintenanceController.class.getAnnotation(PreAuthorize.class);
        assertThat(annotation).isNotNull();
        assertThat(annotation.value()).isEqualTo("hasRole('ADMIN')");
    }

    @Test
    void enqueueMaintenanceReturnsSafeCounts() throws Exception {
        when(mergeService.enqueueMaintenance(eq(99L), any(AiMemoryMergeMaintenanceRequest.class)))
                .thenReturn(new AiMemoryMergeMaintenanceResponse(99L, 5, 1, 1, List.of(100L), List.of(200L)));

        mockMvc.perform(post("/ai/admin/memory-merge-maintenance")
                        .contentType("application/json")
                        .content("""
                                {"category":"preference","limit":20}
                                """))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.targetUserId").value(99))
                .andExpect(jsonPath("$.data.scannedMemories").value(5))
                .andExpect(jsonPath("$.data.relatedGroups").value(1))
                .andExpect(jsonPath("$.data.queuedJobs").value(1))
                .andExpect(jsonPath("$.data.candidateIds[0]").value(100));

        verify(mergeService).enqueueMaintenance(eq(99L), any(AiMemoryMergeMaintenanceRequest.class));
    }
}
