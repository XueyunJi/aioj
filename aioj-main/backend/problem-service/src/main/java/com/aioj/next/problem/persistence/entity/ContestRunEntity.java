package com.aioj.next.problem.persistence.entity;

import com.aioj.next.contract.contest.ContestAiPolicyMode;
import com.aioj.next.contract.contest.ContestMode;
import com.aioj.next.contract.contest.ContestRegistrationAccess;
import com.aioj.next.contract.contest.ContestRegistrationPolicy;
import com.aioj.next.contract.contest.ContestRunKind;
import com.aioj.next.contract.contest.ContestRunStatus;
import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;

import java.time.Instant;

@TableName("contest_runs")
public class ContestRunEntity {
    @TableId(type = IdType.ASSIGN_ID)
    private Long id;
    private Long contestId;
    private ContestRunKind runKind;
    private String title;
    private ContestRunStatus status;
    private Instant startAt;
    private Instant endAt;
    private Instant freezeAt;
    private Long sourceRunId;
    private Long createdBy;
    private ContestRegistrationPolicy registrationPolicy;
    private ContestRegistrationAccess registrationAccess;
    private Boolean approvalRequired;
    private Instant registrationStartAt;
    private Instant registrationEndAt;
    private Integer maxParticipants;
    private String contestTitleSnapshot;
    private String contestDescriptionSnapshot;
    private ContestMode modeSnapshot;
    private Integer penaltyMinutesSnapshot;
    private Boolean cePenaltySnapshot;
    private ContestAiPolicyMode aiPolicyModeSnapshot;
    private String aiPolicyNotesSnapshot;
    private Instant archivedAt;
    private String archiveReason;
    private ContestRunStatus statusBeforeArchive;
    private Instant deletedAt;
    private Long deletedBy;
    private Instant publicScoreboardUnfrozenAt;
    private Long publicScoreboardUnfrozenBy;
    private Instant createdAt;
    private Instant updatedAt;

