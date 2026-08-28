package com.aioj.next.auth.controller;

import com.aioj.next.common.api.ApiResponse;
import com.aioj.next.contract.diagnostics.ClientErrorReport;
import jakarta.validation.Valid;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/diagnostics")
public class DiagnosticsController {
    private static final Logger log = LoggerFactory.getLogger("client.diagnostics");

    @PostMapping("/error")
    public ApiResponse<Boolean> reportError(@RequestBody @Valid ClientErrorReport report) {
        log.warn("clientError kind={} code={} traceId={} url={} ua=\"{}\" msg=\"{}\"",
                report.kind(), report.code(), report.traceId(), report.url(),
                abbreviate(report.userAgent(), 120), abbreviate(report.message(), 200));
        if (report.stack() != null && !report.stack().isBlank()) {
            log.debug("clientErrorStack traceId={} stack=\n{}", report.traceId(), report.stack());
        }
        return ApiResponse.ok(Boolean.TRUE);
    }

    private static String abbreviate(String s, int max) {
        if (s == null) return "";
        return s.length() <= max ? s : s.substring(0, max) + "...";
    }
}
