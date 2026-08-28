package com.aioj.next.ai.persistence.entity;

import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;

import java.time.LocalDateTime;

@TableName("ai_turns")
public class AiTurnEntity {
    @TableId
    private String id;
    private String conversationId;
    private String clientTurnId;
    private Long turnSeq;
    private String status;
    private String userMessageId;
    private String assistantMessageId;
    private String quotaReservationId;
    private String providerRequestId;
    private Long stateVersion;
    private String contextSnapshotId;
    private String contextManifestJson;
    private String policySnapshotId;
    private String outputMode;
    private String errorCode;
    private LocalDateTime createdAt;
    private LocalDateTime completedAt;

    public String getId() {
        return id;
    }

    public void setId(String id) {
        this.id = id;
    }

    public String getConversationId() {
        return conversationId;
    }

    public void setConversationId(String conversationId) {
        this.conversationId = conversationId;
    }

    public String getClientTurnId() {
        return clientTurnId;
    }

    public void setClientTurnId(String clientTurnId) {
        this.clientTurnId = clientTurnId;
    }

    public Long getTurnSeq() {
        return turnSeq;
    }

    public void setTurnSeq(Long turnSeq) {
        this.turnSeq = turnSeq;
    }

    public String getStatus() {
        return status;
    }

    public void setStatus(String status) {
        this.status = status;
    }

    public String getUserMessageId() {
        return userMessageId;
    }

    public void setUserMessageId(String userMessageId) {
        this.userMessageId = userMessageId;
    }

    public String getAssistantMessageId() {
        return assistantMessageId;
    }

    public void setAssistantMessageId(String assistantMessageId) {
        this.assistantMessageId = assistantMessageId;
    }

    public String getQuotaReservationId() {
        return quotaReservationId;
    }

    public void setQuotaReservationId(String quotaReservationId) {
        this.quotaReservationId = quotaReservationId;
    }

    public String getProviderRequestId() {
        return providerRequestId;
    }

    public void setProviderRequestId(String providerRequestId) {
        this.providerRequestId = providerRequestId;
    }

    public Long getStateVersion() {
        return stateVersion;
    }

    public void setStateVersion(Long stateVersion) {
        this.stateVersion = stateVersion;
    }

    public String getContextSnapshotId() {
        return contextSnapshotId;
    }

    public void setContextSnapshotId(String contextSnapshotId) {
        this.contextSnapshotId = contextSnapshotId;
    }

    public String getContextManifestJson() {
        return contextManifestJson;
    }

    public void setContextManifestJson(String contextManifestJson) {
        this.contextManifestJson = contextManifestJson;
    }

    public String getPolicySnapshotId() {
        return policySnapshotId;
    }

    public void setPolicySnapshotId(String policySnapshotId) {
        this.policySnapshotId = policySnapshotId;
    }

    public String getOutputMode() {
        return outputMode;
    }

    public void setOutputMode(String outputMode) {
        this.outputMode = outputMode;
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

    public LocalDateTime getCompletedAt() {
        return completedAt;
    }

    public void setCompletedAt(LocalDateTime completedAt) {
        this.completedAt = completedAt;
    }
}
