package com.aioj.next.problem.persistence.entity;

import com.aioj.next.contract.contest.ContestParticipantStatus;
import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;

import java.time.Instant;

@TableName("contest_participant_snapshots")
public class ContestParticipantSnapshotEntity {
    @TableId(type = IdType.ASSIGN_ID)
    private Long id;
    private Long contestId;
    private Long contestRunId;
    private Long participantId;
    private Long userId;
    private String accountSnapshot;
    private String displayNameSnapshot;
    private String emailSnapshot;
    private Long scopeGroupId;
    private String groupNameSnapshot;
    private ContestParticipantStatus participantStatus;
    private String snapshotReason;
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

    public Long getParticipantId() {
        return participantId;
    }

    public void setParticipantId(Long participantId) {
        this.participantId = participantId;
    }

    public Long getUserId() {
        return userId;
    }

    public void setUserId(Long userId) {
        this.userId = userId;
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

    public String getEmailSnapshot() {
        return emailSnapshot;
    }

    public void setEmailSnapshot(String emailSnapshot) {
        this.emailSnapshot = emailSnapshot;
    }

    public Long getScopeGroupId() {
        return scopeGroupId;
    }

    public void setScopeGroupId(Long scopeGroupId) {
        this.scopeGroupId = scopeGroupId;
    }

    public String getGroupNameSnapshot() {
        return groupNameSnapshot;
    }

    public void setGroupNameSnapshot(String groupNameSnapshot) {
        this.groupNameSnapshot = groupNameSnapshot;
    }

    public ContestParticipantStatus getParticipantStatus() {
        return participantStatus;
    }

    public void setParticipantStatus(ContestParticipantStatus participantStatus) {
        this.participantStatus = participantStatus;
    }

    public String getSnapshotReason() {
        return snapshotReason;
    }

    public void setSnapshotReason(String snapshotReason) {
        this.snapshotReason = snapshotReason;
    }

    public Instant getCreatedAt() {
        return createdAt;
    }

    public void setCreatedAt(Instant createdAt) {
        this.createdAt = createdAt;
    }
}
