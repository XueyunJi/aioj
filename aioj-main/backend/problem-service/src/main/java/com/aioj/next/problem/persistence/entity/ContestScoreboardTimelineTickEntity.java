package com.aioj.next.problem.persistence.entity;

import com.aioj.next.contract.contest.ContestScoreboardView;
import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;

import java.time.Instant;

@TableName("contest_scoreboard_timeline_ticks")
public class ContestScoreboardTimelineTickEntity {
    @TableId(type = IdType.ASSIGN_ID)
    private Long id;
    private Long contestId;
    private Long contestRunId;
    private ContestScoreboardView viewType;
    private Long bucketMillis;
    private Long snapshotId;
    private String checksum;
    private Instant createdAt;

    public Long getId() { return id; }
    public void setId(Long id) { this.id = id; }
    public Long getContestId() { return contestId; }
    public void setContestId(Long contestId) { this.contestId = contestId; }
    public Long getContestRunId() { return contestRunId; }
    public void setContestRunId(Long contestRunId) { this.contestRunId = contestRunId; }
    public ContestScoreboardView getViewType() { return viewType; }
    public void setViewType(ContestScoreboardView viewType) { this.viewType = viewType; }
    public Long getBucketMillis() { return bucketMillis; }
    public void setBucketMillis(Long bucketMillis) { this.bucketMillis = bucketMillis; }
    public Long getSnapshotId() { return snapshotId; }
    public void setSnapshotId(Long snapshotId) { this.snapshotId = snapshotId; }
    public String getChecksum() { return checksum; }
    public void setChecksum(String checksum) { this.checksum = checksum; }
    public Instant getCreatedAt() { return createdAt; }
    public void setCreatedAt(Instant createdAt) { this.createdAt = createdAt; }
}
