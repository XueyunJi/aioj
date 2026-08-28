package com.aioj.next.problem.controller;

import com.aioj.next.common.api.ApiResponse;
import com.aioj.next.common.api.PageResponse;
import com.aioj.next.contract.problem.Difficulty;
import com.aioj.next.contract.problem.ProblemCreateRequest;
import com.aioj.next.contract.problem.ProblemResponse;
import com.aioj.next.contract.problem.ProblemSolutionResponse;
import com.aioj.next.contract.problem.ProblemTestcaseGeneratorResponse;
import com.aioj.next.contract.problem.ProblemUpdateRequest;
import com.aioj.next.problem.domain.ProblemCatalog;
import jakarta.validation.Valid;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

@RestController
@RequestMapping("/problems")
public class ProblemController {
    private final ProblemCatalog problemCatalog;

    public ProblemController(ProblemCatalog problemCatalog) {
        this.problemCatalog = problemCatalog;
    }

    @GetMapping
    public ApiResponse<PageResponse<ProblemResponse>> list(@RequestParam(defaultValue = "1") long page,
                                                           @RequestParam(defaultValue = "20") long pageSize,
                                                           @RequestParam(required = false) String keyword,
                                                           @RequestParam(required = false) Difficulty difficulty,
                                                           @RequestParam(required = false) String tag,
                                                           @RequestParam(required = false) String status,
                                                           @RequestParam(required = false) String visibility,
                                                           @RequestParam(required = false) String sort) {
        return ApiResponse.ok(problemCatalog.list(page, pageSize, keyword, difficulty, tag, status, visibility, sort));
    }

    @GetMapping("/{id}")
    public ApiResponse<ProblemResponse> get(@PathVariable Long id,
                                            @RequestParam(required = false) Long contestRunId,
                                            @RequestParam(required = false) Long contestProblemId,
                                            @RequestParam(required = false, defaultValue = "false") boolean staffView) {
        return ApiResponse.ok(problemCatalog.get(id, contestRunId, contestProblemId, staffView));
    }

    @PostMapping
    @PreAuthorize("hasAnyRole('TEACHER','ADMIN')")
    public ApiResponse<ProblemResponse> create(@RequestBody @Valid ProblemCreateRequest request) {
        return ApiResponse.ok(problemCatalog.create(request, false));
    }

    @PutMapping("/{id}")
    @PreAuthorize("hasAnyRole('TEACHER','ADMIN')")
    public ApiResponse<ProblemResponse> update(@PathVariable Long id, @RequestBody @Valid ProblemUpdateRequest request) {
        return ApiResponse.ok(problemCatalog.update(id, request));
    }

    @GetMapping("/{id}/standard-solution")
    @PreAuthorize("hasAnyRole('TEACHER','ADMIN')")
    public ApiResponse<ProblemSolutionResponse> standardSolution(@PathVariable Long id) {
        return ApiResponse.ok(problemCatalog.standardSolution(id));
    }

    @GetMapping("/{id}/standard-solutions")
    @PreAuthorize("hasAnyRole('TEACHER','ADMIN')")
    public ApiResponse<List<ProblemSolutionResponse>> standardSolutions(@PathVariable Long id) {
        return ApiResponse.ok(problemCatalog.standardSolutions(id));
    }

    @GetMapping("/{id}/testcase-generator")
    @PreAuthorize("hasAnyRole('TEACHER','ADMIN')")
    public ApiResponse<ProblemTestcaseGeneratorResponse> testcaseGenerator(@PathVariable Long id) {
        return ApiResponse.ok(problemCatalog.testcaseGenerator(id));
    }

    @PostMapping("/{id}/archive")
    @PreAuthorize("hasAnyRole('TEACHER','ADMIN')")
    public ApiResponse<ProblemResponse> archive(@PathVariable Long id) {
        return ApiResponse.ok(problemCatalog.archive(id));
    }

    @PostMapping("/{id}/restore")
    @PreAuthorize("hasAnyRole('TEACHER','ADMIN')")
    public ApiResponse<ProblemResponse> restore(@PathVariable Long id) {
        return ApiResponse.ok(problemCatalog.restore(id));
    }

    @DeleteMapping("/{id}")
    @PreAuthorize("hasAnyRole('TEACHER','ADMIN')")
    public ApiResponse<Void> delete(@PathVariable Long id) {
        problemCatalog.delete(id);
        return ApiResponse.ok(null);
    }
}
