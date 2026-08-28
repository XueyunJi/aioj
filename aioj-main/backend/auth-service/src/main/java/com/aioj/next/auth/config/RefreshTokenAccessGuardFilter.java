package com.aioj.next.auth.config;

import com.aioj.next.common.security.ApiSecurityExceptionHandlers;
import com.aioj.next.common.security.JwtTokenService;
import io.jsonwebtoken.Claims;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;

public class RefreshTokenAccessGuardFilter extends OncePerRequestFilter {
    private final JwtTokenService jwtTokenService;

    public RefreshTokenAccessGuardFilter(JwtTokenService jwtTokenService) {
        this.jwtTokenService = jwtTokenService;
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain filterChain)
            throws ServletException, IOException {
        String header = request.getHeader("Authorization");
        if (header != null && header.startsWith("Bearer ")) {
            try {
                Claims claims = jwtTokenService.parse(header.substring(7));
                if (JwtTokenService.TOKEN_TYPE_REFRESH.equals(claims.get("typ", String.class))
                        && !allowsRefreshToken(request.getRequestURI())) {
                    SecurityContextHolder.clearContext();
                    ApiSecurityExceptionHandlers.writeUnauthorized(response, "Access token required");
                    return;
                }
            } catch (RuntimeException ignored) {
                SecurityContextHolder.clearContext();
            }
        }
        filterChain.doFilter(request, response);
    }

    private boolean allowsRefreshToken(String path) {
        return "/auth/refresh".equals(path) || "/auth/logout".equals(path);
    }
}
