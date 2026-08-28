package com.aioj.next.problem.domain;

import com.aioj.next.common.error.DomainException;
import com.aioj.next.common.error.ErrorCode;
import com.aioj.next.common.security.Role;
import com.aioj.next.common.security.SecuritySupport;
import com.aioj.next.contract.contest.ContestPlagiarismGraphResponse;
import com.aioj.next.contract.contest.PlagiarismJobStatus;
import com.aioj.next.contract.contest.PlagiarismReviewStatus;
import com.aioj.next.contract.contest.PlagiarismRiskLevel;
import com.aioj.next.problem.persistence.entity.ContestEntity;
import com.aioj.next.problem.persistence.entity.ContestProblemEntity;
import com.aioj.next.problem.persistence.entity.ContestRunEntity;
import com.aioj.next.problem.persistence.entity.PlagiarismJobEntity;
import com.aioj.next.problem.persistence.entity.PlagiarismJobSubmissionEntity;
import com.aioj.next.problem.persistence.entity.PlagiarismPairEntity;
import com.aioj.next.problem.persistence.mapper.ContestMapper;
import com.aioj.next.problem.persistence.mapper.ContestProblemMapper;
import com.aioj.next.problem.persistence.mapper.ContestRunMapper;
import com.aioj.next.problem.persistence.mapper.PlagiarismJobMapper;
import com.aioj.next.problem.persistence.mapper.PlagiarismJobSubmissionMapper;
import com.aioj.next.problem.persistence.mapper.PlagiarismPairMapper;
import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.function.Function;
import java.util.stream.Collectors;

@Service
public class ContestPlagiarismGraphService {
    private final ContestMapper contestMapper;
    private final ContestRunMapper contestRunMapper;
    private final ContestProblemMapper contestProblemMapper;
    private final PlagiarismJobMapper jobMapper;
    private final PlagiarismPairMapper pairMapper;
    private final PlagiarismJobSubmissionMapper jobSubmissionMapper;

    public ContestPlagiarismGraphService(ContestMapper contestMapper,
                                          ContestRunMapper contestRunMapper,
                                          ContestProblemMapper contestProblemMapper,
                                          PlagiarismJobMapper jobMapper,
                                          PlagiarismPairMapper pairMapper,
                                          PlagiarismJobSubmissionMapper jobSubmissionMapper) {
        this.contestMapper = contestMapper;
        this.contestRunMapper = contestRunMapper;
        this.contestProblemMapper = contestProblemMapper;
        this.jobMapper = jobMapper;
        this.pairMapper = pairMapper;
        this.jobSubmissionMapper = jobSubmissionMapper;
    }

    public ContestPlagiarismGraphResponse graph(Long contestId, Long runId, Long jobId, Long contestProblemId,
                                                String language, PlagiarismRiskLevel riskLevel,
                                                PlagiarismReviewStatus reviewStatus) {
        requireManagedContest(contestId);
        requireRun(contestId, runId);
        List<PlagiarismJobEntity> jobs = jobs(contestId, runId, jobId);
        if (jobs.isEmpty()) {
            return new ContestPlagiarismGraphResponse(contestId, runId,
                    new ContestPlagiarismGraphResponse.Summary(0, 0, 0, 0, 0),
                    List.of(), List.of(), List.of());
        }
        List<Long> jobIds = jobs.stream().map(PlagiarismJobEntity::getId).toList();
        String normalizedLanguage = normalizeLanguage(language);
        List<PlagiarismPairEntity> pairs = pairMapper.selectList(new LambdaQueryWrapper<PlagiarismPairEntity>()
                .eq(PlagiarismPairEntity::getContestId, contestId)
                .in(PlagiarismPairEntity::getJobId, jobIds)
                .eq(contestProblemId != null, PlagiarismPairEntity::getContestProblemId, contestProblemId)
                .eq(normalizedLanguage != null, PlagiarismPairEntity::getLanguage, normalizedLanguage)
                .eq(riskLevel != null, PlagiarismPairEntity::getRiskLevel, riskLevel)
                .eq(reviewStatus != null, PlagiarismPairEntity::getReviewStatus, reviewStatus)
                .orderByDesc(PlagiarismPairEntity::getSimilarity)
                .orderByDesc(PlagiarismPairEntity::getCreatedAt));
        return toResponse(contestId, runId, pairs);
    }

