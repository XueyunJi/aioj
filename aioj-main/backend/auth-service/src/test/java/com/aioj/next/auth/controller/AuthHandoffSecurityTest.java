package com.aioj.next.auth.controller;

import com.aioj.next.auth.config.AuthProperties;
import com.aioj.next.auth.config.SecurityConfig;
import com.aioj.next.auth.domain.AuthHandoffService;
import com.aioj.next.common.security.JwtProperties;
import com.aioj.next.common.security.JwtTokenService;
import com.aioj.next.common.security.Role;
import com.aioj.next.contract.auth.HandoffExchangeResponse;
import com.aioj.next.contract.auth.HandoffTicketIssueResponse;
import com.aioj.next.contract.auth.TokenResponse;
import org.junit.jupiter.api.Test;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.beans.factory.annotation.Autowired;

import java.time.Instant;
import java.util.Set;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@WebMvcTest(controllers = AuthHandoffController.class)
@Import({SecurityConfig.class, AuthHandoffSecurityTest.PropertiesConfiguration.class})
@TestPropertySource(properties = "aioj.security.jwt.hmac-secret=auth-handoff-security-test-secret-0123456789")
class AuthHandoffSecurityTest {
    @Autowired
    private MockMvc mockMvc;
    @Autowired
    private JwtTokenService jwtTokenService;
    @MockBean
    private AuthHandoffService authHandoffService;

    @Test
    void exchangeIsAnonymous() throws Exception {
        when(authHandoffService.exchange("ticket")).thenReturn(new HandoffExchangeResponse(tokens(), "/problems/123"));

        mockMvc.perform(post("/auth/handoff/exchange")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"ticket\":\"ticket\"}"))
                .andExpect(status().isOk());

        verify(authHandoffService).exchange("ticket");
    }

    @Test
    void issueRequiresAccessToken() throws Exception {
        mockMvc.perform(post("/auth/handoff-tickets")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"audience\":\"web-user\",\"nextPath\":\"/problems/123\"}"))
                .andExpect(status().isUnauthorized());

        verify(authHandoffService, never()).issue(any(), any());
    }

    @Test
    void issueAcceptsAccessToken() throws Exception {
        String accessToken = jwtTokenService.createAccessToken(7L, "student", Set.of(Role.STUDENT), false);
        when(authHandoffService.issue(eq(7L), any())).thenReturn(
                new HandoffTicketIssueResponse("opaque-ticket", Instant.now().plusSeconds(60)));

        mockMvc.perform(post("/auth/handoff-tickets")
                        .header("Authorization", "Bearer " + accessToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"audience\":\"web-user\",\"nextPath\":\"/problems/123\"}"))
                .andExpect(status().isOk());

        verify(authHandoffService).issue(eq(7L), any());
    }

    @Test
    void refreshTokenCannotIssueHandoffTicket() throws Exception {
        String refreshToken = jwtTokenService.createRefreshToken(7L, "student", Set.of(Role.STUDENT), false);

        mockMvc.perform(post("/auth/handoff-tickets")
                        .header("Authorization", "Bearer " + refreshToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"audience\":\"web-user\",\"nextPath\":\"/problems/123\"}"))
                .andExpect(status().isUnauthorized());

        verify(authHandoffService, never()).issue(any(), any());
    }

    private TokenResponse tokens() {
        return new TokenResponse("access", "refresh", "Bearer", Instant.now().plusSeconds(60),
                1L, "student", "Student", Set.of(Role.STUDENT), false);
    }

    @SpringBootApplication(scanBasePackages = "com.aioj.next.auth.controller")
    @EnableConfigurationProperties({JwtProperties.class, AuthProperties.class})
    static class PropertiesConfiguration {
    }
}
