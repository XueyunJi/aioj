package com.aioj.next.ai.persistence.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;

import java.math.BigDecimal;
import java.time.LocalDateTime;

@TableName("ai_memory_candidates")
public class AiMemoryCandidateEntity {
    @TableId(type = IdType.ASSIGN_ID)
    public Long id;
    public Long userId;
    public String category;
    public String memoryKey;
    public String canonicalText;
    public String valueJson;
    public String scopeType;
    public String scopeId;
    public String evidenceType;
    public BigDecimal extractionConfidence;
    public BigDecimal writeScore;
    public Boolean isLongTerm;
    public Boolean isProblemSpecific;
    public Boolean isHypothetical;
    public Boolean isQuoted;
    public Boolean needsConfirmation;
    public String qualityFlags;
    public String ambiguityFlags;
    public String sourceConversationId;
    public Long sourceMessageId;
    public String status;
    public String rejectedReason;
    public LocalDateTime createdAt;
    public LocalDateTime updatedAt;
}
