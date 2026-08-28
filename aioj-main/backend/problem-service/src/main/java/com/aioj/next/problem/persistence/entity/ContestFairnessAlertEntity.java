package com.aioj.next.problem.persistence.entity;

import com.aioj.next.contract.contest.FairnessAlertSeverity;
import com.aioj.next.contract.contest.FairnessAlertStatus;
import com.aioj.next.contract.contest.FairnessAlertType;
import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;

import java.time.Instant;

@TableName("contest_fairness_alerts")
public class ContestFairnessAlertEntity {
    @TableId(type = IdType.ASSIGN_ID)
    private Long id;
    private Long contestId;
    private Long contestRunId;
    private FairnessAlertType alertType;
    private FairnessAlertSeverity severity;
    private FairnessAlertStatus status;
    private Long primaryParticipantId;
    private Long secondaryParticipantId;
    private Long plagiarismPairId;
    private String evidenceJson;
    private String teacherNote;
    private Long reviewedBy;
    private Instant reviewedAt;
    private Instant createdAt;
    private Instant updatedAt;

    public Long getId() { return id; }
    public void setId(Long id) { this.id = id; }
    public Long getContestId() { return contestId; }
    public void setContestId(Long contestId) { this.contestId = contestId; }
    public Long getContestRunId() { return contestRunId; }
    public void setContestRunId(Long contestRunId) { this.contestRunId = contestRunId; }
    public FairnessAlertType getAlertType() { return alertType; }
    public void setAlertType(FairnessAlertType alertType) { this.alertType = alertType; }
    public FairnessAlertSeverity getSeverity() { return severity; }
    public void setSeverity(FairnessAlertSeverity severity) { this.severity = severity; }
    public FairnessAlertStatus getStatus() { return status; }
    public void setStatus(FairnessAlertStatus status) { this.status = status; }
    public Long getPrimaryParticipantId() { return primaryParticipantId; }
    public void setPrimaryParticipantId(Long primaryParticipantId) { this.primaryParticipantId = primaryParticipantId; }
    public Long getSecondaryParticipantId() { return secondaryParticipantId; }
    public void setSecondaryParticipantId(Long secondaryParticipantId) { this.secondaryParticipantId = secondaryParticipantId; }
    public Long getPlagiarismPairId() { return plagiarismPairId; }
    public void setPlagiarismPairId(Long plagiarismPairId) { this.plagiarismPairId = plagiarismPairId; }
    public String getEvidenceJson() { return evidenceJson; }
    public void setEvidenceJson(String evidenceJson) { this.evidenceJson = evidenceJson; }
    public String getTeacherNote() { return teacherNote; }
    public void setTeacherNote(String teacherNote) { this.teacherNote = teacherNote; }
    public Long getReviewedBy() { return reviewedBy; }
    public void setReviewedBy(Long reviewedBy) { this.reviewedBy = reviewedBy; }
    public Instant getReviewedAt() { return reviewedAt; }
    public void setReviewedAt(Instant reviewedAt) { this.reviewedAt = reviewedAt; }
    public Instant getCreatedAt() { return createdAt; }
    public void setCreatedAt(Instant createdAt) { this.createdAt = createdAt; }
    public Instant getUpdatedAt() { return updatedAt; }
    public void setUpdatedAt(Instant updatedAt) { this.updatedAt = updatedAt; }
}
