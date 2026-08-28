package com.aioj.next.common.api;

import org.slf4j.MDC;

import java.util.UUID;

public final class TraceIds {
    public static final String HEADER = "X-Trace-Id";
    public static final String MDC_KEY = "traceId";

    private TraceIds() {
    }

    public static String current() {
        String value = MDC.get(MDC_KEY);
        return value == null || value.isBlank() ? "n/a" : value;
    }

    public static String ensure(String incoming) {
        return incoming == null || incoming.isBlank() ? UUID.randomUUID().toString() : incoming;
    }
}
