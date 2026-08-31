package com.aioj.next.problem.domain;

import com.aioj.next.common.security.Role;
import com.aioj.next.common.security.SecurityPrincipal;
import com.aioj.next.contract.problem.ProblemVisibility;
import com.aioj.next.contract.problem.TutorProblemResponse;
import com.aioj.next.contract.submission.SubmissionStatus;
import com.aioj.next.problem.persistence.entity.ProblemEntity;
import com.aioj.next.problem.persistence.entity.SubmissionEntity;
import com.aioj.next.problem.persistence.mapper.ProblemMapper;
import com.aioj.next.problem.persistence.mapper.SubmissionMapper;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.context.SecurityContextHolder;

import java.time.Instant;
import java.util.List;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class TutorRecommendationServiceTest {
    @Mock
    private ProblemMapper problemMapper;
    @Mock
    private SubmissionMapper submissionMapper;
    @Mock
    private ProblemCatalog problemCatalog;

    private TutorRecommendationService service;

    @BeforeEach
    void setUp() {
        service = new TutorRecommendationService(problemMapper, submissionMapper, problemCatalog);
        SecurityContextHolder.getContext().setAuthentication(new UsernamePasswordAuthenticationToken(
                new SecurityPrincipal(42L, "student", Set.of(Role.STUDENT)), "n/a"));
    }

    @AfterEach
    void tearDown() {
        SecurityContextHolder.clearContext();
    }

    @Test
    void readsOnlyCurrentUsersPracticeHistoryAndExcludesAcceptedProblems() {
        ProblemEntity unseen = problem(1L, "Unseen");
        ProblemEntity solved = problem(2L, "Solved");
        SubmissionEntity ownAccepted = submission(2L, 42L, SubmissionStatus.ACCEPTED);
        SubmissionEntity otherFailed = submission(1L, 99L, SubmissionStatus.WRONG_ANSWER);
        when(submissionMapper.selectList(any())).thenReturn(List.of(ownAccepted));
        when(problemMapper.selectList(any())).thenReturn(List.of(unseen, solved));
        when(problemCatalog.toTutorResponse(unseen)).thenReturn(tutorProblem(1L));

        var result = service.recommend(10);

        assertEquals(1, result.size());
        assertEquals(1L, result.get(0).problem().problemId());
    }

    @Test
    void failedProblemIsRecommendedWithRetryReason() {
        ProblemEntity failed = problem(3L, "Retry");
        when(submissionMapper.selectList(any())).thenReturn(List.of(submission(3L, 42L, SubmissionStatus.WRONG_ANSWER)));
        when(problemMapper.selectList(any())).thenReturn(List.of(failed));
        when(problemCatalog.toTutorResponse(failed)).thenReturn(tutorProblem(3L));

        var result = service.recommend(1);

        assertEquals(1, result.size());
        assertEquals("曾提交但尚未通过，适合针对性复习", result.get(0).reason());
    }

    private ProblemEntity problem(Long id, String title) {
        ProblemEntity problem = new ProblemEntity();
        problem.setId(id);
        problem.setTitle(title);
        problem.setVisibility(ProblemVisibility.PUBLIC);
        problem.setDeleted(false);
        problem.setCreatedAt(Instant.parse("2026-08-01T00:00:00Z"));
        return problem;
    }

    private SubmissionEntity submission(Long problemId, Long userId, SubmissionStatus status) {
        SubmissionEntity submission = new SubmissionEntity();
        submission.setProblemId(problemId);
        submission.setUserId(userId);
        submission.setStatus(status);
        return submission;
    }

    private TutorProblemResponse tutorProblem(Long id) {
        return new TutorProblemResponse(id, "v1", null, "http://localhost:5175/problems/" + id,
                "problem", null, "statement", null, List.of(), List.of(), 1000, 262144);
    }
}
