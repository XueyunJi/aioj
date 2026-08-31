package com.aioj.next.problem.controller;

import com.aioj.next.common.api.ApiResponse;
import com.aioj.next.common.api.PageResponse;
import com.aioj.next.contract.problem.Difficulty;
import com.aioj.next.contract.problem.TutorProblemCapabilitiesResponse;
import com.aioj.next.contract.problem.TutorProblemResponse;
import com.aioj.next.contract.problem.TutorRecommendationResponse;
import com.aioj.next.problem.domain.ProblemCatalog;
import com.aioj.next.problem.domain.TutorRecommendationService;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/tutor")
public class TutorProblemController {
    private final ProblemCatalog problemCatalog;
    private final TutorRecommendationService recommendationService;

    public TutorProblemController(ProblemCatalog problemCatalog, TutorRecommendationService recommendationService) {
        this.problemCatalog = problemCatalog;
        this.recommendationService = recommendationService;
    }

    @GetMapping("/capabilities")
    public ApiResponse<TutorProblemCapabilitiesResponse> capabilities() {
        return ApiResponse.ok(problemCatalog.tutorCapabilities());
    }

    @GetMapping("/problems")
    public ApiResponse<PageResponse<TutorProblemResponse>> search(
            @RequestParam(defaultValue = "1") long page,
            @RequestParam(defaultValue = "20") long pageSize,
            @RequestParam(required = false) String keyword,
            @RequestParam(required = false) Difficulty difficulty,
            @RequestParam(required = false) String tag,
            @RequestParam(required = false, defaultValue = "NEWEST") String sort) {
        return ApiResponse.ok(problemCatalog.tutorSearch(page, pageSize, keyword, difficulty, tag, sort));
    }

    @GetMapping("/problems/{id}")
    public ApiResponse<TutorProblemResponse> detail(@PathVariable Long id) {
        return ApiResponse.ok(problemCatalog.tutorDetail(id));
    }

    @GetMapping("/recommendations")
    public ApiResponse<java.util.List<TutorRecommendationResponse>> recommendations(
            @RequestParam(required = false) Integer limit) {
        return ApiResponse.ok(recommendationService.recommend(limit));
    }
}
