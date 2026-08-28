package com.aioj.next.contract.diagnostics;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

public record ClientErrorReport(
        @NotBlank @Size(max = 32) String kind,
        @NotBlank @Size(max = 500) String message,
        @Size(max = 8000) String stack,
        Integer code,
        @Size(max = 64) String traceId,
        @Size(max = 500) String url,
        @Size(max = 300) String userAgent,
        String when
) {
}
