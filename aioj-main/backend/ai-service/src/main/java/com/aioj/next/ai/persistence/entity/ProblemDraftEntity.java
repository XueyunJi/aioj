package com.aioj.next.ai.persistence.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;

import java.time.LocalDateTime;

@TableName("problem_drafts")
public class ProblemDraftEntity {
    @TableId(type = IdType.ASSIGN_ID)
    private Long id;
    private Long creatorUserId;
    private String title;
    private String difficulty;
    private String draftJson;
    private String validationStatus;
    private String validationErrors;
    private String verificationStatus;
    private String verificationReportJson;
    private Integer repairAttemptCount;
    private String lastRepairReason;
    private String model;
    private String status;
    private LocalDateTime createdAt;
    private LocalDateTime archivedAt;
    private LocalDateTime deletedAt;
    private Long deletedBy;
    private LocalDateTime reviewedAt;
    private Long reviewedBy;
    private Long importedProblemId;
    private Long refinedFromDraftId;
    private String refineNote;

    public Long getId() {
        return id;
    }

    public void setId(Long id) {
        this.id = id;
    }

    public Long getCreatorUserId() {
        return creatorUserId;
    }

    public void setCreatorUserId(Long creatorUserId) {
        this.creatorUserId = creatorUserId;
    }

    public String getTitle() {
        return title;
    }

    public void setTitle(String title) {
        this.title = title;
    }

    public String getDifficulty() {
        return difficulty;
    }

    public void setDifficulty(String difficulty) {
        this.difficulty = difficulty;
    }

    public String getDraftJson() {
        return draftJson;
    }

    public void setDraftJson(String draftJson) {
        this.draftJson = draftJson;
    }

    public String getValidationStatus() {
        return validationStatus;
    }

    public void setValidationStatus(String validationStatus) {
        this.validationStatus = validationStatus;
    }

    public String getValidationErrors() {
        return validationErrors;
    }

    public void setValidationErrors(String validationErrors) {
        this.validationErrors = validationErrors;
    }

    public String getVerificationStatus() {
        return verificationStatus;
    }

    public void setVerificationStatus(String verificationStatus) {
        this.verificationStatus = verificationStatus;
    }

    public String getVerificationReportJson() {
        return verificationReportJson;
    }

    public void setVerificationReportJson(String verificationReportJson) {
        this.verificationReportJson = verificationReportJson;
    }

    public Integer getRepairAttemptCount() {
        return repairAttemptCount;
    }

    public void setRepairAttemptCount(Integer repairAttemptCount) {
        this.repairAttemptCount = repairAttemptCount;
    }

    public String getLastRepairReason() {
        return lastRepairReason;
    }

    public void setLastRepairReason(String lastRepairReason) {
        this.lastRepairReason = lastRepairReason;
    }

    public String getModel() {
        return model;
    }

    public void setModel(String model) {
        this.model = model;
    }

    public String getStatus() {
        return status;
    }

    public void setStatus(String status) {
        this.status = status;
    }

    public LocalDateTime getCreatedAt() {
        return createdAt;
    }

    public void setCreatedAt(LocalDateTime createdAt) {
        this.createdAt = createdAt;
    }

    public LocalDateTime getArchivedAt() {
        return archivedAt;
    }

    public void setArchivedAt(LocalDateTime archivedAt) {
        this.archivedAt = archivedAt;
    }

    public LocalDateTime getDeletedAt() {
        return deletedAt;
    }

    public void setDeletedAt(LocalDateTime deletedAt) {
        this.deletedAt = deletedAt;
    }

    public Long getDeletedBy() {
        return deletedBy;
    }

    public void setDeletedBy(Long deletedBy) {
        this.deletedBy = deletedBy;
    }

    public LocalDateTime getReviewedAt() {
        return reviewedAt;
    }

    public void setReviewedAt(LocalDateTime reviewedAt) {
        this.reviewedAt = reviewedAt;
    }

    public Long getReviewedBy() {
        return reviewedBy;
    }

    public void setReviewedBy(Long reviewedBy) {
        this.reviewedBy = reviewedBy;
    }

    public Long getImportedProblemId() {
        return importedProblemId;
    }

    public void setImportedProblemId(Long importedProblemId) {
        this.importedProblemId = importedProblemId;
    }

    public Long getRefinedFromDraftId() {
        return refinedFromDraftId;
    }

    public void setRefinedFromDraftId(Long refinedFromDraftId) {
        this.refinedFromDraftId = refinedFromDraftId;
    }

    public String getRefineNote() {
        return refineNote;
    }

    public void setRefineNote(String refineNote) {
        this.refineNote = refineNote;
    }
}
