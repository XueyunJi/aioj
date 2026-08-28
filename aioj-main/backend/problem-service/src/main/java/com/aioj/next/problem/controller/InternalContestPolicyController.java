package com.aioj.next.problem.controller;

import com.aioj.next.common.api.ApiResponse;
import com.aioj.next.contract.contest.ContestAiPolicyRequest;
import com.aioj.next.contract.contest.ContestAiPolicyResponse;
import com.aioj.next.problem.domain.ContestAiPolicyService;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/v1/internal/contests/ai-policy")
public class InternalContestPolicyController {
    private final ContestAiPolicyService contestAiPolicyService;

    public InternalContestPolicyController(ContestAiPolicyService contestAiPolicyService) {
        this.contestAiPolicyService = contestAiPolicyService;
    }

    /**
     * @deprecated legacy per-question policy check; ai-service switches to the
     * statement/participation snapshot endpoints after the contest AI guard
     * redesign. Kept until the legacy call sites are removed.
     */
    @Deprecated
    @PostMapping("/check")
    @PreAuthorize("hasRole('INTERNAL')")
    public ApiResponse<ContestAiPolicyResponse> check(@RequestBody(required = false) ContestAiPolicyRequest request) {
        return ApiResponse.ok(contestAiPolicyService.check(request));
    }
}
