package com.aioj.next.ai.persistence.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;

import java.time.LocalDateTime;

@TableName("ai_conversation_summaries")
public class AiConversationSummaryEntity {
    @TableId(type = IdType.ASSIGN_ID)
    public Long id;
    public Long userId;
    public String conversationId;
    public String summaryType;
    public String narrativeSummary;
    public String structuredSummary;
    public Long messageStartId;
    public Long messageEndId;
    public Integer tokenEstimate;
    public String embeddingOwnerType;
    public String embeddingOwnerId;
    public LocalDateTime createdAt;
    public LocalDateTime updatedAt;
}
