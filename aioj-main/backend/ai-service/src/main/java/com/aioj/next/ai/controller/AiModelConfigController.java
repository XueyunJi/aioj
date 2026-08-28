package com.aioj.next.ai.controller;

import com.aioj.next.ai.domain.AiModelConfigService;
import com.aioj.next.common.api.ApiResponse;
import com.aioj.next.common.security.SecuritySupport;
import com.aioj.next.contract.ai.AiModelConfigResponse;
import com.aioj.next.contract.ai.AiModelConfigTestRequest;
import com.aioj.next.contract.ai.AiModelConfigTestResponse;
import com.aioj.next.contract.ai.AiModelConfigUpdateRequest;
import com.aioj.next.contract.ai.AiModelListResponse;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

@RestController
@PreAuthorize("hasRole('ADMIN')")
public class AiModelConfigController {
    private final AiModelConfigService service;

    public AiModelConfigController(AiModelConfigService service) {
        this.service = service;
    }

    @GetMapping("/admin/ai-model-configs")
    public ApiResponse<List<AiModelConfigResponse>> list() {
        return ApiResponse.ok(service.listConfigs());
    }

    @GetMapping("/admin/ai-model-configs/{scope}/models")
    public ApiResponse<AiModelListResponse> models(
            @PathVariable String scope,
            @RequestParam(required = false) String provider,
            @RequestParam(required = false) String baseUrl
    ) {
        return ApiResponse.ok(service.listModels(scope, provider, baseUrl));
    }

    @PutMapping("/admin/ai-model-configs/{scope}")
    public ApiResponse<AiModelConfigResponse> update(
            @PathVariable String scope,
            @RequestBody AiModelConfigUpdateRequest request
    ) {
        return ApiResponse.ok(service.updateConfig(scope, request, SecuritySupport.currentUserId()));
    }

    @PostMapping("/admin/ai-model-configs/{scope}/test")
    public ApiResponse<AiModelConfigTestResponse> test(
            @PathVariable String scope,
            @RequestBody(required = false) AiModelConfigTestRequest request
    ) {
        return ApiResponse.ok(service.testConfig(scope, request, SecuritySupport.currentUserId()));
    }
}
