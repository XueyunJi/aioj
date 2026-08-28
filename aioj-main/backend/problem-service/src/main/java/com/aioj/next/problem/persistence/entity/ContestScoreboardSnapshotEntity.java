package com.aioj.next.problem.persistence.entity;

import com.aioj.next.contract.contest.ContestScoreboardSnapshotKind;
import com.aioj.next.contract.contest.ContestScoreboardView;
import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;

import java.time.Instant;

@TableName("contest_scoreboard_snapshots")
public class ContestScoreboardSnapshotEntity {
    @TableId(type = IdType.ASSIGN_ID)
    private Long id;
    private Long contestId;
    private Long contestRunId;
    private ContestScoreboardSnapshotKind snapshotKind;
    private ContestScoreboardView viewType;
    private Instant snapshotAt;
    private Long contestTimeMillis;
    private Integer scoringVersion;
    private Boolean frozen;
    private String rowsJson;
    private String checksum;
    private Long createdBy;
    private Instant createdAt;

    public Long getId() {
        return id;
    }

    public void setId(Long id) {
        this.id = id;
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

    public ContestScoreboardSnapshotKind getSnapshotKind() {
        return snapshotKind;
    }

    public void setSnapshotKind(ContestScoreboardSnapshotKind snapshotKind) {
        this.snapshotKind = snapshotKind;
    }

    public ContestScoreboardView getViewType() {
        return viewType;
    }

    public void setViewType(ContestScoreboardView viewType) {
        this.viewType = viewType;
    }

    public Instant getSnapshotAt() {
        return snapshotAt;
    }

    public void setSnapshotAt(Instant snapshotAt) {
        this.snapshotAt = snapshotAt;
    }

    public Long getContestTimeMillis() {
        return contestTimeMillis;
    }

    public void setContestTimeMillis(Long contestTimeMillis) {
        this.contestTimeMillis = contestTimeMillis;
    }

    public Integer getScoringVersion() {
        return scoringVersion;
    }

    public void setScoringVersion(Integer scoringVersion) {
        this.scoringVersion = scoringVersion;
    }

    public Boolean getFrozen() {
        return frozen;
    }

    public void setFrozen(Boolean frozen) {
        this.frozen = frozen;
    }

    public String getRowsJson() {
        return rowsJson;
    }

    public void setRowsJson(String rowsJson) {
        this.rowsJson = rowsJson;
    }

    public String getChecksum() {
        return checksum;
    }

    public void setChecksum(String checksum) {
        this.checksum = checksum;
    }

    public Long getCreatedBy() {
        return createdBy;
    }

    public void setCreatedBy(Long createdBy) {
        this.createdBy = createdBy;
    }

    public Instant getCreatedAt() {
        return createdAt;
    }

    public void setCreatedAt(Instant createdAt) {
        this.createdAt = createdAt;
    }
}