    private ContestPlagiarismGraphResponse toResponse(Long contestId, Long runId, List<PlagiarismPairEntity> pairs) {
        Map<Long, PlagiarismJobSubmissionEntity> submissions = jobSubmissions(pairs);
        Map<Long, ContestProblemEntity> problems = contestProblems(contestId, pairs);
        Map<Long, NodeAccumulator> nodes = new LinkedHashMap<>();
        List<ContestPlagiarismGraphResponse.Edge> edges = new ArrayList<>();
        Map<String, ClusterAccumulator> clusters = new LinkedHashMap<>();
        int highRisk = 0;
        int criticalRisk = 0;
        for (PlagiarismPairEntity pair : pairs) {
            PlagiarismJobSubmissionEntity left = submissions.get(pair.getLeftJobSubmissionId());
            PlagiarismJobSubmissionEntity right = submissions.get(pair.getRightJobSubmissionId());
            if (left == null || right == null) {
                continue;
            }
            ContestProblemEntity problem = problems.get(pair.getContestProblemId());
            boolean high = isHighRisk(pair.getRiskLevel());
            boolean critical = pair.getRiskLevel() == PlagiarismRiskLevel.CRITICAL;
            if (high) {
                highRisk++;
            }
            if (critical) {
                criticalRisk++;
            }
            nodes.computeIfAbsent(left.getContestParticipantId(), ignored -> new NodeAccumulator(left))
                    .record(right.getContestParticipantId(), high, critical);
            nodes.computeIfAbsent(right.getContestParticipantId(), ignored -> new NodeAccumulator(right))
                    .record(left.getContestParticipantId(), high, critical);
            edges.add(new ContestPlagiarismGraphResponse.Edge(pair.getId(), pair.getJobId(), pair.getContestProblemId(),
                    problem == null ? "" : problem.getLabel(),
                    problem == null ? "" : safeTitle(problem),
                    pair.getLanguage(), pair.getLeftParticipantId(), pair.getRightParticipantId(),
                    left.getDisplayNameSnapshot(), right.getDisplayNameSnapshot(),
                    safeDouble(pair.getSimilarity()), value(pair.getMatchedTokens()),
                    pair.getRiskLevel(), pair.getReviewStatus(), pair.getAiSummary()));
            String key = clusterKey(pair.getLeftParticipantId(), pair.getRightParticipantId());
            clusters.computeIfAbsent(key, ignored -> new ClusterAccumulator(left, right))
                    .record(pair, high);
        }
        List<ContestPlagiarismGraphResponse.Node> nodeResponses = nodes.values().stream()
                .map(NodeAccumulator::toResponse)
                .sorted(Comparator.comparingInt(ContestPlagiarismGraphResponse.Node::highRiskPairCount).reversed()
                        .thenComparing(Comparator.comparingInt(ContestPlagiarismGraphResponse.Node::pairCount).reversed()))
                .toList();
        List<ContestPlagiarismGraphResponse.Cluster> clusterResponses = clusters.values().stream()
                .filter(cluster -> cluster.pairCount > 1 || cluster.highRiskPairCount > 0)
                .map(ClusterAccumulator::toResponse)
                .sorted(Comparator.comparingInt(ContestPlagiarismGraphResponse.Cluster::highRiskPairCount).reversed()
                        .thenComparing(Comparator.comparingInt(ContestPlagiarismGraphResponse.Cluster::pairCount).reversed()))
                .toList();
        int repeatedPairs = (int) clusterResponses.stream().filter(cluster -> cluster.pairCount() > 1).count();
        return new ContestPlagiarismGraphResponse(contestId, runId,
                new ContestPlagiarismGraphResponse.Summary(nodeResponses.size(), edges.size(), highRisk, criticalRisk, repeatedPairs),
                nodeResponses, edges, clusterResponses);
    }

    private List<PlagiarismJobEntity> jobs(Long contestId, Long runId, Long jobId) {
        if (jobId != null) {
            PlagiarismJobEntity job = jobMapper.selectOne(new LambdaQueryWrapper<PlagiarismJobEntity>()
                    .eq(PlagiarismJobEntity::getId, jobId)
                    .eq(PlagiarismJobEntity::getContestId, contestId)
                    .eq(PlagiarismJobEntity::getContestRunId, runId));
            if (job == null) {
                throw new DomainException(ErrorCode.NOT_FOUND, "Plagiarism job not found");
            }
            return job.getStatus() == PlagiarismJobStatus.COMPLETED ? List.of(job) : List.of();
        }
        PlagiarismJobEntity latest = jobMapper.selectOne(new LambdaQueryWrapper<PlagiarismJobEntity>()
                .eq(PlagiarismJobEntity::getContestId, contestId)
                .eq(PlagiarismJobEntity::getContestRunId, runId)
                .eq(PlagiarismJobEntity::getStatus, PlagiarismJobStatus.COMPLETED)
                .orderByDesc(PlagiarismJobEntity::getCompletedAt)
                .orderByDesc(PlagiarismJobEntity::getCreatedAt)
                .orderByDesc(PlagiarismJobEntity::getId)
                .last("LIMIT 1"));
        return latest == null ? List.of() : List.of(latest);
    }

    private Map<Long, PlagiarismJobSubmissionEntity> jobSubmissions(List<PlagiarismPairEntity> pairs) {
        Set<Long> ids = new HashSet<>();
        for (PlagiarismPairEntity pair : pairs) {
            ids.add(pair.getLeftJobSubmissionId());
            ids.add(pair.getRightJobSubmissionId());
        }
        if (ids.isEmpty()) {
            return Map.of();
        }
        return jobSubmissionMapper.selectBatchIds(ids).stream()
                .collect(Collectors.toMap(PlagiarismJobSubmissionEntity::getId, Function.identity()));
    }