    public Long getId() { return id; }
    public void setId(Long id) { this.id = id; }
    public Long getContestId() { return contestId; }
    public void setContestId(Long contestId) { this.contestId = contestId; }
    public ContestRunKind getRunKind() { return runKind; }
    public void setRunKind(ContestRunKind runKind) { this.runKind = runKind; }
    public String getTitle() { return title; }
    public void setTitle(String title) { this.title = title; }
    public ContestRunStatus getStatus() { return status; }
    public void setStatus(ContestRunStatus status) { this.status = status; }
    public Instant getStartAt() { return startAt; }
    public void setStartAt(Instant startAt) { this.startAt = startAt; }
    public Instant getEndAt() { return endAt; }
    public void setEndAt(Instant endAt) { this.endAt = endAt; }
    public Instant getFreezeAt() { return freezeAt; }
    public void setFreezeAt(Instant freezeAt) { this.freezeAt = freezeAt; }
    public Long getSourceRunId() { return sourceRunId; }
    public void setSourceRunId(Long sourceRunId) { this.sourceRunId = sourceRunId; }
    public Long getCreatedBy() { return createdBy; }
    public void setCreatedBy(Long createdBy) { this.createdBy = createdBy; }
    public ContestRegistrationPolicy getRegistrationPolicy() { return registrationPolicy; }
    public void setRegistrationPolicy(ContestRegistrationPolicy registrationPolicy) { this.registrationPolicy = registrationPolicy; }
    public ContestRegistrationAccess getRegistrationAccess() { return registrationAccess; }
    public void setRegistrationAccess(ContestRegistrationAccess registrationAccess) { this.registrationAccess = registrationAccess; }
    public Boolean getApprovalRequired() { return approvalRequired; }
    public void setApprovalRequired(Boolean approvalRequired) { this.approvalRequired = approvalRequired; }
    public Instant getRegistrationStartAt() { return registrationStartAt; }
    public void setRegistrationStartAt(Instant registrationStartAt) { this.registrationStartAt = registrationStartAt; }
    public Instant getRegistrationEndAt() { return registrationEndAt; }
    public void setRegistrationEndAt(Instant registrationEndAt) { this.registrationEndAt = registrationEndAt; }
    public Integer getMaxParticipants() { return maxParticipants; }
    public void setMaxParticipants(Integer maxParticipants) { this.maxParticipants = maxParticipants; }
    public String getContestTitleSnapshot() { return contestTitleSnapshot; }
    public void setContestTitleSnapshot(String contestTitleSnapshot) { this.contestTitleSnapshot = contestTitleSnapshot; }
    public String getContestDescriptionSnapshot() { return contestDescriptionSnapshot; }
    public void setContestDescriptionSnapshot(String contestDescriptionSnapshot) { this.contestDescriptionSnapshot = contestDescriptionSnapshot; }
    public ContestMode getModeSnapshot() { return modeSnapshot; }
    public void setModeSnapshot(ContestMode modeSnapshot) { this.modeSnapshot = modeSnapshot; }
    public Integer getPenaltyMinutesSnapshot() { return penaltyMinutesSnapshot; }
    public void setPenaltyMinutesSnapshot(Integer penaltyMinutesSnapshot) { this.penaltyMinutesSnapshot = penaltyMinutesSnapshot; }
    public Boolean getCePenaltySnapshot() { return cePenaltySnapshot; }
    public void setCePenaltySnapshot(Boolean cePenaltySnapshot) { this.cePenaltySnapshot = cePenaltySnapshot; }
    public ContestAiPolicyMode getAiPolicyModeSnapshot() { return aiPolicyModeSnapshot; }
    public void setAiPolicyModeSnapshot(ContestAiPolicyMode aiPolicyModeSnapshot) { this.aiPolicyModeSnapshot = aiPolicyModeSnapshot; }
    public String getAiPolicyNotesSnapshot() { return aiPolicyNotesSnapshot; }
    public void setAiPolicyNotesSnapshot(String aiPolicyNotesSnapshot) { this.aiPolicyNotesSnapshot = aiPolicyNotesSnapshot; }
    public Instant getArchivedAt() { return archivedAt; }
    public void setArchivedAt(Instant archivedAt) { this.archivedAt = archivedAt; }
    public String getArchiveReason() { return archiveReason; }
    public void setArchiveReason(String archiveReason) { this.archiveReason = archiveReason; }
    public ContestRunStatus getStatusBeforeArchive() { return statusBeforeArchive; }
    public void setStatusBeforeArchive(ContestRunStatus statusBeforeArchive) { this.statusBeforeArchive = statusBeforeArchive; }
    public Instant getDeletedAt() { return deletedAt; }
    public void setDeletedAt(Instant deletedAt) { this.deletedAt = deletedAt; }
    public Long getDeletedBy() { return deletedBy; }
    public void setDeletedBy(Long deletedBy) { this.deletedBy = deletedBy; }
    public Instant getPublicScoreboardUnfrozenAt() { return publicScoreboardUnfrozenAt; }
    public void setPublicScoreboardUnfrozenAt(Instant publicScoreboardUnfrozenAt) { this.publicScoreboardUnfrozenAt = publicScoreboardUnfrozenAt; }
    public Long getPublicScoreboardUnfrozenBy() { return publicScoreboardUnfrozenBy; }
    public void setPublicScoreboardUnfrozenBy(Long publicScoreboardUnfrozenBy) { this.publicScoreboardUnfrozenBy = publicScoreboardUnfrozenBy; }
    public Instant getCreatedAt() { return createdAt; }
    public void setCreatedAt(Instant createdAt) { this.createdAt = createdAt; }
    public Instant getUpdatedAt() { return updatedAt; }
    public void setUpdatedAt(Instant updatedAt) { this.updatedAt = updatedAt; }
}
