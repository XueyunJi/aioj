package com.aioj.next.problem.domain;

import com.aioj.next.contract.contest.ContestAiPolicyRequest;
import com.aioj.next.contract.contest.ContestAiPolicyResponse;
import com.aioj.next.contract.contest.ContestParticipantStatus;
import com.aioj.next.contract.contest.ContestRunStatus;
import com.aioj.next.contract.contest.ContestStatus;
import com.aioj.next.common.error.DomainException;
import com.aioj.next.common.error.ErrorCode;
import com.aioj.next.contract.problem.ProblemVisibility;
import com.aioj.next.problem.persistence.entity.ContestEntity;
import com.aioj.next.problem.persistence.entity.ContestParticipantEntity;
import com.aioj.next.problem.persistence.entity.ContestRunEntity;
import com.aioj.next.problem.persistence.entity.ContestRunProblemSnapshotEntity;
import com.aioj.next.problem.persistence.mapper.ContestMapper;
import com.aioj.next.problem.persistence.mapper.ContestParticipantMapper;
import com.aioj.next.problem.persistence.mapper.ContestRunMapper;
import com.aioj.next.problem.persistence.mapper.ContestRunProblemSnapshotMapper;
import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import org.springframework.stereotype.Service;

import java.time.Instant;

@Service
public class ContestAiPolicyService {
    private final ContestMapper contestMapper;
    private final ContestRunMapper contestRunMapper;
    private final ContestRunProblemSnapshotMapper runProblemSnapshotMapper;
    private final ContestParticipantMapper participantMapper;

    public ContestAiPolicyService(
            ContestMapper contestMapper,
            ContestRunMapper contestRunMapper,
            ContestRunProblemSnapshotMapper runProblemSnapshotMapper,
            ContestParticipantMapper participantMapper
    ) {
        this.contestMapper = contestMapper;
        this.contestRunMapper = contestRunMapper;
        this.runProblemSnapshotMapper = runProblemSnapshotMapper;
        this.participantMapper = participantMapper;
    }

    /**
     * @deprecated ai-service no longer calls this endpoint after the contest AI
     * guard redesign; interception is driven by running-participation statement
     * snapshots instead. Kept for backward compatibility until the legacy
     * ai-service call sites are removed. Visibility is read from the run problem
     * snapshot rather than the live problems table.
     */
    @Deprecated
    public ContestAiPolicyResponse check(ContestAiPolicyRequest request) {
        if (request == null || request.userId() == null || request.problemId() == null) {
            return ContestAiPolicyResponse.inactive();
        }
        LambdaQueryWrapper<ContestRunProblemSnapshotEntity> query = new LambdaQueryWrapper<ContestRunProblemSnapshotEntity>()
                .eq(ContestRunProblemSnapshotEntity::getProblemId, request.problemId())
                .eq(request.contestId() != null, ContestRunProblemSnapshotEntity::getContestId, request.contestId())
                .eq(request.contestRunId() != null, ContestRunProblemSnapshotEntity::getContestRunId, request.contestRunId())
                .eq(request.contestProblemId() != null, ContestRunProblemSnapshotEntity::getContestProblemId, request.contestProblemId())
                .orderByDesc(ContestRunProblemSnapshotEntity::getCreatedAt)
                .last("LIMIT 50");
        Instant now = Instant.now();
        for (ContestRunProblemSnapshotEntity snapshot : runProblemSnapshotMapper.selectList(query)) {
            ContestRunEntity run = contestRunMapper.selectById(snapshot.getContestRunId());
            if (!isActiveRun(run, now)) {
                continue;
            }
            ContestEntity contest = contestMapper.selectById(snapshot.getContestId());
            if (!isPublishedContest(contest)) {
                continue;
            }
            if (!isActiveParticipant(snapshot.getContestRunId(), request.userId())) {
                continue;
            }
            if (snapshot.getVisibility() == ProblemVisibility.PRIVATE) {
                // Private contest problems never expose AI assistance; enforce in the backend.
                throw new DomainException(ErrorCode.FORBIDDEN, "AI assistance is not available for this contest problem");
            }
            return new ContestAiPolicyResponse(
                    true,
                    snapshot.getContestId(),
                    snapshot.getContestRunId(),
                    snapshot.getContestProblemId(),
                    snapshot.getProblemId(),
                    contest.getTitle(),
                    run.getTitle(),
                    snapshot.getDisplayTitle(),
                    true,
                    true,
                    true,
                    false,
                    false,
                    true,
                    12,
                    "比赛进行中只能提供思路、复杂度、边界情况和调试方向，不能提供完整可提交代码。"
            );
        }
        return ContestAiPolicyResponse.inactive();
    }

    private boolean isPublishedContest(ContestEntity contest) {
        return contest != null
                && contest.getDeletedAt() == null
                && contest.getStatus() == ContestStatus.PUBLISHED;
    }

    private boolean isActiveRun(ContestRunEntity run, Instant now) {
        return run != null
                && run.getDeletedAt() == null
                && ContestRunStatePolicy.effectiveStatus(run, now) == ContestRunStatus.RUNNING;
    }

    private boolean isActiveParticipant(Long runId, Long userId) {
        if (runId == null || userId == null) {
            return false;
        }
        Long count = participantMapper.selectCount(new LambdaQueryWrapper<ContestParticipantEntity>()
                .eq(ContestParticipantEntity::getContestRunId, runId)
                .eq(ContestParticipantEntity::getUserId, userId)
                .eq(ContestParticipantEntity::getStatus, ContestParticipantStatus.ACTIVE));
        return count != null && count > 0;
    }
}
