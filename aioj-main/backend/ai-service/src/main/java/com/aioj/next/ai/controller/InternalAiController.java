package com.aioj.next.ai.controller;

import com.aioj.next.ai.domain.ContestPostmortemAnalysisService;
import com.aioj.next.ai.domain.AiMemoryService;
import com.aioj.next.ai.domain.PlagiarismAnalysisService;
import com.aioj.next.ai.domain.StudentPostmortemAnalysisService;
import com.aioj.next.ai.domain.memory.AiJudgedSubmissionEventService;
import com.aioj.next.common.api.ApiResponse;
import com.aioj.next.contract.ai.AiJudgedSubmissionEventRequest;
import com.aioj.next.contract.ai.ContestPostmortemAnalysisRequest;
import com.aioj.next.contract.ai.ContestPostmortemAnalysisResponse;
import com.aioj.next.contract.ai.PlagiarismAnalysisRequest;
import com.aioj.next.contract.ai.PlagiarismAnalysisResponse;
import com.aioj.next.contract.ai.StudentPostmortemAnalysisRequest;
import com.aioj.next.contract.ai.StudentPostmortemAnalysisResponse;
import com.aioj.next.contract.ai.StudentPostmortemWeaknessConfirmRequest;
import com.aioj.next.contract.ai.StudentPostmortemWeaknessConfirmResponse;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/v1/internal/ai")
public class InternalAiController {
    private final PlagiarismAnalysisService plagiarismAnalysisService;
    private final ContestPostmortemAnalysisService contestPostmortemAnalysisService;
    private final StudentPostmortemAnalysisService studentPostmortemAnalysisService;
    private final AiMemoryService aiMemoryService;
    private final AiJudgedSubmissionEventService judgedSubmissionEventService;

    public InternalAiController(PlagiarismAnalysisService plagiarismAnalysisService,
                                ContestPostmortemAnalysisService contestPostmortemAnalysisService,
                                StudentPostmortemAnalysisService studentPostmortemAnalysisService,
                                AiMemoryService aiMemoryService,
                                AiJudgedSubmissionEventService judgedSubmissionEventService) {
        this.plagiarismAnalysisService = plagiarismAnalysisService;
        this.contestPostmortemAnalysisService = contestPostmortemAnalysisService;
        this.studentPostmortemAnalysisService = studentPostmortemAnalysisService;
        this.aiMemoryService = aiMemoryService;
        this.judgedSubmissionEventService = judgedSubmissionEventService;
    }

    @PostMapping("/plagiarism-analysis")
    @PreAuthorize("hasRole('INTERNAL')")
    public ApiResponse<PlagiarismAnalysisResponse> plagiarismAnalysis(@RequestBody PlagiarismAnalysisRequest request) {
        return ApiResponse.ok(plagiarismAnalysisService.analyze(request));
    }

    @PostMapping("/contest-postmortem-analysis")
    @PreAuthorize("hasRole('INTERNAL')")
    public ApiResponse<ContestPostmortemAnalysisResponse> contestPostmortemAnalysis(@RequestBody ContestPostmortemAnalysisRequest request) {
        return ApiResponse.ok(contestPostmortemAnalysisService.analyze(request));
    }

    @PostMapping("/student-postmortem-analysis")
    @PreAuthorize("hasRole('INTERNAL')")
    public ApiResponse<StudentPostmortemAnalysisResponse> studentPostmortemAnalysis(@RequestBody StudentPostmortemAnalysisRequest request) {
        return ApiResponse.ok(studentPostmortemAnalysisService.analyze(request));
    }

    @PostMapping("/student-postmortem-weakness-confirmations")
    @PreAuthorize("hasRole('INTERNAL')")
    public ApiResponse<StudentPostmortemWeaknessConfirmResponse> studentPostmortemWeaknessConfirmation(
            @RequestBody StudentPostmortemWeaknessConfirmRequest request) {
        return ApiResponse.ok(aiMemoryService.confirmStudentPostmortemWeakness(request));
    }

    @PostMapping("/submissions/judged-events")
    @PreAuthorize("hasRole('INTERNAL')")
    public ApiResponse<Void> judgedSubmissionEvent(@RequestBody AiJudgedSubmissionEventRequest request) {
        judgedSubmissionEventService.recordJudgedSubmission(request);
        return ApiResponse.ok(null);
    }
}
