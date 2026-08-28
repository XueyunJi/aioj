package com.aioj.next.ai.persistence.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;

import java.time.LocalDateTime;

/** One reported model-usage measurement belonging to a V3 contest assistance turn. */
@TableName("ai_contest_assistance_model_usages")
public class AiContestAssistanceModelUsageEntity {
    @TableId(type = IdType.ASSIGN_ID)
    private Long id;
    private Long assistanceTurnId;
    private String turnId;
    private String usageKey;
    private String usageSource;
    private String provider;
    private String model;
    private Long promptTokens;
    private Long completionTokens;
    private String usageStatus;
    private LocalDateTime createdAt;

    public Long getId() { return id; }
    public void setId(Long id) { this.id = id; }
    public Long getAssistanceTurnId() { return assistanceTurnId; }
    public void setAssistanceTurnId(Long assistanceTurnId) { this.assistanceTurnId = assistanceTurnId; }
    public String getTurnId() { return turnId; }
    public void setTurnId(String turnId) { this.turnId = turnId; }
    public String getUsageKey() { return usageKey; }
    public void setUsageKey(String usageKey) { this.usageKey = usageKey; }
    public String getUsageSource() { return usageSource; }
    public void setUsageSource(String usageSource) { this.usageSource = usageSource; }
    public String getProvider() { return provider; }
    public void setProvider(String provider) { this.provider = provider; }
    public String getModel() { return model; }
    public void setModel(String model) { this.model = model; }
    public Long getPromptTokens() { return promptTokens; }
    public void setPromptTokens(Long promptTokens) { this.promptTokens = promptTokens; }
    public Long getCompletionTokens() { return completionTokens; }
    public void setCompletionTokens(Long completionTokens) { this.completionTokens = completionTokens; }
    public String getUsageStatus() { return usageStatus; }
    public void setUsageStatus(String usageStatus) { this.usageStatus = usageStatus; }
    public LocalDateTime getCreatedAt() { return createdAt; }
    public void setCreatedAt(LocalDateTime createdAt) { this.createdAt = createdAt; }
}
