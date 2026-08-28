package com.aioj.next.problem.persistence.entity;

import com.aioj.next.contract.contest.ContestAiPolicyMode;
import com.aioj.next.contract.contest.ContestMode;
import com.aioj.next.contract.contest.ContestStatus;
import com.aioj.next.contract.contest.ContestVisibility;
import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;

import java.time.Instant;

@TableName("contests")
public class ContestEntity {
    @TableId(type = IdType.ASSIGN_ID)
    private Long id;
    private Long ownerUserId;
    private Long scopeGroupId;
    private String title;
    private String description;
    private ContestMode mode;
    private ContestStatus status;
    private ContestVisibility visibility;
    private Instant startAt;
    private Instant endAt;
    private Instant freezeAt;
    private Integer penaltyMinutes;
    private Boolean cePenalty;
    private ContestAiPolicyMode aiPolicyMode;
    private String aiPolicyNotes;
    private Instant createdAt;
    private Instant updatedAt;
    private Instant publishedAt;
    private Instant archivedAt;
    private Instant deletedAt;
    private Long deletedBy;

    public Long getId() {
        return id;
    }

    public void setId(Long id) {
        this.id = id;
    }

    public Long getOwnerUserId() {
        return ownerUserId;
    }

    public void setOwnerUserId(Long ownerUserId) {
        this.ownerUserId = ownerUserId;
    }

    public Long getScopeGroupId() {
        return scopeGroupId;
    }

    public void setScopeGroupId(Long scopeGroupId) {
        this.scopeGroupId = scopeGroupId;
    }

    public String getTitle() {
        return title;
    }

    public void setTitle(String title) {
        this.title = title;
    }

    public String getDescription() {
        return description;
    }

    public void setDescription(String description) {
        this.description = description;
    }

    public ContestMode getMode() {
        return mode;
    }

    public void setMode(ContestMode mode) {
        this.mode = mode;
    }

    public ContestStatus getStatus() {
        return status;
    }

    public void setStatus(ContestStatus status) {
        this.status = status;
    }

    public ContestVisibility getVisibility() {
        return visibility;
    }

    public void setVisibility(ContestVisibility visibility) {
        this.visibility = visibility;
    }

    public Instant getStartAt() {
        return startAt;
    }

    public void setStartAt(Instant startAt) {
        this.startAt = startAt;
    }

    public Instant getEndAt() {
        return endAt;
    }

    public void setEndAt(Instant endAt) {
        this.endAt = endAt;
    }

    public Instant getFreezeAt() {
        return freezeAt;
    }

    public void setFreezeAt(Instant freezeAt) {
        this.freezeAt = freezeAt;
    }

    public Integer getPenaltyMinutes() {
        return penaltyMinutes;
    }

    public void setPenaltyMinutes(Integer penaltyMinutes) {
        this.penaltyMinutes = penaltyMinutes;
    }

    public Boolean getCePenalty() {
        return cePenalty;
    }

    public void setCePenalty(Boolean cePenalty) {
        this.cePenalty = cePenalty;
    }

    public ContestAiPolicyMode getAiPolicyMode() {
        return aiPolicyMode;
    }

    public void setAiPolicyMode(ContestAiPolicyMode aiPolicyMode) {
        this.aiPolicyMode = aiPolicyMode;
    }

    public String getAiPolicyNotes() {
        return aiPolicyNotes;
    }

    public void setAiPolicyNotes(String aiPolicyNotes) {
        this.aiPolicyNotes = aiPolicyNotes;
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

    public Instant getPublishedAt() {
        return publishedAt;
    }

    public void setPublishedAt(Instant publishedAt) {
        this.publishedAt = publishedAt;
    }

    public Instant getArchivedAt() {
        return archivedAt;
    }

    public void setArchivedAt(Instant archivedAt) {
        this.archivedAt = archivedAt;
    }

    public Instant getDeletedAt() {
        return deletedAt;
    }

    public void setDeletedAt(Instant deletedAt) {
        this.deletedAt = deletedAt;
    }

    public Long getDeletedBy() {
        return deletedBy;
    }

    public void setDeletedBy(Long deletedBy) {
        this.deletedBy = deletedBy;
    }
}
