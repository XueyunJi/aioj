package com.aioj.next.ai.controller;

import com.aioj.next.ai.domain.memory.AiMemoryCandidateService;
import com.aioj.next.ai.domain.memory.AiMemoryReviewService;
import com.aioj.next.common.security.Role;
import com.aioj.next.common.security.SecurityPrincipal;
import com.aioj.next.contract.ai.AiMemoryReviewDetailResponse;
import com.aioj.next.contract.ai.AiMemoryReviewListItemResponse;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import java.util.Set;

import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@ExtendWith(MockitoExtension.class)
class AiMemoryCandidateControllerTest {
    @Mock
    private AiMemoryCandidateService candidateService;
    @Mock
    private AiMemoryReviewService reviewService;

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
                candidateService,
                null,
                reviewService,
                null,
                null,
                new ObjectMapper().findAndRegisterModules()
        );
        mockMvc = MockMvcBuilders.standaloneSetup(controller).build();
        SecurityPrincipal principal = new SecurityPrincipal(7L, "student", Set.of(Role.STUDENT));
        SecurityContextHolder.getContext().setAuthentication(new UsernamePasswordAuthenticationToken(
                principal,
                null,
                List.of(new SimpleGrantedAuthority("ROLE_STUDENT"))
        ));
    }

    @AfterEach
    void tearDown() {
        SecurityContextHolder.clearContext();
    }

    @Test
    void detailUsesCurrentUserScope() throws Exception {
        when(reviewService.detailForUser(7L, 100L)).thenReturn(new AiMemoryReviewDetailResponse(item(), List.of(), List.of(), List.of(), List.of("APPROVE")));

        mockMvc.perform(get("/ai/memory-candidates/100"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.candidate.id").value(100))
                .andExpect(jsonPath("$.data.candidate.userId").value(7))
                .andExpect(jsonPath("$.data.candidate.canonicalText").value("用户偏好先给提示。"));

        verify(reviewService).detailForUser(7L, 100L);
    }

    private static AiMemoryReviewListItemResponse item() {
        return new AiMemoryReviewListItemResponse(
                100L,
                7L,
                "PREFERENCE",
                "guidance_preference",
                "用户偏好先给提示。",
                "GLOBAL",
                null,
                "USER_ACCEPTED",
                BigDecimal.valueOf(0.88),
                BigDecimal.valueOf(0.88),
                true,
                List.of(),
                List.of(),
                "NEEDS_CONFIRMATION",
                null,
                null,
                null,
                Instant.now(),
                Instant.now()
        );
    }
}
