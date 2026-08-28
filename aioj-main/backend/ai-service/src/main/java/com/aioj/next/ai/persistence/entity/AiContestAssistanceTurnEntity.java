package com.aioj.next.ai.persistence.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;

import java.time.LocalDateTime;

/** One authoritative V3 contest-assistance ledger row per {@code ai_turn}. */
@TableName("ai_contest_assistance_turns")
public class AiContestAssistanceTurnEntity {
    @TableId(type = IdType.ASSIGN_ID)
    private Long id;
    private String turnId;
    private Long userId;
    private Long contestId;
    private Long contestRunId;
    private String conversationId;
    private String terminalStatus;
    private String interceptType;
    private String intentStatus;
    private String tokenAccountingStatus;
    private LocalDateTime startedAt;
    private LocalDateTime completedAt;
    private LocalDateTime createdAt;
    private LocalDateTime updatedAt;

    public Long getId() { return id; }
    public void setId(Long id) { this.id = id; }
    public String getTurnId() { return turnId; }
    public void setTurnId(String turnId) { this.turnId = turnId; }
    public Long getUserId() { return userId; }
    public void setUserId(Long userId) { this.userId = userId; }
    public Long getContestId() { return contestId; }
    public void setContestId(Long contestId) { this.contestId = contestId; }
    public Long getContestRunId() { return contestRunId; }
    public void setContestRunId(Long contestRunId) { this.contestRunId = contestRunId; }
    public String getConversationId() { return conversationId; }
    public void setConversationId(String conversationId) { this.conversationId = conversationId; }
    public String getTerminalStatus() { return terminalStatus; }
    public void setTerminalStatus(String terminalStatus) { this.terminalStatus = terminalStatus; }
    public String getInterceptType() { return interceptType; }
    public void setInterceptType(String interceptType) { this.interceptType = interceptType; }
    public String getIntentStatus() { return intentStatus; }
    public void setIntentStatus(String intentStatus) { this.intentStatus = intentStatus; }
    public String getTokenAccountingStatus() { return tokenAccountingStatus; }
    public void setTokenAccountingStatus(String tokenAccountingStatus) { this.tokenAccountingStatus = tokenAccountingStatus; }
    public LocalDateTime getStartedAt() { return startedAt; }
    public void setStartedAt(LocalDateTime startedAt) { this.startedAt = startedAt; }
    public LocalDateTime getCompletedAt() { return completedAt; }
    public void setCompletedAt(LocalDateTime completedAt) { this.completedAt = completedAt; }
    public LocalDateTime getCreatedAt() { return createdAt; }
    public void setCreatedAt(LocalDateTime createdAt) { this.createdAt = createdAt; }
    public LocalDateTime getUpdatedAt() { return updatedAt; }
    public void setUpdatedAt(LocalDateTime updatedAt) { this.updatedAt = updatedAt; }
}
