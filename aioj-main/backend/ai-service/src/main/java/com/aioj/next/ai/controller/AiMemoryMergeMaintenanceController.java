package com.aioj.next.ai.controller;

import com.aioj.next.ai.domain.memory.AiMemoryMergeService;
import com.aioj.next.common.api.ApiResponse;
import com.aioj.next.common.security.SecuritySupport;
import com.aioj.next.contract.ai.AiMemoryMergeMaintenanceRequest;
import com.aioj.next.contract.ai.AiMemoryMergeMaintenanceResponse;
import jakarta.validation.Valid;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;

@RestController
@PreAuthorize("hasRole('ADMIN')")
public class AiMemoryMergeMaintenanceController {
    private final AiMemoryMergeService mergeService;

    public AiMemoryMergeMaintenanceController(AiMemoryMergeService mergeService) {
        this.mergeService = mergeService;
    }

    @PostMapping("/ai/admin/memory-merge-maintenance")
    public ApiResponse<AiMemoryMergeMaintenanceResponse> enqueueMaintenance(
            @RequestBody(required = false) @Valid AiMemoryMergeMaintenanceRequest request
    ) {
        return ApiResponse.ok(mergeService.enqueueMaintenance(SecuritySupport.currentUserId(), request));
    }
}
