package com.aioj.next.problem.persistence.entity;

import com.aioj.next.contract.contest.ContestPostmortemAiStatus;
import com.aioj.next.contract.contest.ContestPostmortemReportStatus;
import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;

import java.time.Instant;

@TableName("contest_postmortem_reports")
public class ContestPostmortemReportEntity {
    @TableId(type = IdType.ASSIGN_ID)
    private Long id;
    private Long contestId;
    private Long contestRunId;
    private ContestPostmortemReportStatus status;
    private ContestPostmortemAiStatus aiStatus;
    private Long generatedBy;
    private String statisticsJson;
    private String aiMarkdown;
    private String aiProvider;
    private String aiModel;
    private Long promptTokens;
    private Long completionTokens;
    private String errorMessage;
    private Instant createdAt;
    private Instant updatedAt;
    private Instant completedAt;

    public Long getId() { return id; }
    public void setId(Long id) { this.id = id; }
    public Long getContestId() { return contestId; }
    public void setContestId(Long contestId) { this.contestId = contestId; }
    public Long getContestRunId() { return contestRunId; }
    public void setContestRunId(Long contestRunId) { this.contestRunId = contestRunId; }
    public ContestPostmortemReportStatus getStatus() { return status; }
    public void setStatus(ContestPostmortemReportStatus status) { this.status = status; }
    public ContestPostmortemAiStatus getAiStatus() { return aiStatus; }
    public void setAiStatus(ContestPostmortemAiStatus aiStatus) { this.aiStatus = aiStatus; }
    public Long getGeneratedBy() { return generatedBy; }
    public void setGeneratedBy(Long generatedBy) { this.generatedBy = generatedBy; }
    public String getStatisticsJson() { return statisticsJson; }
    public void setStatisticsJson(String statisticsJson) { this.statisticsJson = statisticsJson; }
    public String getAiMarkdown() { return aiMarkdown; }
    public void setAiMarkdown(String aiMarkdown) { this.aiMarkdown = aiMarkdown; }
    public String getAiProvider() { return aiProvider; }
    public void setAiProvider(String aiProvider) { this.aiProvider = aiProvider; }
    public String getAiModel() { return aiModel; }
    public void setAiModel(String aiModel) { this.aiModel = aiModel; }
    public Long getPromptTokens() { return promptTokens; }
    public void setPromptTokens(Long promptTokens) { this.promptTokens = promptTokens; }
    public Long getCompletionTokens() { return completionTokens; }
    public void setCompletionTokens(Long completionTokens) { this.completionTokens = completionTokens; }
    public String getErrorMessage() { return errorMessage; }
    public void setErrorMessage(String errorMessage) { this.errorMessage = errorMessage; }
    public Instant getCreatedAt() { return createdAt; }
    public void setCreatedAt(Instant createdAt) { this.createdAt = createdAt; }
    public Instant getUpdatedAt() { return updatedAt; }
    public void setUpdatedAt(Instant updatedAt) { this.updatedAt = updatedAt; }
    public Instant getCompletedAt() { return completedAt; }
    public void setCompletedAt(Instant completedAt) { this.completedAt = completedAt; }
}
