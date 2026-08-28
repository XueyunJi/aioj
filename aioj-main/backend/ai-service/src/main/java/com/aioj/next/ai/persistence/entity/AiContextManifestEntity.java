package com.aioj.next.ai.persistence.entity;

import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;

import java.time.LocalDateTime;

@TableName("ai_context_manifests")
public class AiContextManifestEntity {
    @TableId
    private Long id;
    private String turnId;
    private Long agentRunId;
    private Integer callSeq;
    private String model;
    private String promptVersion;
    private String policySnapshotId;
    private String sectionsJson;
    private String toolDefinitionsHash;
    private String contextHash;
    private Integer inputTokens;
    private Integer cacheHitTokens;
    private String warningsJson;
    private LocalDateTime createdAt;

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

    public Long getAgentRunId() {
        return agentRunId;
    }

    public void setAgentRunId(Long agentRunId) {
        this.agentRunId = agentRunId;
    }

    public Integer getCallSeq() {
        return callSeq;
    }

    public void setCallSeq(Integer callSeq) {
        this.callSeq = callSeq;
    }

    public String getModel() {
        return model;
    }

    public void setModel(String model) {
        this.model = model;
    }

    public String getPromptVersion() {
        return promptVersion;
    }

    public void setPromptVersion(String promptVersion) {
        this.promptVersion = promptVersion;
    }

    public String getPolicySnapshotId() {
        return policySnapshotId;
    }

    public void setPolicySnapshotId(String policySnapshotId) {
        this.policySnapshotId = policySnapshotId;
    }

    public String getSectionsJson() {
        return sectionsJson;
    }

    public void setSectionsJson(String sectionsJson) {
        this.sectionsJson = sectionsJson;
    }

    public String getToolDefinitionsHash() {
        return toolDefinitionsHash;
    }

    public void setToolDefinitionsHash(String toolDefinitionsHash) {
        this.toolDefinitionsHash = toolDefinitionsHash;
    }

    public String getContextHash() {
        return contextHash;
    }

    public void setContextHash(String contextHash) {
        this.contextHash = contextHash;
    }

    public Integer getInputTokens() {
        return inputTokens;
    }

    public void setInputTokens(Integer inputTokens) {
        this.inputTokens = inputTokens;
    }

    public Integer getCacheHitTokens() {
        return cacheHitTokens;
    }

    public void setCacheHitTokens(Integer cacheHitTokens) {
        this.cacheHitTokens = cacheHitTokens;
    }

    public String getWarningsJson() {
        return warningsJson;
    }

    public void setWarningsJson(String warningsJson) {
        this.warningsJson = warningsJson;
    }

    public LocalDateTime getCreatedAt() {
        return createdAt;
    }

    public void setCreatedAt(LocalDateTime createdAt) {
        this.createdAt = createdAt;
    }
}
