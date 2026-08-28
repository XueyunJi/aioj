package com.aioj.next.common.web;

import com.aioj.next.common.api.TraceIds;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.slf4j.MDC;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.Locale;

public class TraceIdFilter extends OncePerRequestFilter {
    private static final String JSON_CONTENT_TYPE = "application/json;charset=UTF-8";

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain filterChain)
            throws ServletException, IOException {
        request.setCharacterEncoding(StandardCharsets.UTF_8.name());
        response.setCharacterEncoding(StandardCharsets.UTF_8.name());
        String traceId = TraceIds.ensure(request.getHeader(TraceIds.HEADER));
        MDC.put(TraceIds.MDC_KEY, traceId);
        response.setHeader(TraceIds.HEADER, traceId);
        try {
            filterChain.doFilter(request, response);
        } finally {
            ensureJsonCharset(response);
            MDC.remove(TraceIds.MDC_KEY);
        }
    }

    private void ensureJsonCharset(HttpServletResponse response) {
        String contentType = response.getContentType();
        if (contentType == null) {
            return;
        }
        String normalized = contentType.toLowerCase(Locale.ROOT);
        if (normalized.startsWith("application/json") && !normalized.contains("charset=")) {
            response.setContentType(JSON_CONTENT_TYPE);
        }
    }
}
