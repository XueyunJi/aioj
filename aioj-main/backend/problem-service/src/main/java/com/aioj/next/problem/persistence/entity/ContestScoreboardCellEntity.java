package com.aioj.next.problem.persistence.entity;

import com.aioj.next.contract.contest.ContestScoreboardCellStatus;
import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;

import java.math.BigDecimal;
import java.time.Instant;

@TableName("contest_scoreboard_cells")
public class ContestScoreboardCellEntity {
    @TableId(type = IdType.ASSIGN_ID)
    private Long id;
    private Long snapshotId;
    private Long rowId;
    private Long contestId;
    private Long contestRunId;
    private Long participantId;
    private Long contestProblemId;
    private ContestScoreboardCellStatus status;
    private Integer attempts;
    private Integer wrongAttempts;
    private Integer pendingAttempts;
    private Long acceptedAtMillis;
    private Integer penaltyMinutes;
    private BigDecimal score;
    private BigDecimal maxScore;
    private Long bestSubmissionId;
    private Long lastScoreImprovedAtMillis;
    private Instant createdAt;

    public Long getId() {
        return id;
    }

    public void setId(Long id) {
        this.id = id;
    }

    public Long getSnapshotId() {
        return snapshotId;
    }

    public void setSnapshotId(Long snapshotId) {
        this.snapshotId = snapshotId;
    }

    public Long getRowId() {
        return rowId;
    }

    public void setRowId(Long rowId) {
        this.rowId = rowId;
    }

    public Long getContestId() {
        return contestId;
    }

    public void setContestId(Long contestId) {
        this.contestId = contestId;
    }

    public Long getContestRunId() {
        return contestRunId;
    }

    public void setContestRunId(Long contestRunId) {
        this.contestRunId = contestRunId;
    }

    public Long getParticipantId() {
        return participantId;
    }

    public void setParticipantId(Long participantId) {
        this.participantId = participantId;
    }

    public Long getContestProblemId() {
        return contestProblemId;
    }

    public void setContestProblemId(Long contestProblemId) {
        this.contestProblemId = contestProblemId;
    }

    public ContestScoreboardCellStatus getStatus() {
        return status;
    }

    public void setStatus(ContestScoreboardCellStatus status) {
        this.status = status;
    }

    public Integer getAttempts() {
        return attempts;
    }

    public void setAttempts(Integer attempts) {
        this.attempts = attempts;
    }

    public Integer getWrongAttempts() {
        return wrongAttempts;
    }

    public void setWrongAttempts(Integer wrongAttempts) {
        this.wrongAttempts = wrongAttempts;
    }

    public Integer getPendingAttempts() {
        return pendingAttempts;
    }

    public void setPendingAttempts(Integer pendingAttempts) {
        this.pendingAttempts = pendingAttempts;
    }

    public Long getAcceptedAtMillis() {
        return acceptedAtMillis;
    }

    public void setAcceptedAtMillis(Long acceptedAtMillis) {
        this.acceptedAtMillis = acceptedAtMillis;
    }

    public Integer getPenaltyMinutes() {
        return penaltyMinutes;
    }

    public void setPenaltyMinutes(Integer penaltyMinutes) {
        this.penaltyMinutes = penaltyMinutes;
    }

    public BigDecimal getScore() {
        return score;
    }

    public void setScore(BigDecimal score) {
        this.score = score;
    }

    public BigDecimal getMaxScore() {
        return maxScore;
    }

    public void setMaxScore(BigDecimal maxScore) {
        this.maxScore = maxScore;
    }

    public Long getBestSubmissionId() {
        return bestSubmissionId;
    }

    public void setBestSubmissionId(Long bestSubmissionId) {
        this.bestSubmissionId = bestSubmissionId;
    }

    public Long getLastScoreImprovedAtMillis() {
        return lastScoreImprovedAtMillis;
    }

    public void setLastScoreImprovedAtMillis(Long lastScoreImprovedAtMillis) {
        this.lastScoreImprovedAtMillis = lastScoreImprovedAtMillis;
    }

    public Instant getCreatedAt() {
        return createdAt;
    }

    public void setCreatedAt(Instant createdAt) {
        this.createdAt = createdAt;
    }
}
