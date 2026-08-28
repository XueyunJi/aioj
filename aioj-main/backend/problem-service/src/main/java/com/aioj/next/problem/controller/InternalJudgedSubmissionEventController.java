package com.aioj.next.problem.controller;

import com.aioj.next.common.api.ApiResponse;
import com.aioj.next.contract.ai.AiJudgedSubmissionEventRequest;
import com.aioj.next.problem.domain.JudgedSubmissionEventService;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/v1/internal/submissions/judged-events")
public class InternalJudgedSubmissionEventController {
    private final JudgedSubmissionEventService eventService;

    public InternalJudgedSubmissionEventController(JudgedSubmissionEventService eventService) {
        this.eventService = eventService;
    }

    @PostMapping
    @PreAuthorize("hasRole('INTERNAL')")
    public ApiResponse<Void> judgedSubmissionEvent(@RequestBody AiJudgedSubmissionEventRequest request) {
        eventService.handle(request);
        return ApiResponse.ok(null);
    }
}
