package com.aioj.next.ai.controller;

import com.aioj.next.ai.domain.memory.AiMemoryObservabilityService;
import com.aioj.next.common.api.ApiResponse;
import com.aioj.next.contract.ai.AiMemoryObservabilityResponse;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@PreAuthorize("hasRole('ADMIN')")
public class AiMemoryObservabilityController {
    private final AiMemoryObservabilityService observabilityService;

    public AiMemoryObservabilityController(AiMemoryObservabilityService observabilityService) {
        this.observabilityService = observabilityService;
    }

    @GetMapping("/ai/admin/memory-observability")
    public ApiResponse<AiMemoryObservabilityResponse> summary() {
        return ApiResponse.ok(observabilityService.summary());
    }
}
