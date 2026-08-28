package com.aioj.next.ai.persistence.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;

import java.time.LocalDateTime;

/** Immutable V63 estimate derived from pre-V3 contest-AI records. */
@TableName("ai_contest_assistance_legacy_snapshots")
public class AiContestAssistanceLegacySnapshotEntity {
    @TableId(type = IdType.AUTO)
    private Long id;
    private Long contestId;
    private Long contestRunId;
    private Long userId;
    private Long turnCount;
    private Long promptTokens;
    private Long completionTokens;
    private Long conversationCount;
    private Long interceptedCount;
    private LocalDateTime lastUsedAt;
    private LocalDateTime snapshotAt;

    public Long getId() { return id; }
    public void setId(Long id) { this.id = id; }
    public Long getContestId() { return contestId; }
    public void setContestId(Long contestId) { this.contestId = contestId; }
    public Long getContestRunId() { return contestRunId; }
    public void setContestRunId(Long contestRunId) { this.contestRunId = contestRunId; }
    public Long getUserId() { return userId; }
    public void setUserId(Long userId) { this.userId = userId; }
    public Long getTurnCount() { return turnCount; }
    public void setTurnCount(Long turnCount) { this.turnCount = turnCount; }
    public Long getPromptTokens() { return promptTokens; }
    public void setPromptTokens(Long promptTokens) { this.promptTokens = promptTokens; }
    public Long getCompletionTokens() { return completionTokens; }
    public void setCompletionTokens(Long completionTokens) { this.completionTokens = completionTokens; }
    public Long getConversationCount() { return conversationCount; }
    public void setConversationCount(Long conversationCount) { this.conversationCount = conversationCount; }
    public Long getInterceptedCount() { return interceptedCount; }
    public void setInterceptedCount(Long interceptedCount) { this.interceptedCount = interceptedCount; }
    public LocalDateTime getLastUsedAt() { return lastUsedAt; }
    public void setLastUsedAt(LocalDateTime lastUsedAt) { this.lastUsedAt = lastUsedAt; }
    public LocalDateTime getSnapshotAt() { return snapshotAt; }
    public void setSnapshotAt(LocalDateTime snapshotAt) { this.snapshotAt = snapshotAt; }
}
