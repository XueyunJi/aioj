package com.aioj.next.ai.controller;

import com.aioj.next.ai.domain.memory.AiMemoryMarkdownArchiveService;
import com.aioj.next.common.security.Role;
import com.aioj.next.common.security.SecurityPrincipal;
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

import java.util.List;
import java.util.Set;

import static org.hamcrest.Matchers.containsString;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@ExtendWith(MockitoExtension.class)
class AiMemoryArchiveControllerTest {
    @Mock
    private AiMemoryMarkdownArchiveService archiveService;

    private MockMvc mockMvc;

    @BeforeEach
    void setUp() {
        mockMvc = MockMvcBuilders.standaloneSetup(new AiMemoryArchiveController(archiveService)).build();
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
    void exportMarkdownArchiveReturnsAttachment() throws Exception {
        when(archiveService.export(7L)).thenReturn(new AiMemoryMarkdownArchiveService.MarkdownArchive(
                "aioj-learning-archive-20260625-111111.md",
                "# AI-OJ AI 学习档案\n\n安全摘要",
                new AiMemoryMarkdownArchiveService.ArchiveCounts(0, 0, 0, 0, 0, 0, 0)
        ));

        mockMvc.perform(get("/ai/memories/export/markdown"))
                .andExpect(status().isOk())
                .andExpect(header().string("Content-Type", containsString("text/markdown")))
                .andExpect(header().string("Content-Disposition", containsString("attachment")))
                .andExpect(header().string("Content-Disposition", containsString("aioj-learning-archive-20260625-111111.md")))
                .andExpect(content().string(containsString("AI-OJ AI 学习档案")));

        verify(archiveService).export(7L);
    }
}
