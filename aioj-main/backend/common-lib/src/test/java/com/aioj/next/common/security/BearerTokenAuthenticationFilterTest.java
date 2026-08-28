package com.aioj.next.common.security;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockFilterChain;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.security.core.context.SecurityContextHolder;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;

class BearerTokenAuthenticationFilterTest {
    private JwtTokenService jwtTokenService;
    private BearerTokenAuthenticationFilter filter;

    @BeforeEach
    void setUp() {
        jwtTokenService = new JwtTokenService(new JwtProperties());
        filter = new BearerTokenAuthenticationFilter(jwtTokenService);
    }

    @AfterEach
    void tearDown() {
        SecurityContextHolder.clearContext();
    }

    @Test
    void accessTokenAuthenticates() throws Exception {
        String token = jwtTokenService.createAccessToken(7L, "student", List.of(Role.STUDENT));
        MockHttpServletRequest request = new MockHttpServletRequest("GET", "/problems");
        request.addHeader("Authorization", "Bearer " + token);

        filter.doFilter(request, new MockHttpServletResponse(), new MockFilterChain());

        assertNotNull(SecurityContextHolder.getContext().getAuthentication());
    }

    @Test
    void refreshTokenIsRejectedAsAccessToken() throws Exception {
        String token = jwtTokenService.createRefreshToken(7L, "student", List.of(Role.STUDENT));
        MockHttpServletRequest request = new MockHttpServletRequest("GET", "/problems");
        request.addHeader("Authorization", "Bearer " + token);

        filter.doFilter(request, new MockHttpServletResponse(), new MockFilterChain());

        assertNull(SecurityContextHolder.getContext().getAuthentication());
    }

    @Test
    void malformedTokenLeavesContextCleared() throws Exception {
        MockHttpServletRequest request = new MockHttpServletRequest("GET", "/problems");
        request.addHeader("Authorization", "Bearer not-a-jwt");

        filter.doFilter(request, new MockHttpServletResponse(), new MockFilterChain());

        assertNull(SecurityContextHolder.getContext().getAuthentication());
    }
}
