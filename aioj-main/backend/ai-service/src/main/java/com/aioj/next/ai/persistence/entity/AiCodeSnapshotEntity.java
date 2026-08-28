package com.aioj.next.ai.persistence.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;

import java.time.LocalDateTime;

@TableName("ai_code_snapshots")
public class AiCodeSnapshotEntity {
    @TableId(type = IdType.ASSIGN_ID)
    public Long id;
    public Long userId;
    public String conversationId;
    public Long messageId;
    public String language;
    public String codeHash;
    public String codeText;
    public String codeSummary;
    public Boolean isLatest;
    public LocalDateTime createdAt;
}
