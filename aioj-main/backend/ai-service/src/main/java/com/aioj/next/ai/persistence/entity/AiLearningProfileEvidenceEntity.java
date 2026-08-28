package com.aioj.next.ai.persistence.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;

import java.math.BigDecimal;
import java.time.LocalDateTime;

@TableName("ai_learning_profile_evidence")
public class AiLearningProfileEvidenceEntity {
    @TableId(type = IdType.ASSIGN_ID)
    public Long id;
    public Long userId;
    public Long profileId;
    public String evidenceType;
    public String sourceType;
    public String sourceId;
    public String summary;
    public BigDecimal confidence;
    public String codeHash;
    public LocalDateTime createdAt;
}
