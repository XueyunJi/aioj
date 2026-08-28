package com.aioj.next.ai.persistence.entity;

import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;

import java.time.LocalDateTime;

@TableName("ai_conversations")
public class AiConversationEntity {
    @TableId
    private String id;
    private Long userId;
    private Long problemId;
    private Long contestId;
    private Long contestRunId;
    private Long contestProblemId;
    private String title;
    private String source;
    private String sourceRefType;
    private String sourceRefId;
    private String mode;
    private String summary;
    private Long recentProblemId;
    private String currentSnapshotId;
    private LocalDateTime summaryUpdatedAt;
    private LocalDateTime deletedAt;
    private LocalDateTime createdAt;
    private LocalDateTime updatedAt;

    public String getId() {
        return id;
    }

    public void setId(String id) {
        this.id = id;
    }

    public Long getUserId() {
        return userId;
    }

    public void setUserId(Long userId) {
        this.userId = userId;
    }

    public Long getProblemId() {
        return problemId;
    }

    public void setProblemId(Long problemId) {
        this.problemId = problemId;
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

    public Long getContestProblemId() {
        return contestProblemId;
    }

    public void setContestProblemId(Long contestProblemId) {
        this.contestProblemId = contestProblemId;
    }

    public String getTitle() {
        return title;
    }

    public void setTitle(String title) {
        this.title = title;
    }

    public String getSource() {
        return source;
    }

    public void setSource(String source) {
        this.source = source;
    }

    public String getSourceRefType() {
        return sourceRefType;
    }

    public void setSourceRefType(String sourceRefType) {
        this.sourceRefType = sourceRefType;
    }

    public String getSourceRefId() {
        return sourceRefId;
    }

    public void setSourceRefId(String sourceRefId) {
        this.sourceRefId = sourceRefId;
    }

    public String getMode() {
        return mode;
    }

    public void setMode(String mode) {
        this.mode = mode;
    }

    public String getSummary() {
        return summary;
    }

    public void setSummary(String summary) {
        this.summary = summary;
    }

    public Long getRecentProblemId() {
        return recentProblemId;
    }

    public void setRecentProblemId(Long recentProblemId) {
        this.recentProblemId = recentProblemId;
    }

    public String getCurrentSnapshotId() {
        return currentSnapshotId;
    }

    public void setCurrentSnapshotId(String currentSnapshotId) {
        this.currentSnapshotId = currentSnapshotId;
    }

    public LocalDateTime getSummaryUpdatedAt() {
        return summaryUpdatedAt;
    }

    public void setSummaryUpdatedAt(LocalDateTime summaryUpdatedAt) {
        this.summaryUpdatedAt = summaryUpdatedAt;
    }

    public LocalDateTime getDeletedAt() {
        return deletedAt;
    }

    public void setDeletedAt(LocalDateTime deletedAt) {
        this.deletedAt = deletedAt;
    }

    public LocalDateTime getCreatedAt() {
        return createdAt;
    }

    public void setCreatedAt(LocalDateTime createdAt) {
        this.createdAt = createdAt;
    }

    public LocalDateTime getUpdatedAt() {
        return updatedAt;
    }

    public void setUpdatedAt(LocalDateTime updatedAt) {
        this.updatedAt = updatedAt;
    }
}