    private Map<Long, ContestProblemEntity> contestProblems(Long contestId, List<PlagiarismPairEntity> pairs) {
        Set<Long> ids = pairs.stream().map(PlagiarismPairEntity::getContestProblemId).filter(Objects::nonNull).collect(Collectors.toSet());
        if (ids.isEmpty()) {
            return Map.of();
        }
        return contestProblemMapper.selectList(new LambdaQueryWrapper<ContestProblemEntity>()
                        .eq(ContestProblemEntity::getContestId, contestId)
                        .in(ContestProblemEntity::getId, ids))
                .stream()
                .collect(Collectors.toMap(ContestProblemEntity::getId, Function.identity()));
    }

    private ContestEntity requireManagedContest(Long contestId) {
        ContestEntity contest = contestMapper.selectOne(new LambdaQueryWrapper<ContestEntity>()
                .eq(ContestEntity::getId, contestId)
                .isNull(ContestEntity::getDeletedAt));
        if (contest == null) {
            throw new DomainException(ErrorCode.NOT_FOUND, "Contest not found");
        }
        if (SecuritySupport.hasRole(Role.ADMIN)) {
            return contest;
        }
        if (SecuritySupport.hasRole(Role.TEACHER) && contest.getOwnerUserId().equals(SecuritySupport.currentUserId())) {
            return contest;
        }
        throw new DomainException(ErrorCode.FORBIDDEN, "Cannot manage contest plagiarism graph");
    }

    private ContestRunEntity requireRun(Long contestId, Long runId) {
        if (runId == null) {
            throw new DomainException(ErrorCode.BAD_REQUEST, "Contest run is required");
        }
        ContestRunEntity run = contestRunMapper.selectOne(new LambdaQueryWrapper<ContestRunEntity>()
                .eq(ContestRunEntity::getId, runId)
                .eq(ContestRunEntity::getContestId, contestId)
                .isNull(ContestRunEntity::getDeletedAt));
        if (run == null) {
            throw new DomainException(ErrorCode.NOT_FOUND, "Contest run not found");
        }
        return run;
    }

    private String normalizeLanguage(String language) {
        if (!StringUtils.hasText(language)) {
            return null;
        }
        return language.trim().toLowerCase(Locale.ROOT);
    }

    private boolean isHighRisk(PlagiarismRiskLevel riskLevel) {
        return riskLevel == PlagiarismRiskLevel.HIGH || riskLevel == PlagiarismRiskLevel.CRITICAL;
    }

    private String safeTitle(ContestProblemEntity problem) {
        return StringUtils.hasText(problem.getDisplayTitle()) ? problem.getDisplayTitle() : "#" + problem.getProblemId();
    }

    private String clusterKey(Long left, Long right) {
        long a = Math.min(value(left), value(right));
        long b = Math.max(value(left), value(right));
        return a + ":" + b;
    }

    private int value(Integer value) {
        return value == null ? 0 : value;
    }

    private long value(Long value) {
        return value == null ? 0 : value;
    }

    private double safeDouble(Double value) {
        return value == null ? 0.0 : value;
    }

    private static final class NodeAccumulator {
        private final PlagiarismJobSubmissionEntity submission;
        private final Set<Long> connected = new HashSet<>();
        private int pairCount;
        private int highRiskPairCount;
        private int criticalRiskPairCount;

        private NodeAccumulator(PlagiarismJobSubmissionEntity submission) {
            this.submission = submission;
        }

        private void record(Long otherParticipantId, boolean highRisk, boolean criticalRisk) {
            pairCount++;
            if (otherParticipantId != null) {
                connected.add(otherParticipantId);
            }
            if (highRisk) {
                highRiskPairCount++;
            }
            if (criticalRisk) {
                criticalRiskPairCount++;
            }
        }

        private ContestPlagiarismGraphResponse.Node toResponse() {
            return new ContestPlagiarismGraphResponse.Node(submission.getContestParticipantId(), submission.getUserId(),
                    submission.getAccountSnapshot(), submission.getDisplayNameSnapshot(), pairCount, highRiskPairCount,
                    criticalRiskPairCount, connected.size());
        }
    }

    private static final class ClusterAccumulator {
        private final PlagiarismJobSubmissionEntity left;
        private final PlagiarismJobSubmissionEntity right;
        private final List<Long> pairIds = new ArrayList<>();
        private int pairCount;
        private int highRiskPairCount;
        private double maxSimilarity;

        private ClusterAccumulator(PlagiarismJobSubmissionEntity left, PlagiarismJobSubmissionEntity right) {
            this.left = left;
            this.right = right;
        }

        private void record(PlagiarismPairEntity pair, boolean highRisk) {
            pairCount++;
            if (highRisk) {
                highRiskPairCount++;
            }
            maxSimilarity = Math.max(maxSimilarity, pair.getSimilarity() == null ? 0.0 : pair.getSimilarity());
            pairIds.add(pair.getId());
        }

        private ContestPlagiarismGraphResponse.Cluster toResponse() {
            return new ContestPlagiarismGraphResponse.Cluster(left.getContestParticipantId(), right.getContestParticipantId(),
                    left.getDisplayNameSnapshot(), right.getDisplayNameSnapshot(), pairCount, highRiskPairCount,
                    maxSimilarity, pairIds);
        }
    }
}
