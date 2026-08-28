package com.aioj.next.problem.controller;

import com.aioj.next.common.api.ApiResponse;
import com.aioj.next.common.api.PageResponse;
import com.aioj.next.contract.contest.ContestExportResponse;
import com.aioj.next.contract.operation.OperationAuditEventResponse;
import com.aioj.next.contract.operation.OperationJobResponse;
import com.aioj.next.contract.operation.OperationJobStatus;
import com.aioj.next.contract.operation.OperationJobType;
import com.aioj.next.problem.domain.OperationAuditService;
import com.aioj.next.problem.domain.OperationJobService;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/admin")
public class OperationController {
    private final OperationJobService operationJobService;
    private final OperationAuditService operationAuditService;

    public OperationController(OperationJobService operationJobService,
                               OperationAuditService operationAuditService) {
        this.operationJobService = operationJobService;
        this.operationAuditService = operationAuditService;
    }

    @GetMapping("/operation-jobs")
    @PreAuthorize("hasAnyRole('TEACHER','ADMIN')")
    public ApiResponse<PageResponse<OperationJobResponse>> jobs(@RequestParam(defaultValue = "1") long page,
                                                                @RequestParam(defaultValue = "20") long pageSize,
                                                                @RequestParam(required = false) OperationJobStatus status,
                                                                @RequestParam(required = false) OperationJobType type) {
        return ApiResponse.ok(operationJobService.list(page, pageSize, status, type));
    }

    @GetMapping("/operation-jobs/{jobId}")
    @PreAuthorize("hasAnyRole('TEACHER','ADMIN')")
    public ApiResponse<OperationJobResponse> job(@PathVariable Long jobId) {
        return ApiResponse.ok(operationJobService.get(jobId));
    }

    @PostMapping("/operation-jobs/{jobId}/retry")
    @PreAuthorize("hasAnyRole('TEACHER','ADMIN')")
    public ApiResponse<OperationJobResponse> retryJob(@PathVariable Long jobId) {
        return ApiResponse.ok(operationJobService.retry(jobId));
    }

    @GetMapping("/operation-jobs/{jobId}/artifact")
    @PreAuthorize("hasAnyRole('TEACHER','ADMIN')")
    public ApiResponse<ContestExportResponse> jobArtifact(@PathVariable Long jobId) {
        return ApiResponse.ok(operationJobService.artifact(jobId));
    }

    @GetMapping("/audit-events")
    @PreAuthorize("hasAnyRole('TEACHER','ADMIN')")
    public ApiResponse<PageResponse<OperationAuditEventResponse>> auditEvents(@RequestParam(defaultValue = "1") long page,
                                                                              @RequestParam(defaultValue = "20") long pageSize,
                                                                              @RequestParam(required = false) String action,
                                                                              @RequestParam(required = false) String resourceType,
                                                                              @RequestParam(required = false) Long contestId,
                                                                              @RequestParam(required = false) Long contestRunId,
                                                                              @RequestParam(required = false) Long actorUserId) {
        return ApiResponse.ok(operationAuditService.list(page, pageSize, action, resourceType, contestId,
                contestRunId, actorUserId));
    }
}
