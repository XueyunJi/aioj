package com.aioj.next.ai.persistence.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;

import java.time.LocalDateTime;

@TableName("ai_problem_draft_testcase_artifacts")
public class ProblemDraftTestcaseArtifactEntity {
    @TableId(type = IdType.ASSIGN_ID)
    private Long id;
    private Long draftId;
    private Long creatorUserId;
    private String status;
    private String fileName;
    private String storagePath;
    private Long fileSizeBytes;
    private String sha256;
    private Integer caseCount;
    private Long totalInputBytes;
    private Long totalOutputBytes;
    private Long largestCaseBytes;
    private String packageSummaryJson;
    private Long importedProblemId;
    private Long problemTestcasePackageId;
    private LocalDateTime createdAt;
    private LocalDateTime updatedAt;

    public Long getId() { return id; }
    public void setId(Long id) { this.id = id; }
    public Long getDraftId() { return draftId; }
    public void setDraftId(Long draftId) { this.draftId = draftId; }
    public Long getCreatorUserId() { return creatorUserId; }
    public void setCreatorUserId(Long creatorUserId) { this.creatorUserId = creatorUserId; }
    public String getStatus() { return status; }
    public void setStatus(String status) { this.status = status; }
    public String getFileName() { return fileName; }
    public void setFileName(String fileName) { this.fileName = fileName; }
    public String getStoragePath() { return storagePath; }
    public void setStoragePath(String storagePath) { this.storagePath = storagePath; }
    public Long getFileSizeBytes() { return fileSizeBytes; }
    public void setFileSizeBytes(Long fileSizeBytes) { this.fileSizeBytes = fileSizeBytes; }
    public String getSha256() { return sha256; }
    public void setSha256(String sha256) { this.sha256 = sha256; }
    public Integer getCaseCount() { return caseCount; }
    public void setCaseCount(Integer caseCount) { this.caseCount = caseCount; }
    public Long getTotalInputBytes() { return totalInputBytes; }
    public void setTotalInputBytes(Long totalInputBytes) { this.totalInputBytes = totalInputBytes; }
    public Long getTotalOutputBytes() { return totalOutputBytes; }
    public void setTotalOutputBytes(Long totalOutputBytes) { this.totalOutputBytes = totalOutputBytes; }
    public Long getLargestCaseBytes() { return largestCaseBytes; }
    public void setLargestCaseBytes(Long largestCaseBytes) { this.largestCaseBytes = largestCaseBytes; }
    public String getPackageSummaryJson() { return packageSummaryJson; }
    public void setPackageSummaryJson(String packageSummaryJson) { this.packageSummaryJson = packageSummaryJson; }
    public Long getImportedProblemId() { return importedProblemId; }
    public void setImportedProblemId(Long importedProblemId) { this.importedProblemId = importedProblemId; }
    public Long getProblemTestcasePackageId() { return problemTestcasePackageId; }
    public void setProblemTestcasePackageId(Long problemTestcasePackageId) { this.problemTestcasePackageId = problemTestcasePackageId; }
    public LocalDateTime getCreatedAt() { return createdAt; }
    public void setCreatedAt(LocalDateTime createdAt) { this.createdAt = createdAt; }
    public LocalDateTime getUpdatedAt() { return updatedAt; }
    public void setUpdatedAt(LocalDateTime updatedAt) { this.updatedAt = updatedAt; }
}
