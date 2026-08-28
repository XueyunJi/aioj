package com.aioj.next.problem.persistence.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;

import java.math.BigDecimal;
import java.time.Instant;

@TableName("contest_scoreboard_rows")
public class ContestScoreboardRowEntity {
    @TableId(type = IdType.ASSIGN_ID)
    private Long id;
    private Long snapshotId;
    private Long contestId;
    private Long contestRunId;
    private Long participantId;
    private Integer rankNo;
    private String accountSnapshot;
    private String displayNameSnapshot;
    private Integer solvedCount;
    private Integer penaltyMinutes;
    private Long lastAcceptedAtMillis;
    private BigDecimal totalScore;
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

    public Integer getRankNo() {
        return rankNo;
    }

    public void setRankNo(Integer rankNo) {
        this.rankNo = rankNo;
    }

    public String getAccountSnapshot() {
        return accountSnapshot;
    }

    public void setAccountSnapshot(String accountSnapshot) {
        this.accountSnapshot = accountSnapshot;
    }

    public String getDisplayNameSnapshot() {
        return displayNameSnapshot;
    }

    public void setDisplayNameSnapshot(String displayNameSnapshot) {
        this.displayNameSnapshot = displayNameSnapshot;
    }

    public Integer getSolvedCount() {
        return solvedCount;
    }

    public void setSolvedCount(Integer solvedCount) {
        this.solvedCount = solvedCount;
    }

    public Integer getPenaltyMinutes() {
        return penaltyMinutes;
    }

    public void setPenaltyMinutes(Integer penaltyMinutes) {
        this.penaltyMinutes = penaltyMinutes;
    }

    public Long getLastAcceptedAtMillis() {
        return lastAcceptedAtMillis;
    }

    public void setLastAcceptedAtMillis(Long lastAcceptedAtMillis) {
        this.lastAcceptedAtMillis = lastAcceptedAtMillis;
    }

    public BigDecimal getTotalScore() {
        return totalScore;
    }

    public void setTotalScore(BigDecimal totalScore) {
        this.totalScore = totalScore;
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
