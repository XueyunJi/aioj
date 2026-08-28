package com.aioj.next.ai.controller;

import com.aioj.next.ai.domain.memory.AiMemoryObservabilityService;
import com.aioj.next.contract.ai.AiMemoryObservabilityMetricResponse;
import com.aioj.next.contract.ai.AiMemoryObservabilityResponse;
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
class AiMemoryObservabilityControllerTest {
    @Mock
    private AiMemoryObservabilityService observabilityService;

    private MockMvc mockMvc;

    @BeforeEach
    void setUp() {
        mockMvc = MockMvcBuilders.standaloneSetup(new AiMemoryObservabilityController(observabilityService)).build();
    }

    @Test
    void controllerIsAdminOnly() {
        PreAuthorize annotation = AiMemoryObservabilityController.class.getAnnotation(PreAuthorize.class);
        assertThat(annotation).isNotNull();
        assertThat(annotation.value()).isEqualTo("hasRole('ADMIN')");
    }

    @Test
    void summaryReturnsPipelineHealth() throws Exception {
        when(observabilityService.summary()).thenReturn(new AiMemoryObservabilityResponse(
                Instant.parse("2026-06-25T00:00:00Z"),
                List.of(new AiMemoryObservabilityMetricResponse("QUEUED", 3)),
                List.of(new AiMemoryObservabilityMetricResponse("AI_AFTER_TURN_MEMORY_PROFILE", 2)),
                List.of(new AiMemoryObservabilityMetricResponse("AI_CHAT_TURN_COMPLETED", 4)),
                1,
                0,
                List.of(),
                3,
                0.0,
                0,
                0,
                0
        ));

        mockMvc.perform(get("/ai/admin/memory-observability"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.dueJobCount").value(1))
                .andExpect(jsonPath("$.data.jobsByStatus[0].key").value("QUEUED"))
                .andExpect(jsonPath("$.data.eventsByType[0].count").value(4))
                .andExpect(jsonPath("$.data.totalJobCount").value(3))
                .andExpect(jsonPath("$.data.embeddingFailureCount").value(0));

        verify(observabilityService).summary();
    }
}
