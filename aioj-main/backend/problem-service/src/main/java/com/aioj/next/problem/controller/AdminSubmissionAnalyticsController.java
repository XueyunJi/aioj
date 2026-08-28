package com.aioj.next.problem.controller;

import com.aioj.next.common.api.ApiResponse;
import com.aioj.next.contract.submission.DailySubmissionStatsResponse;
import com.aioj.next.problem.domain.SubmissionService;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

@RestController
@RequestMapping("/admin/submissions")
@PreAuthorize("hasAnyRole('TEACHER','ADMIN')")
public class AdminSubmissionAnalyticsController {
    private final SubmissionService submissionService;

    public AdminSubmissionAnalyticsController(SubmissionService submissionService) {
        this.submissionService = submissionService;
    }

    @GetMapping("/analytics/daily")
    public ApiResponse<List<DailySubmissionStatsResponse>> dailyStats(@RequestParam(defaultValue = "7") int days) {
        return ApiResponse.ok(submissionService.dailyStats(days));
    }
}
