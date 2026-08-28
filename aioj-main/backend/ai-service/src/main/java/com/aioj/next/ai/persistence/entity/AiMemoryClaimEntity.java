package com.aioj.next.ai.persistence.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;

import java.math.BigDecimal;
import java.time.LocalDateTime;

@TableName("ai_memory_claims")
public class AiMemoryClaimEntity {
    @TableId(type = IdType.ASSIGN_ID)
    public Long id;
    public Long userId;
    public Long legacyMemoryId;
    public String scopeType;
    public String scopeId;
    public String category;
    public String memoryKey;
    public String valueJson;
    public String canonicalText;
    public BigDecimal confidence;
    public BigDecimal stabilityScore;
    public Integer supportCount;
    public Integer contradictionCount;
    public String sourceMode;
    public String status;
    public String sensitivityLevel;
    public String ambiguityLevel;
    public LocalDateTime firstSeenAt;
    public LocalDateTime lastSeenAt;
    public LocalDateTime lastUsedAt;
    public LocalDateTime expiresAt;
    /** W1.5: columns come from migration V59 (volatility/review_after/last_confirmed_at). */
    public String volatility;
    public LocalDateTime reviewAfter;
    public LocalDateTime lastConfirmedAt;
    public Boolean pinned;
    public Integer version;
    public LocalDateTime createdAt;
    public LocalDateTime updatedAt;
}
