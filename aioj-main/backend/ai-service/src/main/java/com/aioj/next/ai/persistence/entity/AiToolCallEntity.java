package com.aioj.next.ai.persistence.entity;

import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;

import java.time.LocalDateTime;

@TableName("ai_tool_calls")
public class AiToolCallEntity {
    @TableId
    private Long id;
    private Long agentRunId;
    private String turnId;
    private Long userId;
    private String callId;
    private Integer callSeq;
    private String toolName;
    private String toolVersion;
    private String argumentsRedacted;
    private String policyDecisionId;
    private String status;
    private String resultClassification;
    private String resultHash;
    private Integer resultTokens;
    private Integer latencyMs;
    private String errorCode;
    private LocalDateTime createdAt;

    public Long getId() {
        return id;
    }

    public void setId(Long id) {
        this.id = id;
    }

    public Long getAgentRunId() {
        return agentRunId;
    }

    public void setAgentRunId(Long agentRunId) {
        this.agentRunId = agentRunId;
    }

    public String getTurnId() {
        return turnId;
    }

    public void setTurnId(String turnId) {
        this.turnId = turnId;
    }

    public Long getUserId() {
        return userId;
    }

    public void setUserId(Long userId) {
        this.userId = userId;
    }

    public String getCallId() {
        return callId;
    }

    public void setCallId(String callId) {
        this.callId = callId;
    }

    public Integer getCallSeq() {
        return callSeq;
    }

    public void setCallSeq(Integer callSeq) {
        this.callSeq = callSeq;
    }

    public String getToolName() {
        return toolName;
    }

    public void setToolName(String toolName) {
        this.toolName = toolName;
    }

    public String getToolVersion() {
        return toolVersion;
    }

    public void setToolVersion(String toolVersion) {
        this.toolVersion = toolVersion;
    }

    public String getArgumentsRedacted() {
        return argumentsRedacted;
    }

    public void setArgumentsRedacted(String argumentsRedacted) {
        this.argumentsRedacted = argumentsRedacted;
    }

    public String getPolicyDecisionId() {
        return policyDecisionId;
    }

    public void setPolicyDecisionId(String policyDecisionId) {
        this.policyDecisionId = policyDecisionId;
    }

    public String getStatus() {
        return status;
    }

    public void setStatus(String status) {
        this.status = status;
    }

    public String getResultClassification() {
        return resultClassification;
    }

    public void setResultClassification(String resultClassification) {
        this.resultClassification = resultClassification;
    }

    public String getResultHash() {
        return resultHash;
    }

    public void setResultHash(String resultHash) {
        this.resultHash = resultHash;
    }

    public Integer getResultTokens() {
        return resultTokens;
    }

    public void setResultTokens(Integer resultTokens) {
        this.resultTokens = resultTokens;
    }

    public Integer getLatencyMs() {
        return latencyMs;
    }

    public void setLatencyMs(Integer latencyMs) {
        this.latencyMs = latencyMs;
    }

    public String getErrorCode() {
        return errorCode;
    }

    public void setErrorCode(String errorCode) {
        this.errorCode = errorCode;
    }

    public LocalDateTime getCreatedAt() {
        return createdAt;
    }

    public void setCreatedAt(LocalDateTime createdAt) {
        this.createdAt = createdAt;
    }
}
