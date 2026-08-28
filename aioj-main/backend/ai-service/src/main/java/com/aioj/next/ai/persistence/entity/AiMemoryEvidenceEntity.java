package com.aioj.next.ai.persistence.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;

import java.math.BigDecimal;
import java.time.LocalDateTime;

@TableName("ai_memory_evidence")
public class AiMemoryEvidenceEntity {
    @TableId(type = IdType.ASSIGN_ID)
    public Long id;
    public Long userId;
    public Long claimId;
    public Long candidateId;
    public String conversationId;
    public Long messageId;
    public String evidenceType;
    public String evidenceText;
    public BigDecimal confidence;
    public String reason;
    public LocalDateTime createdAt;
}
