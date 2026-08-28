package com.aioj.next.gateway;

import org.slf4j.MDC;
import org.springframework.http.server.reactive.ServerHttpRequest;
import org.springframework.stereotype.Component;
import org.springframework.web.server.ServerWebExchange;
import org.springframework.web.server.WebFilter;
import org.springframework.web.server.WebFilterChain;
import reactor.core.publisher.Mono;

import java.util.UUID;

@Component
public class TraceWebFilter implements WebFilter {
    public static final String TRACE_HEADER = "X-Trace-Id";

    @Override
    public Mono<Void> filter(ServerWebExchange exchange, WebFilterChain chain) {
        String traceId = exchange.getRequest().getHeaders().getFirst(TRACE_HEADER);
        if (traceId == null || traceId.isBlank()) {
            traceId = UUID.randomUUID().toString();
        }
        ServerHttpRequest request = exchange.getRequest().mutate().header(TRACE_HEADER, traceId).build();
        exchange.getResponse().getHeaders().set(TRACE_HEADER, traceId);
        String finalTraceId = traceId;
        return chain.filter(exchange.mutate().request(request).build())
                .contextWrite(context -> context.put("traceId", finalTraceId))
                .doFinally(signalType -> MDC.remove("traceId"));
    }
}
