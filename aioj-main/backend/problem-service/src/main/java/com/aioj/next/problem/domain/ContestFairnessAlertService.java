package com.aioj.next.problem.domain;

import com.aioj.next.common.api.PageResponse;
import com.aioj.next.common.error.DomainException;
import com.aioj.next.common.error.ErrorCode;
import com.aioj.next.common.security.Role;
import com.aioj.next.common.security.SecuritySupport;
import com.aioj.next.contract.contest.FairnessAlertResponse;
import com.aioj.next.contract.contest.FairnessAlertSeverity;
import com.aioj.next.contract.contest.FairnessAlertStatus;
import com.aioj.next.contract.contest.FairnessAlertType;
import com.aioj.next.contract.contest.FairnessAlertUpdateRequest;
import com.aioj.next.contract.contest.PlagiarismJobStatus;
import com.aioj.next.contract.contest.PlagiarismReviewStatus;
import com.aioj.next.contract.contest.PlagiarismRiskLevel;
import com.aioj.next.contract.submission.SubmissionStatus;
import com.aioj.next.problem.persistence.entity.ContestEntity;
import com.aioj.next.problem.persistence.entity.ContestFairnessAlertEntity;
import com.aioj.next.problem.persistence.entity.ContestParticipantEntity;
import com.aioj.next.problem.persistence.entity.ContestRunEntity;
import com.aioj.next.problem.persistence.entity.PlagiarismJobEntity;
import com.aioj.next.problem.persistence.entity.PlagiarismPairEntity;
import com.aioj.next.problem.persistence.entity.SubmissionEntity;
import com.aioj.next.problem.persistence.entity.SubmissionRequestFingerprintEntity;
import com.aioj.next.problem.persistence.mapper.ContestFairnessAlertMapper;
import com.aioj.next.problem.persistence.mapper.ContestMapper;
import com.aioj.next.problem.persistence.mapper.ContestParticipantMapper;
import com.aioj.next.problem.persistence.mapper.ContestRunMapper;
import com.aioj.next.problem.persistence.mapper.PlagiarismJobMapper;
import com.aioj.next.problem.persistence.mapper.PlagiarismPairMapper;
import com.aioj.next.problem.persistence.mapper.SubmissionMapper;
import com.aioj.next.problem.persistence.mapper.SubmissionRequestFingerprintMapper;
import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.util.StringUtils;

import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.function.Function;
import java.util.stream.Collectors;

@Service
public class ContestFairnessAlertService {
    private static final int PAGE_SIZE_LIMIT = 100;
    private static final long NEAR_TIME_SECONDS = 300;
    private static final Set<SubmissionStatus> UNFINISHED_STATUSES = Set.of(
            SubmissionStatus.QUEUED,
            SubmissionStatus.RUNNING,
            SubmissionStatus.SYSTEM_ERROR
    );
    private static final TypeReference<Map<String, Object>> MAP_TYPE = new TypeReference<>() {
    };

    private final ContestMapper contestMapper;
    private final ContestRunMapper contestRunMapper;
    private final ContestParticipantMapper participantMapper;
    private final SubmissionMapper submissionMapper;
    private final SubmissionRequestFingerprintMapper fingerprintMapper;
    private final PlagiarismJobMapper jobMapper;
    private final PlagiarismPairMapper pairMapper;
    private final ContestFairnessAlertMapper alertMapper;
    private final ObjectMapper objectMapper;

    public ContestFairnessAlertService(ContestMapper contestMapper,
                                       ContestRunMapper contestRunMapper,
                                       ContestParticipantMapper participantMapper,
                                       SubmissionMapper submissionMapper,
                                       SubmissionRequestFingerprintMapper fingerprintMapper,
                                       PlagiarismJobMapper jobMapper,
                                       PlagiarismPairMapper pairMapper,
                                       ContestFairnessAlertMapper alertMapper,
                                       ObjectMapper objectMapper) {
        this.contestMapper = contestMapper;
        this.contestRunMapper = contestRunMapper;
        this.participantMapper = participantMapper;
        this.submissionMapper = submissionMapper;
        this.fingerprintMapper = fingerprintMapper;
        this.jobMapper = jobMapper;
        this.pairMapper = pairMapper;
        this.alertMapper = alertMapper;
        this.objectMapper = objectMapper;
    }

