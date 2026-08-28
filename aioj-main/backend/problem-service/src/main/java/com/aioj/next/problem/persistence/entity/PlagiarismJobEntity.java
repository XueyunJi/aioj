package com.aioj.next.problem.persistence.entity;

import com.aioj.next.contract.contest.PlagiarismDetectorType;
import com.aioj.next.contract.contest.PlagiarismJobStatus;
import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;

import java.time.Instant;

@TableName("plagiarism_jobs")
public class PlagiarismJobEntity {
    @TableId(type = IdType.ASSIGN_ID)
    private Long id;
    private Long contestId;
    private Long contestRunId;
    private PlagiarismJobStatus status;
    private PlagiarismDetectorType detector;
    private String optionsJson;
    private Double minimumSimilarity;
    private Boolean includeAiAnalysis;
    private Integer totalSubmissions;
    private Integer totalPairs;
    private Integer highRiskPairs;
    private String errorMessage;
    private Long createdBy;
    private Instant startedAt;
    private Instant completedAt;
    private Instant createdAt;
    private Instant updatedAt;

    public Long getId() { return id; }
    public void setId(Long id) { this.id = id; }
    public Long getContestId() { return contestId; }
    public void setContestId(Long contestId) { this.contestId = contestId; }
    public Long getContestRunId() { return contestRunId; }
    public void setContestRunId(Long contestRunId) { this.contestRunId = contestRunId; }
    public PlagiarismJobStatus getStatus() { return status; }
    public void setStatus(PlagiarismJobStatus status) { this.status = status; }
    public PlagiarismDetectorType getDetector() { return detector; }
    public void setDetector(PlagiarismDetectorType detector) { this.detector = detector; }
    public String getOptionsJson() { return optionsJson; }
    public void setOptionsJson(String optionsJson) { this.optionsJson = optionsJson; }
    public Double getMinimumSimilarity() { return minimumSimilarity; }
    public void setMinimumSimilarity(Double minimumSimilarity) { this.minimumSimilarity = minimumSimilarity; }
    public Boolean getIncludeAiAnalysis() { return includeAiAnalysis; }
    public void setIncludeAiAnalysis(Boolean includeAiAnalysis) { this.includeAiAnalysis = includeAiAnalysis; }
    public Integer getTotalSubmissions() { return totalSubmissions; }
    public void setTotalSubmissions(Integer totalSubmissions) { this.totalSubmissions = totalSubmissions; }
    public Integer getTotalPairs() { return totalPairs; }
    public void setTotalPairs(Integer totalPairs) { this.totalPairs = totalPairs; }
    public Integer getHighRiskPairs() { return highRiskPairs; }
    public void setHighRiskPairs(Integer highRiskPairs) { this.highRiskPairs = highRiskPairs; }
    public String getErrorMessage() { return errorMessage; }
    public void setErrorMessage(String errorMessage) { this.errorMessage = errorMessage; }
    public Long getCreatedBy() { return createdBy; }
    public void setCreatedBy(Long createdBy) { this.createdBy = createdBy; }
    public Instant getStartedAt() { return startedAt; }
    public void setStartedAt(Instant startedAt) { this.startedAt = startedAt; }
    public Instant getCompletedAt() { return completedAt; }
    public void setCompletedAt(Instant completedAt) { this.completedAt = completedAt; }
    public Instant getCreatedAt() { return createdAt; }
    public void setCreatedAt(Instant createdAt) { this.createdAt = createdAt; }
    public Instant getUpdatedAt() { return updatedAt; }
    public void setUpdatedAt(Instant updatedAt) { this.updatedAt = updatedAt; }
}
