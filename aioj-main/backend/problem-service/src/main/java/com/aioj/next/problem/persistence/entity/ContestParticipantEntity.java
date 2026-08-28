package com.aioj.next.problem.persistence.entity;

import com.aioj.next.contract.contest.ContestParticipantStatus;
import com.aioj.next.contract.contest.ContestParticipantType;
import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;

import java.time.Instant;

@TableName("contest_participants")
public class ContestParticipantEntity {
    @TableId(type = IdType.ASSIGN_ID)
    private Long id;
    private Long contestId;
    private Long contestRunId;
    private Long userId;
    private ContestParticipantType participantType;
    private ContestParticipantStatus status;
    private String accountSnapshot;
    private String displayNameSnapshot;
    private String emailSnapshot;
    private Long scopeGroupId;
    private String groupNameSnapshot;
    private Instant registeredAt;
    private Instant createdAt;
    private Instant updatedAt;

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

    public Long getUserId() {
        return userId;
    }

    public void setUserId(Long userId) {
        this.userId = userId;
    }

    public ContestParticipantType getParticipantType() {
        return participantType;
    }

    public void setParticipantType(ContestParticipantType participantType) {
        this.participantType = participantType;
    }

    public ContestParticipantStatus getStatus() {
        return status;
    }

    public void setStatus(ContestParticipantStatus status) {
        this.status = status;
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

    public Instant getRegisteredAt() {
        return registeredAt;
    }

    public void setRegisteredAt(Instant registeredAt) {
        this.registeredAt = registeredAt;
    }

    public Instant getCreatedAt() {
        return createdAt;
    }

    public void setCreatedAt(Instant createdAt) {
        this.createdAt = createdAt;
    }

    public Instant getUpdatedAt() {
        return updatedAt;
    }

    public void setUpdatedAt(Instant updatedAt) {
        this.updatedAt = updatedAt;
    }
}