    @Transactional
    public PageResponse<FairnessAlertResponse> rebuild(Long contestId, Long runId) {
        requireManagedContest(contestId);
        ContestRunEntity run = requireRun(contestId, runId);
        if (ContestRunStatePolicy.isArchived(run)) {
            throw new DomainException(ErrorCode.BAD_REQUEST, "Archived contest runs cannot rebuild fairness alerts");
        }
        alertMapper.delete(new LambdaQueryWrapper<ContestFairnessAlertEntity>()
                .eq(ContestFairnessAlertEntity::getContestId, contestId)
                .eq(ContestFairnessAlertEntity::getContestRunId, runId));
        Instant now = Instant.now();
        List<ContestFairnessAlertEntity> alerts = new ArrayList<>();
        alerts.addAll(unfinishedJudgingAlerts(contestId, runId, now));
        PlagiarismJobEntity job = latestCompletedJob(contestId, runId);
        if (job != null) {
            List<PlagiarismPairEntity> pairs = pairs(contestId, job.getId());
            alerts.addAll(highRiskUnreviewedAlerts(contestId, runId, pairs, now));
            alerts.addAll(repeatedHighSimilarityAlerts(contestId, runId, pairs, now));
            alerts.addAll(nearTimeAlerts(contestId, runId, pairs, now));
            alerts.addAll(fingerprintClusterAlerts(contestId, runId, pairs, now));
        } else {
            alerts.addAll(fingerprintClusterAlerts(contestId, runId, List.of(), now));
        }
        for (ContestFairnessAlertEntity alert : alerts) {
            alertMapper.insert(alert);
        }
        return list(contestId, runId, null, null, null, null, 1, 20);
    }

    public PageResponse<FairnessAlertResponse> list(Long contestId, Long runId,
                                                    FairnessAlertSeverity severity,
                                                    FairnessAlertType type,
                                                    FairnessAlertStatus status,
                                                    Long participantId,
                                                    long page,
                                                    long pageSize) {
        requireManagedContest(contestId);
        requireRun(contestId, runId);
        Page<ContestFairnessAlertEntity> result = alertMapper.selectPage(new Page<>(normalizePage(page), normalizePageSize(pageSize)),
                new LambdaQueryWrapper<ContestFairnessAlertEntity>()
                        .eq(ContestFairnessAlertEntity::getContestId, contestId)
                        .eq(ContestFairnessAlertEntity::getContestRunId, runId)
                        .eq(severity != null, ContestFairnessAlertEntity::getSeverity, severity)
                        .eq(type != null, ContestFairnessAlertEntity::getAlertType, type)
                        .eq(status != null, ContestFairnessAlertEntity::getStatus, status)
                        .and(participantId != null, wrapper -> wrapper
                                .eq(ContestFairnessAlertEntity::getPrimaryParticipantId, participantId)
                                .or()
                                .eq(ContestFairnessAlertEntity::getSecondaryParticipantId, participantId))
                        .orderByDesc(ContestFairnessAlertEntity::getSeverity)
                        .orderByDesc(ContestFairnessAlertEntity::getCreatedAt)
                        .orderByDesc(ContestFairnessAlertEntity::getId));
        Map<Long, ContestParticipantEntity> participants = participants(result.getRecords());
        return new PageResponse<>(result.getRecords().stream()
                .map(alert -> toResponse(alert, participants))
                .toList(), result.getTotal(), result.getCurrent(), result.getSize());
    }

    @Transactional
    public FairnessAlertResponse update(Long contestId, Long runId, Long alertId, FairnessAlertUpdateRequest request) {
        requireManagedContest(contestId);
        requireRun(contestId, runId);
        ContestFairnessAlertEntity alert = alertMapper.selectOne(new LambdaQueryWrapper<ContestFairnessAlertEntity>()
                .eq(ContestFairnessAlertEntity::getId, alertId)
                .eq(ContestFairnessAlertEntity::getContestId, contestId)
                .eq(ContestFairnessAlertEntity::getContestRunId, runId));
        if (alert == null) {
            throw new DomainException(ErrorCode.NOT_FOUND, "Fairness alert not found");
        }
        if (request != null && request.status() != null) {
            alert.setStatus(request.status());
            alert.setReviewedBy(SecuritySupport.currentUserId());
            alert.setReviewedAt(Instant.now());
        }
        if (request != null) {
            alert.setTeacherNote(limit(request.teacherNote(), 500));
            if (StringUtils.hasText(request.teacherNote()) && alert.getReviewedAt() == null) {
                alert.setReviewedBy(SecuritySupport.currentUserId());
                alert.setReviewedAt(Instant.now());
            }
        }
        alert.setUpdatedAt(Instant.now());
        alertMapper.updateById(alert);
        return toResponse(alert, participants(List.of(alert)));
    }

