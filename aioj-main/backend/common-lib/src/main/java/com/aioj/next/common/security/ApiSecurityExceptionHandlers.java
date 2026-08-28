package com.aioj.next.common.security;

import com.aioj.next.common.api.TraceIds;
import com.aioj.next.common.error.ErrorCode;
import com.aioj.next.common.error.UserErrorFeedback;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.security.web.AuthenticationEntryPoint;
import org.springframework.security.web.access.AccessDeniedHandler;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.Map;

public final class ApiSecurityExceptionHandlers {
    private ApiSecurityExceptionHandlers() {
    }

    public static AuthenticationEntryPoint authenticationEntryPoint() {
        return (request, response, exception) ->
                write(response, HttpStatus.UNAUTHORIZED, ErrorCode.UNAUTHORIZED);
    }

    public static AccessDeniedHandler accessDeniedHandler() {
        return (request, response, exception) ->
                write(response, HttpStatus.FORBIDDEN, ErrorCode.FORBIDDEN);
    }

    public static void writeUnauthorized(HttpServletResponse response, String message) throws IOException {
        write(response, HttpStatus.UNAUTHORIZED, ErrorCode.UNAUTHORIZED,
                UserErrorFeedback.forDomain(ErrorCode.UNAUTHORIZED, message, "gateway-service"));
    }

    public static void writeForbidden(HttpServletResponse response, String message) throws IOException {
        write(response, HttpStatus.FORBIDDEN, ErrorCode.FORBIDDEN,
                UserErrorFeedback.forDomain(ErrorCode.FORBIDDEN, message, "gateway-service"));
    }

    private static void write(HttpServletResponse response, HttpStatus status, ErrorCode code) throws IOException {
        write(response, status, code, UserErrorFeedback.forCode(code, "gateway-service"));
    }

    private static void write(HttpServletResponse response, HttpStatus status, ErrorCode code,
                              UserErrorFeedback feedback) throws IOException {
        String traceId = TraceIds.current();
        response.setStatus(status.value());
        response.setContentType(MediaType.APPLICATION_JSON_VALUE);
        response.setCharacterEncoding(StandardCharsets.UTF_8.name());
        response.setHeader(TraceIds.HEADER, traceId);
        response.getWriter().write("""
                {"code":%d,"message":"%s","data":null,"details":null,"traceId":"%s","timestamp":"%s","errorKey":"%s","errorParams":%s}\
                """.formatted(
                        code.code(),
                        escapeJson(feedback.message()),
                        escapeJson(traceId),
                        Instant.now(),
                        escapeJson(feedback.key()),
                        paramsJson(feedback.params())
                ));
    }

    private static String escapeJson(String value) {
        return value.replace("\\", "\\\\").replace("\"", "\\\"");
    }

    private static String paramsJson(Map<String, String> params) {
        if (params == null || params.isEmpty()) {
            return "null";
        }
        StringBuilder builder = new StringBuilder("{");
        boolean first = true;
        for (Map.Entry<String, String> entry : params.entrySet()) {
            if (!first) {
                builder.append(',');
            }
            builder.append('"').append(escapeJson(entry.getKey())).append("\":\"")
                    .append(escapeJson(entry.getValue()))
                    .append('"');
            first = false;
        }
        return builder.append('}').toString();
    }
}
