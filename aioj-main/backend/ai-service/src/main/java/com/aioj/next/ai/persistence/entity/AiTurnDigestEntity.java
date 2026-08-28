package com.aioj.next.ai.persistence.entity;

import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;

import java.time.LocalDateTime;

@TableName("ai_turn_digests")
public class AiTurnDigestEntity {
    @TableId
    private Long id;
    private String turnId;
    private String conversationId;
    private Long userId;
    private String summary;
    private String structuredDigest;
    private String searchText;
    private String sourceHash;
    private Integer digestVersion;
    private String curatorModel;
    private String curatorPromptVersion;
    private String status;
    private Integer tokenEstimate;
    private LocalDateTime createdAt;
    private LocalDateTime updatedAt;

    public Long getId() {
        return id;
    }

    public void setId(Long id) {
        this.id = id;
    }

    public String getTurnId() {
        return turnId;
    }

    public void setTurnId(String turnId) {
        this.turnId = turnId;
    }

    public String getConversationId() {
        return conversationId;
    }

    public void setConversationId(String conversationId) {
        this.conversationId = conversationId;
    }

    public Long getUserId() {
        return userId;
    }

    public void setUserId(Long userId) {
        this.userId = userId;
    }

    public String getSummary() {
        return summary;
    }

    public void setSummary(String summary) {
        this.summary = summary;
    }

    public String getStructuredDigest() {
        return structuredDigest;
    }

    public void setStructuredDigest(String structuredDigest) {
        this.structuredDigest = structuredDigest;
    }

    public String getSearchText() {
        return searchText;
    }

    public void setSearchText(String searchText) {
        this.searchText = searchText;
    }

    public String getSourceHash() {
        return sourceHash;
    }

    public void setSourceHash(String sourceHash) {
        this.sourceHash = sourceHash;
    }

    public Integer getDigestVersion() {
        return digestVersion;
    }

    public void setDigestVersion(Integer digestVersion) {
        this.digestVersion = digestVersion;
    }

    public String getCuratorModel() {
        return curatorModel;
    }

    public void setCuratorModel(String curatorModel) {
        this.curatorModel = curatorModel;
    }

    public String getCuratorPromptVersion() {
        return curatorPromptVersion;
    }

    public void setCuratorPromptVersion(String curatorPromptVersion) {
        this.curatorPromptVersion = curatorPromptVersion;
    }

    public String getStatus() {
        return status;
    }

    public void setStatus(String status) {
        this.status = status;
    }

    public Integer getTokenEstimate() {
        return tokenEstimate;
    }

    public void setTokenEstimate(Integer tokenEstimate) {
        this.tokenEstimate = tokenEstimate;
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