    private List<ContestFairnessAlertEntity> unfinishedJudgingAlerts(Long contestId, Long runId, Instant now) {
        long count = submissionMapper.selectCount(new LambdaQueryWrapper<SubmissionEntity>()
                .eq(SubmissionEntity::getContestId, contestId)
                .eq(SubmissionEntity::getContestRunId, runId)
                .in(SubmissionEntity::getStatus, UNFINISHED_STATUSES));
        if (count <= 0) {
            return List.of();
        }
        return List.of(alert(contestId, runId, FairnessAlertType.UNFINISHED_JUDGING, FairnessAlertSeverity.HIGH,
                null, null, null,
                Map.of("count", count, "statuses", UNFINISHED_STATUSES.stream().map(Enum::name).toList()), now));
    }

    private List<ContestFairnessAlertEntity> highRiskUnreviewedAlerts(Long contestId, Long runId, List<PlagiarismPairEntity> pairs, Instant now) {
        return pairs.stream()
                .filter(pair -> isHighRisk(pair.getRiskLevel()) && pair.getReviewStatus() == PlagiarismReviewStatus.OPEN)
                .map(pair -> alert(contestId, runId, FairnessAlertType.HIGH_RISK_UNREVIEWED,
                        pair.getRiskLevel() == PlagiarismRiskLevel.CRITICAL ? FairnessAlertSeverity.CRITICAL : FairnessAlertSeverity.HIGH,
                        pair.getLeftParticipantId(), pair.getRightParticipantId(), pair.getId(),
                        Map.of("similarity", value(pair.getSimilarity()), "riskLevel", pair.getRiskLevel().name(),
                                "language", safe(pair.getLanguage()), "contestProblemId", value(pair.getContestProblemId())), now))
                .toList();
    }

    private List<ContestFairnessAlertEntity> repeatedHighSimilarityAlerts(Long contestId, Long runId, List<PlagiarismPairEntity> pairs, Instant now) {
        Map<String, List<PlagiarismPairEntity>> grouped = pairs.stream()
                .filter(pair -> isHighRisk(pair.getRiskLevel()))
                .collect(Collectors.groupingBy(pair -> pairKey(pair.getLeftParticipantId(), pair.getRightParticipantId()),
                        LinkedHashMap::new, Collectors.toList()));
        List<ContestFairnessAlertEntity> alerts = new ArrayList<>();
        for (List<PlagiarismPairEntity> group : grouped.values()) {
            Set<Long> problems = group.stream().map(PlagiarismPairEntity::getContestProblemId).filter(Objects::nonNull).collect(Collectors.toSet());
            if (problems.size() < 2) {
                continue;
            }
            PlagiarismPairEntity first = group.get(0);
            double maxSimilarity = group.stream().map(PlagiarismPairEntity::getSimilarity).filter(Objects::nonNull).max(Double::compareTo).orElse(0.0);
            alerts.add(alert(contestId, runId, FairnessAlertType.REPEATED_HIGH_SIMILARITY, FairnessAlertSeverity.CRITICAL,
                    first.getLeftParticipantId(), first.getRightParticipantId(), first.getId(),
                    Map.of("pairCount", group.size(), "problemCount", problems.size(), "maxSimilarity", maxSimilarity,
                            "pairIds", group.stream().map(PlagiarismPairEntity::getId).toList()), now));
        }
        return alerts;
    }

