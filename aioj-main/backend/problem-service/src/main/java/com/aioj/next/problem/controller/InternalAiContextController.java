package com.aioj.next.problem.controller;

import com.aioj.next.common.api.ApiResponse;
import com.aioj.next.contract.ai.AiProblemContextRequest;
import com.aioj.next.contract.ai.AiProblemContextResponse;
import com.aioj.next.contract.ai.AiSubmissionContextRequest;
import com.aioj.next.contract.ai.AiSubmissionContextResponse;
import com.aioj.next.contract.ai.ContestParticipantProfile;
import com.aioj.next.contract.ai.ProblemTitleInfo;
import com.aioj.next.contract.contest.ContestRunWindow;
import com.aioj.next.contract.contest.RunningContestParticipation;
import com.aioj.next.contract.contest.RunningContestProblemStatement;
import com.aioj.next.problem.domain.ContestProblemVisibilityService;
import com.aioj.next.problem.domain.ContestService;
import com.aioj.next.problem.domain.ProblemCatalog;
import com.aioj.next.problem.domain.SubmissionService;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.time.Instant;
import java.util.List;

@RestController
@RequestMapping("/api/v1/internal/ai")
public class InternalAiContextController {
    private static final int RUNNING_STATEMENT_MAX_CHARS = 4000;

    private final ProblemCatalog problemCatalog;
    private final SubmissionService submissionService;
    private final ContestProblemVisibilityService visibilityService;
    private final ContestService contestService;

    public InternalAiContextController(ProblemCatalog problemCatalog, SubmissionService submissionService,
                                       ContestProblemVisibilityService visibilityService,
                                       ContestService contestService) {
        this.problemCatalog = problemCatalog;
        this.submissionService = submissionService;
        this.visibilityService = visibilityService;
        this.contestService = contestService;
    }

    @PostMapping("/problems/context")
    @PreAuthorize("hasRole('INTERNAL')")
    public ApiResponse<AiProblemContextResponse> problemContext(@RequestBody AiProblemContextRequest request) {
        return ApiResponse.ok(problemCatalog.aiProblemContext(request));
    }

    @PostMapping("/submissions/context")
    @PreAuthorize("hasRole('INTERNAL')")
    public ApiResponse<AiSubmissionContextResponse> submissionContext(@RequestBody AiSubmissionContextRequest request) {
        return ApiResponse.ok(submissionService.aiSubmissionContext(request));
    }

    @GetMapping("/users/{userId}/running-contest-problem-statements")
    @PreAuthorize("hasRole('INTERNAL')")
    public ApiResponse<List<RunningContestProblemStatement>> runningParticipationProblemStatements(@PathVariable Long userId) {
        return ApiResponse.ok(visibilityService.runningParticipationProblemStatements(userId, Instant.now(), RUNNING_STATEMENT_MAX_CHARS));
    }

    @GetMapping("/contests/{contestId}/run-windows")
    @PreAuthorize("hasRole('INTERNAL')")
    public ApiResponse<List<ContestRunWindow>> contestRunWindows(@PathVariable Long contestId) {
        return ApiResponse.ok(visibilityService.contestRunWindows(contestId));
    }

    @GetMapping("/users/{userId}/running-participations")
    @PreAuthorize("hasRole('INTERNAL')")
    public ApiResponse<List<RunningContestParticipation>> runningParticipations(@PathVariable Long userId) {
        return ApiResponse.ok(visibilityService.runningParticipations(userId, Instant.now()));
    }

    @GetMapping("/contests/{contestId}/participants")
    @PreAuthorize("hasRole('INTERNAL')")
    public ApiResponse<List<ContestParticipantProfile>> contestParticipantProfiles(@PathVariable Long contestId) {
        return ApiResponse.ok(contestService.participantProfiles(contestId));
    }

    @GetMapping("/problems/titles")
    @PreAuthorize("hasRole('INTERNAL')")
    public ApiResponse<List<ProblemTitleInfo>> problemTitles(@RequestParam List<Long> ids) {
        return ApiResponse.ok(problemCatalog.problemTitles(ids));
    }
}
