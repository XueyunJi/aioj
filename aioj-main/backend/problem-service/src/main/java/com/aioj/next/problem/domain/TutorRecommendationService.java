package com.aioj.next.problem.domain;

import com.aioj.next.common.security.SecuritySupport;
import com.aioj.next.contract.problem.TutorProblemResponse;
import com.aioj.next.contract.problem.TutorRecommendationResponse;
import com.aioj.next.contract.submission.SubmissionStatus;
import com.aioj.next.problem.persistence.entity.ProblemEntity;
import com.aioj.next.problem.persistence.entity.SubmissionEntity;
import com.aioj.next.problem.persistence.mapper.ProblemMapper;
import com.aioj.next.problem.persistence.mapper.SubmissionMapper;
import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

@Service
public class TutorRecommendationService {
    private static final int DEFAULT_LIMIT = 10;
    private static final int MAX_LIMIT = 50;

    private final ProblemMapper problemMapper;
    private final SubmissionMapper submissionMapper;
    private final ProblemCatalog problemCatalog;

    public TutorRecommendationService(ProblemMapper problemMapper, SubmissionMapper submissionMapper,
                                      ProblemCatalog problemCatalog) {
        this.problemMapper = problemMapper;
        this.submissionMapper = submissionMapper;
        this.problemCatalog = problemCatalog;
    }

    public List<TutorRecommendationResponse> recommend(Integer requestedLimit) {
        Long userId = SecuritySupport.currentUserId();
        int limit = normalizeLimit(requestedLimit);
        List<SubmissionEntity> submissions = submissionMapper.selectList(new LambdaQueryWrapper<SubmissionEntity>()
                .eq(SubmissionEntity::getUserId, userId)
                .isNull(SubmissionEntity::getContestId)
                .orderByDesc(SubmissionEntity::getCreatedAt)
                .orderByDesc(SubmissionEntity::getId));

        Map<Long, ProblemHistory> history = new HashMap<>();
        for (SubmissionEntity submission : submissions) {
            if (submission.getProblemId() == null) {
                continue;
            }
            ProblemHistory current = history.computeIfAbsent(submission.getProblemId(), ignored -> new ProblemHistory());
            current.attempts++;
            if (submission.getStatus() == SubmissionStatus.ACCEPTED) {
                current.accepted = true;
            } else if (current.firstFailure == null && submission.getStatus() != null) {
                current.firstFailure = submission.getStatus();
            }
        }

        List<ProblemEntity> publicProblems = problemMapper.selectList(new LambdaQueryWrapper<ProblemEntity>()
                .eq(ProblemEntity::getDeleted, false)
                .isNull(ProblemEntity::getDeletedAt)
                .isNull(ProblemEntity::getArchivedAt)
                .eq(ProblemEntity::getVisibility, com.aioj.next.contract.problem.ProblemVisibility.PUBLIC));
        List<ScoredProblem> ranked = new ArrayList<>();
        for (ProblemEntity problem : publicProblems) {
            ProblemHistory item = history.get(problem.getId());
            if (item != null && item.accepted) {
                continue;
            }
            double score = item == null ? 100.0 : 70.0 - Math.min(item.attempts, 10) * 3.0;
            String reason = item == null
                    ? "尚未提交过，适合作为新的练习题"
                    : "曾提交但尚未通过，适合针对性复习";
            ranked.add(new ScoredProblem(problem, score, reason));
        }
        ranked.sort(Comparator.comparingDouble(ScoredProblem::score).reversed()
                .thenComparing(item -> item.problem().getCreatedAt(), Comparator.nullsLast(Comparator.reverseOrder()))
                .thenComparing(item -> item.problem().getId(), Comparator.nullsLast(Comparator.reverseOrder())));
        return ranked.stream().limit(limit).map(item -> new TutorRecommendationResponse(
                problemCatalog.toTutorResponse(item.problem()),
                BigDecimal.valueOf(item.score()).setScale(2, java.math.RoundingMode.HALF_UP),
                item.reason())).toList();
    }

    private int normalizeLimit(Integer requestedLimit) {
        if (requestedLimit == null || requestedLimit <= 0) {
            return DEFAULT_LIMIT;
        }
        return Math.min(requestedLimit, MAX_LIMIT);
    }

    private static final class ProblemHistory {
        private int attempts;
        private boolean accepted;
        private SubmissionStatus firstFailure;
    }

    private record ScoredProblem(ProblemEntity problem, double score, String reason) {
    }
}
