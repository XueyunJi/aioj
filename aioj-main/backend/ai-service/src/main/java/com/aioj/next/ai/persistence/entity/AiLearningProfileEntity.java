package com.aioj.next.ai.persistence.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;

import java.math.BigDecimal;
import java.time.LocalDateTime;

@TableName("ai_learning_profile")
public class AiLearningProfileEntity {
    @TableId(type = IdType.ASSIGN_ID)
    public Long id;
    public Long userId;
    public String category;
    public String profileKey;
    public String label;
    public String state;
    public BigDecimal confidence;
    public Integer evidenceCount;
    public LocalDateTime disabledAt;
    public LocalDateTime deletedAt;
    public LocalDateTime lastEvidenceAt;
    public LocalDateTime createdAt;
    public LocalDateTime updatedAt;
}