    private List<ContestFairnessAlertEntity> nearTimeAlerts(Long contestId, Long runId, List<PlagiarismPairEntity> pairs, Instant now) {
        List<PlagiarismPairEntity> highPairs = pairs.stream().filter(pair -> isHighRisk(pair.getRiskLevel())).toList();
        Set<Long> submissionIds = highPairs.stream()
                .flatMap(pair -> List.of(pair.getLeftSubmissionId(), pair.getRightSubmissionId()).stream())
                .filter(Objects::nonNull)
                .collect(Collectors.toSet());
        if (submissionIds.isEmpty()) {
            return List.of();
        }
        Map<Long, SubmissionEntity> submissions = submissionMapper.selectBatchIds(submissionIds).stream()
                .collect(Collectors.toMap(SubmissionEntity::getId, Function.identity()));
        List<ContestFairnessAlertEntity> alerts = new ArrayList<>();
        for (PlagiarismPairEntity pair : highPairs) {
            SubmissionEntity left = submissions.get(pair.getLeftSubmissionId());
            SubmissionEntity right = submissions.get(pair.getRightSubmissionId());
            if (left == null || right == null || left.getCreatedAt() == null || right.getCreatedAt() == null) {
                continue;
            }
            long seconds = Math.abs(Duration.between(left.getCreatedAt(), right.getCreatedAt()).getSeconds());
            if (seconds <= NEAR_TIME_SECONDS) {
                alerts.add(alert(contestId, runId, FairnessAlertType.NEAR_TIME_HIGH_RISK_PAIR, FairnessAlertSeverity.HIGH,
                        pair.getLeftParticipantId(), pair.getRightParticipantId(), pair.getId(),
                        Map.of("deltaSeconds", seconds, "similarity", value(pair.getSimilarity()),
                                "riskLevel", pair.getRiskLevel().name(), "leftSubmittedAt", left.getCreatedAt().toString(),
                                "rightSubmittedAt", right.getCreatedAt().toString()), now));
            }
        }
        return alerts;
    }

    private List<ContestFairnessAlertEntity> fingerprintClusterAlerts(Long contestId, Long runId, List<PlagiarismPairEntity> pairs, Instant now) {
        List<SubmissionRequestFingerprintEntity> fingerprints = fingerprintMapper.selectList(new LambdaQueryWrapper<SubmissionRequestFingerprintEntity>()
                .eq(SubmissionRequestFingerprintEntity::getContestId, contestId)
                .eq(SubmissionRequestFingerprintEntity::getContestRunId, runId));
        Map<String, List<SubmissionRequestFingerprintEntity>> byIp = fingerprints.stream()
                .filter(fp -> StringUtils.hasText(fp.getIpHash()) && fp.getContestParticipantId() != null)
                .collect(Collectors.groupingBy(SubmissionRequestFingerprintEntity::getIpHash));
        Map<String, List<SubmissionRequestFingerprintEntity>> byUa = fingerprints.stream()
                .filter(fp -> StringUtils.hasText(fp.getUserAgentHash()) && fp.getContestParticipantId() != null)
                .collect(Collectors.groupingBy(SubmissionRequestFingerprintEntity::getUserAgentHash));
        Set<String> highRiskKeys = pairs.stream()
                .filter(pair -> isHighRisk(pair.getRiskLevel()))
                .map(pair -> pairKey(pair.getLeftParticipantId(), pair.getRightParticipantId()))
                .collect(Collectors.toSet());
        List<ContestFairnessAlertEntity> alerts = new ArrayList<>();
        for (List<SubmissionRequestFingerprintEntity> group : byIp.values()) {
            clusterAlert(alerts, contestId, runId, FairnessAlertType.SHARED_IP_CLUSTER, group, highRiskKeys, now);
        }
        for (List<SubmissionRequestFingerprintEntity> group : byUa.values()) {
            clusterAlert(alerts, contestId, runId, FairnessAlertType.SHARED_USER_AGENT_CLUSTER, group, highRiskKeys, now);
        }
        return alerts;
    }

    private void clusterAlert(List<ContestFairnessAlertEntity> alerts, Long contestId, Long runId, FairnessAlertType type,
                              List<SubmissionRequestFingerprintEntity> group, Set<String> highRiskKeys, Instant now) {
        Map<Long, Long> counts = group.stream()
                .collect(Collectors.groupingBy(SubmissionRequestFingerprintEntity::getContestParticipantId, LinkedHashMap::new, Collectors.counting()));
        if (counts.size() < (type == FairnessAlertType.SHARED_USER_AGENT_CLUSTER ? 3 : 2)) {
            return;
        }
        List<Long> participants = new ArrayList<>(counts.keySet());
        boolean hasHighRiskPair = false;
        for (int i = 0; i < participants.size(); i++) {
            for (int j = i + 1; j < participants.size(); j++) {
                if (highRiskKeys.contains(pairKey(participants.get(i), participants.get(j)))) {
                    hasHighRiskPair = true;
                }
            }
        }
        Long primary = participants.get(0);
        Long secondary = participants.size() > 1 ? participants.get(1) : null;
        FairnessAlertSeverity severity = hasHighRiskPair ? FairnessAlertSeverity.HIGH
                : type == FairnessAlertType.SHARED_IP_CLUSTER ? FairnessAlertSeverity.MEDIUM : FairnessAlertSeverity.LOW;
        Map<String, Object> evidence = new LinkedHashMap<>();
        evidence.put("participantIds", participants);
        evidence.put("participantCount", participants.size());
        evidence.put("submissionCount", group.size());
        evidence.put("hasHighRiskPair", hasHighRiskPair);
        if (type == FairnessAlertType.SHARED_IP_CLUSTER) {
            evidence.put("ipPrefix", group.stream().map(SubmissionRequestFingerprintEntity::getIpPrefix)
                    .filter(StringUtils::hasText).findFirst().orElse(""));
        } else {
            evidence.put("userAgentSummary", group.stream().map(SubmissionRequestFingerprintEntity::getUserAgentSummary)
                    .filter(StringUtils::hasText).findFirst().orElse(""));
        }
        alerts.add(alert(contestId, runId, type, severity, primary, secondary, null, evidence, now));
    }

