package com.aioj.next.ai.persistence.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;

import java.time.LocalDateTime;

@TableName("ai_conversation_problems")
public class AiConversationProblemEntity {
    @TableId(type = IdType.ASSIGN_ID)
    private Long id;
    private String conversationId;
    private Long userId;
    private Long problemId;
    private String title;
    private String difficulty;
    private String statementSnapshot;
    private String tags;
    private String latestLanguage;
    private String latestCode;
    private String aiSolutionSummary;
    private String userFollowupsSummary;
    private LocalDateTime lastActiveAt;
    /**
     * W1.7 reference resolution: id of the user message that first brought this problem
     * into the conversation (all problems of one message share the set). Immutable once set.
     */
    private String setId;
    /** 1-based position of this problem inside its message set. Immutable once set. */
    private Integer setOrdinal;
    /** 1-based registration order across the whole conversation. Immutable once set. */
    private Integer conversationOrdinal;

    public Long getId() {
        return id;
    }

    public void setId(Long id) {
        this.id = id;
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

    public Long getProblemId() {
        return problemId;
    }

    public void setProblemId(Long problemId) {
        this.problemId = problemId;
    }

    public String getTitle() {
        return title;
    }

    public void setTitle(String title) {
        this.title = title;
    }

    public String getDifficulty() {
        return difficulty;
    }

    public void setDifficulty(String difficulty) {
        this.difficulty = difficulty;
    }

    public String getStatementSnapshot() {
        return statementSnapshot;
    }

    public void setStatementSnapshot(String statementSnapshot) {
        this.statementSnapshot = statementSnapshot;
    }

    public String getTags() {
        return tags;
    }

    public void setTags(String tags) {
        this.tags = tags;
    }

    public String getLatestLanguage() {
        return latestLanguage;
    }

    public void setLatestLanguage(String latestLanguage) {
        this.latestLanguage = latestLanguage;
    }

    public String getLatestCode() {
        return latestCode;
    }

    public void setLatestCode(String latestCode) {
        this.latestCode = latestCode;
    }

    public String getAiSolutionSummary() {
        return aiSolutionSummary;
    }

    public void setAiSolutionSummary(String aiSolutionSummary) {
        this.aiSolutionSummary = aiSolutionSummary;
    }

    public String getUserFollowupsSummary() {
        return userFollowupsSummary;
    }

    public void setUserFollowupsSummary(String userFollowupsSummary) {
        this.userFollowupsSummary = userFollowupsSummary;
    }

    public LocalDateTime getLastActiveAt() {
        return lastActiveAt;
    }

    public void setLastActiveAt(LocalDateTime lastActiveAt) {
        this.lastActiveAt = lastActiveAt;
    }

    public String getSetId() {
        return setId;
    }

    public void setSetId(String setId) {
        this.setId = setId;
    }

    public Integer getSetOrdinal() {
        return setOrdinal;
    }

    public void setSetOrdinal(Integer setOrdinal) {
        this.setOrdinal = setOrdinal;
    }

    public Integer getConversationOrdinal() {
        return conversationOrdinal;
    }

    public void setConversationOrdinal(Integer conversationOrdinal) {
        this.conversationOrdinal = conversationOrdinal;
    }
}
