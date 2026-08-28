package com.aioj.next.common.security;

import com.aioj.next.common.error.ErrorCode;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.security.authentication.BadCredentialsException;

import java.nio.charset.StandardCharsets;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ApiSecurityExceptionHandlersTest {
    @Test
    void authentication_entry_point_returns_json_unauthorized_without_leaking_exception() throws Exception {
        var response = new MockHttpServletResponse();

        ApiSecurityExceptionHandlers.authenticationEntryPoint()
                .commence(new MockHttpServletRequest(), response, new BadCredentialsException("expired token secret"));

        String body = response.getContentAsString(StandardCharsets.UTF_8);
        assertEquals(401, response.getStatus());
        assertTrue(response.getContentType().startsWith("application/json"));
        assertTrue(body.contains("\"code\":" + ErrorCode.UNAUTHORIZED.code()));
        assertTrue(body.contains("\"message\":\"请先登录后再继续操作。\""));
        assertTrue(body.contains("\"errorKey\":\"auth.required\""));
        assertFalse(body.contains("expired token secret"));
    }

    @Test
    void access_denied_handler_returns_json_forbidden_without_leaking_exception() throws Exception {
        var response = new MockHttpServletResponse();

        ApiSecurityExceptionHandlers.accessDeniedHandler()
                .handle(new MockHttpServletRequest(), response, new AccessDeniedException("teacher-only secret"));

        String body = response.getContentAsString(StandardCharsets.UTF_8);
        assertEquals(403, response.getStatus());
        assertTrue(response.getContentType().startsWith("application/json"));
        assertTrue(body.contains("\"code\":" + ErrorCode.FORBIDDEN.code()));
        assertTrue(body.contains("\"message\":\"当前账号没有权限执行该操作。\""));
        assertTrue(body.contains("\"errorKey\":\"auth.forbidden\""));
        assertFalse(body.contains("teacher-only secret"));
    }

    @Test
    void write_unauthorized_returns_domain_specific_json_error() throws Exception {
        var response = new MockHttpServletResponse();

        ApiSecurityExceptionHandlers.writeUnauthorized(response, "Access token required");

        String body = response.getContentAsString(StandardCharsets.UTF_8);
        assertEquals(401, response.getStatus());
        assertTrue(response.getContentType().startsWith("application/json"));
        assertTrue(body.contains("\"code\":" + ErrorCode.UNAUTHORIZED.code()));
        assertTrue(body.contains("\"message\":\"请使用登录凭证访问该接口。\""));
        assertTrue(body.contains("\"errorKey\":\"auth.accessTokenRequired\""));
    }
}