    private ContestFairnessAlertEntity alert(Long contestId, Long runId, FairnessAlertType type, FairnessAlertSeverity severity,
                                             Long primaryParticipantId, Long secondaryParticipantId, Long pairId,
                                             Map<String, Object> evidence, Instant now) {
        ContestFairnessAlertEntity alert = new ContestFairnessAlertEntity();
        alert.setContestId(contestId);
        alert.setContestRunId(runId);
        alert.setAlertType(type);
        alert.setSeverity(severity);
        alert.setStatus(FairnessAlertStatus.OPEN);
        alert.setPrimaryParticipantId(primaryParticipantId);
        alert.setSecondaryParticipantId(secondaryParticipantId);
        alert.setPlagiarismPairId(pairId);
        alert.setEvidenceJson(json(evidence));
        alert.setCreatedAt(now);
        alert.setUpdatedAt(now);
        return alert;
    }

    private PlagiarismJobEntity latestCompletedJob(Long contestId, Long runId) {
        return jobMapper.selectOne(new LambdaQueryWrapper<PlagiarismJobEntity>()
                .eq(PlagiarismJobEntity::getContestId, contestId)
                .eq(PlagiarismJobEntity::getContestRunId, runId)
                .eq(PlagiarismJobEntity::getStatus, PlagiarismJobStatus.COMPLETED)
                .orderByDesc(PlagiarismJobEntity::getCompletedAt)
                .orderByDesc(PlagiarismJobEntity::getCreatedAt)
                .orderByDesc(PlagiarismJobEntity::getId)
                .last("LIMIT 1"));
    }

    private List<PlagiarismPairEntity> pairs(Long contestId, Long jobId) {
        return pairMapper.selectList(new LambdaQueryWrapper<PlagiarismPairEntity>()
                .eq(PlagiarismPairEntity::getContestId, contestId)
                .eq(PlagiarismPairEntity::getJobId, jobId));
    }

    private Map<Long, ContestParticipantEntity> participants(List<ContestFairnessAlertEntity> alerts) {
        Set<Long> ids = alerts.stream()
                .flatMap(alert -> List.of(alert.getPrimaryParticipantId(), alert.getSecondaryParticipantId()).stream())
                .filter(Objects::nonNull)
                .collect(Collectors.toSet());
        if (ids.isEmpty()) {
            return Map.of();
        }
        return participantMapper.selectBatchIds(ids).stream()
                .collect(Collectors.toMap(ContestParticipantEntity::getId, Function.identity()));
    }

    private FairnessAlertResponse toResponse(ContestFairnessAlertEntity alert, Map<Long, ContestParticipantEntity> participants) {
        Map<String, Object> evidence = evidence(alert.getEvidenceJson());
        ContestParticipantEntity primary = participants.get(alert.getPrimaryParticipantId());
        ContestParticipantEntity secondary = participants.get(alert.getSecondaryParticipantId());
        return new FairnessAlertResponse(alert.getId(), alert.getContestId(), alert.getContestRunId(),
                alert.getAlertType(), alert.getSeverity(), alert.getStatus(), alert.getPrimaryParticipantId(),
                alert.getSecondaryParticipantId(), alert.getPlagiarismPairId(), display(primary), display(secondary),
                title(alert.getAlertType()), summary(alert, evidence), evidence, alert.getTeacherNote(),
                alert.getReviewedBy(), alert.getReviewedAt(), alert.getCreatedAt(), alert.getUpdatedAt());
    }

