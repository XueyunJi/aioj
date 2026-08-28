package com.aioj.next.problem.persistence.entity;

import com.aioj.next.contract.contest.ContestResolverSessionStatus;
import com.aioj.next.contract.contest.ContestScoreboardView;
import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;

import java.time.Instant;

@TableName("contest_resolver_sessions")
public class ContestResolverSessionEntity {
    @TableId(type = IdType.ASSIGN_ID)
    private Long id;
    private Long contestId;
    private Long contestRunId;
    private ContestResolverSessionStatus status;
    private String title;
    private ContestScoreboardView viewType;
    private Long freezeSnapshotId;
    private Long finalSnapshotId;
    private Integer stepCount;
    private String checksum;
    private Long createdBy;
    private Instant publishedAt;
    private Instant archivedAt;
    private ContestResolverSessionStatus statusBeforeArchive;
    private Instant deletedAt;
    private Long deletedBy;
    private Instant createdAt;
    private Instant updatedAt;

    public Long getId() { return id; }
    public void setId(Long id) { this.id = id; }
    public Long getContestId() { return contestId; }
    public void setContestId(Long contestId) { this.contestId = contestId; }
    public Long getContestRunId() { return contestRunId; }
    public void setContestRunId(Long contestRunId) { this.contestRunId = contestRunId; }
    public ContestResolverSessionStatus getStatus() { return status; }
    public void setStatus(ContestResolverSessionStatus status) { this.status = status; }
    public String getTitle() { return title; }
    public void setTitle(String title) { this.title = title; }
    public ContestScoreboardView getViewType() { return viewType; }
    public void setViewType(ContestScoreboardView viewType) { this.viewType = viewType; }
    public Long getFreezeSnapshotId() { return freezeSnapshotId; }
    public void setFreezeSnapshotId(Long freezeSnapshotId) { this.freezeSnapshotId = freezeSnapshotId; }
    public Long getFinalSnapshotId() { return finalSnapshotId; }
    public void setFinalSnapshotId(Long finalSnapshotId) { this.finalSnapshotId = finalSnapshotId; }
    public Integer getStepCount() { return stepCount; }
    public void setStepCount(Integer stepCount) { this.stepCount = stepCount; }
    public String getChecksum() { return checksum; }
    public void setChecksum(String checksum) { this.checksum = checksum; }
    public Long getCreatedBy() { return createdBy; }
    public void setCreatedBy(Long createdBy) { this.createdBy = createdBy; }
    public Instant getPublishedAt() { return publishedAt; }
    public void setPublishedAt(Instant publishedAt) { this.publishedAt = publishedAt; }
    public Instant getArchivedAt() { return archivedAt; }
    public void setArchivedAt(Instant archivedAt) { this.archivedAt = archivedAt; }
    public ContestResolverSessionStatus getStatusBeforeArchive() { return statusBeforeArchive; }
    public void setStatusBeforeArchive(ContestResolverSessionStatus statusBeforeArchive) { this.statusBeforeArchive = statusBeforeArchive; }
    public Instant getDeletedAt() { return deletedAt; }
    public void setDeletedAt(Instant deletedAt) { this.deletedAt = deletedAt; }
    public Long getDeletedBy() { return deletedBy; }
    public void setDeletedBy(Long deletedBy) { this.deletedBy = deletedBy; }
    public Instant getCreatedAt() { return createdAt; }
    public void setCreatedAt(Instant createdAt) { this.createdAt = createdAt; }
    public Instant getUpdatedAt() { return updatedAt; }
    public void setUpdatedAt(Instant updatedAt) { this.updatedAt = updatedAt; }
}
