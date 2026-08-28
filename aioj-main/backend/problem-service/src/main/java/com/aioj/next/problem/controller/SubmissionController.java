package com.aioj.next.problem.controller;

import com.aioj.next.common.api.ApiResponse;
import com.aioj.next.common.api.PageResponse;
import com.aioj.next.contract.submission.SubmissionCreateRequest;
import com.aioj.next.contract.submission.SubmissionResponse;
import com.aioj.next.contract.submission.SubmissionScope;
import com.aioj.next.contract.submission.SubmissionStatus;
import com.aioj.next.problem.domain.SubmissionRequestMetadata;
import com.aioj.next.problem.domain.SubmissionService;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/submissions")
public class SubmissionController {
    private final SubmissionService submissionService;

    public SubmissionController(SubmissionService submissionService) {
        this.submissionService = submissionService;
    }

    @PostMapping
    public ApiResponse<SubmissionResponse> submit(@RequestBody @Valid SubmissionCreateRequest request,
                                                  HttpServletRequest servletRequest) {
        SubmissionRequestMetadata metadata = new SubmissionRequestMetadata(
                servletRequest.getRemoteAddr(),
                servletRequest.getHeader("X-Forwarded-For"),
                servletRequest.getHeader("User-Agent")
        );
        return ApiResponse.ok(submissionService.submit(request, metadata));
    }

    @GetMapping("/{id}")
    public ApiResponse<SubmissionResponse> get(@PathVariable Long id) {
        return ApiResponse.ok(submissionService.get(id));
    }

    @GetMapping
    public ApiResponse<PageResponse<SubmissionResponse>> list(@RequestParam(defaultValue = "1") long page,
                                                              @RequestParam(defaultValue = "20") long pageSize,
                                                              @RequestParam(required = false) Long problemId,
                                                              @RequestParam(required = false) Long userId,
                                                              @RequestParam(required = false) SubmissionStatus status,
                                                              @RequestParam(required = false) String language,
                                                              @RequestParam(required = false) Boolean mine,
                                                              @RequestParam(required = false) Long contestId,
                                                              @RequestParam(required = false) Long contestRunId,
                                                              @RequestParam(required = false) Long contestProblemId,
                                                              @RequestParam(required = false) SubmissionScope scope) {
        return ApiResponse.ok(submissionService.list(page, pageSize, problemId, userId, status, language, mine,
                contestId, contestRunId, contestProblemId, scope));
    }

}