    private String title(FairnessAlertType type) {
        return switch (type) {
            case HIGH_RISK_UNREVIEWED -> "高风险相似对未复核";
            case REPEATED_HIGH_SIMILARITY -> "多题重复高相似";
            case SHARED_IP_CLUSTER -> "同 IP 聚类";
            case SHARED_USER_AGENT_CLUSTER -> "同 User-Agent 聚类";
            case NEAR_TIME_HIGH_RISK_PAIR -> "高风险相似对提交时间接近";
            case UNFINISHED_JUDGING -> "仍有未完成判题";
        };
    }

    private String summary(ContestFairnessAlertEntity alert, Map<String, Object> evidence) {
        return switch (alert.getAlertType()) {
            case HIGH_RISK_UNREVIEWED -> "存在尚未人工复核的高风险相似提交。";
            case REPEATED_HIGH_SIMILARITY -> "同一组参赛者在多道题上出现高相似。";
            case SHARED_IP_CLUSTER -> "多个参赛者提交请求落在同一隐私化 IP 线索内。";
            case SHARED_USER_AGENT_CLUSTER -> "多个参赛者提交请求使用相同浏览器/系统摘要。";
            case NEAR_TIME_HIGH_RISK_PAIR -> "高风险相似提交的提交时间间隔较短。";
            case UNFINISHED_JUDGING -> "比赛结束后仍有未完成或系统错误提交，需先处理终榜。";
        };
    }

    private ContestEntity requireManagedContest(Long contestId) {
        ContestEntity contest = contestMapper.selectById(contestId);
        if (contest == null || contest.getDeletedAt() != null) {
            throw new DomainException(ErrorCode.NOT_FOUND, "Contest not found");
        }
        if (SecuritySupport.hasRole(Role.ADMIN)
                || (SecuritySupport.hasRole(Role.TEACHER) && Objects.equals(contest.getOwnerUserId(), SecuritySupport.currentUserId()))) {
            return contest;
        }
        throw new DomainException(ErrorCode.FORBIDDEN, "Cannot manage contest fairness alerts");
    }

    private ContestRunEntity requireRun(Long contestId, Long runId) {
        if (runId == null) {
            throw new DomainException(ErrorCode.BAD_REQUEST, "Contest run is required");
        }
        ContestRunEntity run = contestRunMapper.selectById(runId);
        if (run == null || run.getDeletedAt() != null || !Objects.equals(run.getContestId(), contestId)) {
            throw new DomainException(ErrorCode.NOT_FOUND, "Contest run not found");
        }
        return run;
    }

    private String json(Map<String, Object> value) {
        try {
            return objectMapper.writeValueAsString(value == null ? Map.of() : value);
        } catch (JsonProcessingException ex) {
            throw new DomainException(ErrorCode.INTERNAL_ERROR, "Could not serialize fairness alert evidence");
        }
    }

    private Map<String, Object> evidence(String json) {
        if (!StringUtils.hasText(json)) {
            return Map.of();
        }
        try {
            return objectMapper.readValue(json, MAP_TYPE);
        } catch (JsonProcessingException ex) {
            return Map.of();
        }
    }

    private long normalizePage(long page) {
        return Math.max(1, page);
    }

    private long normalizePageSize(long pageSize) {
        return Math.max(1, Math.min(PAGE_SIZE_LIMIT, pageSize));
    }

    private boolean isHighRisk(PlagiarismRiskLevel riskLevel) {
        return riskLevel == PlagiarismRiskLevel.HIGH || riskLevel == PlagiarismRiskLevel.CRITICAL;
    }

    private String pairKey(Long left, Long right) {
        long a = Math.min(value(left), value(right));
        long b = Math.max(value(left), value(right));
        return a + ":" + b;
    }

    private String display(ContestParticipantEntity participant) {
        if (participant == null) {
            return "";
        }
        return StringUtils.hasText(participant.getDisplayNameSnapshot())
                ? participant.getDisplayNameSnapshot()
                : participant.getAccountSnapshot();
    }

    private String limit(String value, int max) {
        if (!StringUtils.hasText(value)) {
            return null;
        }
        String trimmed = value.trim();
        return trimmed.length() <= max ? trimmed : trimmed.substring(0, max);
    }

    private String safe(String value) {
        return value == null ? "" : value;
    }

    private long value(Long value) {
        return value == null ? 0L : value;
    }

    private double value(Double value) {
        return value == null ? 0.0 : value;
    }
}
