package com.aioj.next.common.security;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;
import java.util.Set;

public class PasswordResetRequiredFilter extends OncePerRequestFilter {
    private static final Set<String> ALLOWED_EXACT_PATHS = Set.of(
            "/users/me",
            "/users/me/password",
            "/auth/refresh",
            "/auth/logout"
    );

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain filterChain)
            throws ServletException, IOException {
        var authentication = SecurityContextHolder.getContext().getAuthentication();
        Object principal = authentication == null ? null : authentication.getPrincipal();
        if (principal instanceof SecurityPrincipal securityPrincipal
                && securityPrincipal.passwordResetRequired()
                && !isAllowed(request)) {
            ApiSecurityExceptionHandlers.writeForbidden(response, "Password reset is required");
            return;
        }
        filterChain.doFilter(request, response);
    }

    private boolean isAllowed(HttpServletRequest request) {
        if ("OPTIONS".equalsIgnoreCase(request.getMethod())) {
            return true;
        }
        String path = request.getRequestURI();
        if (ALLOWED_EXACT_PATHS.contains(path)) {
            return true;
        }
        return path.startsWith("/actuator/")
                || path.startsWith("/v3/api-docs/")
                || path.startsWith("/swagger-ui/");
    }
}
