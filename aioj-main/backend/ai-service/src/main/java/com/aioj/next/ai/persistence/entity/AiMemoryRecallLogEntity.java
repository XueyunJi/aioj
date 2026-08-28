package com.aioj.next.ai.persistence.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;

import java.math.BigDecimal;
import java.time.LocalDateTime;

@TableName("ai_memory_recall_logs")
public class AiMemoryRecallLogEntity {
    @TableId(type = IdType.ASSIGN_ID)
    public Long id;
    public Long userId;
    public String conversationId;
    public Long messageId;
    public Long claimId;
    public Long legacyMemoryId;
    public BigDecimal recallScore;
    public Boolean selected;
    public Boolean usedInPrompt;
    public String reasonJson;
    public String userFeedback;
    public LocalDateTime createdAt;
}
