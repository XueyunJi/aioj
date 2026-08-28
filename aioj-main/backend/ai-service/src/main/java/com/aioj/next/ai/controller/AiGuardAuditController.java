package com.aioj.next.ai.controller;

import com.aioj.next.ai.agent.policy.GuardDecisionAuditService;
import com.aioj.next.ai.domain.response.GuardDecisionAuditItem;
import com.aioj.next.ai.domain.response.GuardTurnMessagesResponse;
import com.aioj.next.common.api.ApiResponse;
import com.aioj.next.common.api.PageResponse;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@RestController
@PreAuthorize("hasAnyRole('TEACHER','ADMIN')")
public class AiGuardAuditController {
    private final GuardDecisionAuditService auditService;

    public AiGuardAuditController(GuardDecisionAuditService auditService) {
        this.auditService = auditService;
    }

    @GetMapping("/admin/ai-guard-decisions")
    public ApiResponse<PageResponse<GuardDecisionAuditItem>> list(
            @RequestParam(required = false) Long contestRunId,
            @RequestParam(required = false) Long userId,
            @RequestParam(required = false) String layer,
            @RequestParam(required = false) String decision,
            @RequestParam(required = false) Boolean degraded,
            @RequestParam(required = false) String from,
            @RequestParam(required = false) String to,
            @RequestParam(defaultValue = "1") long page,
            @RequestParam(defaultValue = "20") long pageSize
    ) {
        return ApiResponse.ok(auditService.list(
                contestRunId, userId, layer, decision, degraded, from, to, page, pageSize));
    }

    @GetMapping("/admin/ai-guard-decisions/turns/{turnId}/messages")
    public ApiResponse<GuardTurnMessagesResponse> turnMessages(@PathVariable String turnId) {
        return ApiResponse.ok(auditService.turnMessages(turnId));
    }
}
