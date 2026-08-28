package com.aioj.next.ai.persistence.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;

import java.time.LocalDateTime;

@TableName("ai_memory_versions")
public class AiMemoryVersionEntity {
    @TableId(type = IdType.ASSIGN_ID)
    public Long id;
    public Long userId;
    public Long claimId;
    public Integer version;
    public String canonicalText;
    public String valueJson;
    public String status;
    public String changeReason;
    public Long sourceCandidateId;
    public LocalDateTime createdAt;
}
