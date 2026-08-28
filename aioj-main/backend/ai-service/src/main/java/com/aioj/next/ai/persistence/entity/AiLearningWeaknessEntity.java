package com.aioj.next.ai.persistence.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;

import java.math.BigDecimal;
import java.time.LocalDateTime;

@TableName("ai_learning_weaknesses")
public class AiLearningWeaknessEntity {
    @TableId(type = IdType.ASSIGN_ID)
    public Long id;
    public Long userId;
    public String knowledgeNode;
    public String symptom;
    public String tags;
    public BigDecimal masteryScore;
    public Integer weakSignalCount;
    public Integer recentErrorCount;
    public BigDecimal teachingBoost;
    public String evidenceJson;
    public String status;
    public LocalDateTime lastEvidenceAt;
    public LocalDateTime createdAt;
    public LocalDateTime updatedAt;
}
