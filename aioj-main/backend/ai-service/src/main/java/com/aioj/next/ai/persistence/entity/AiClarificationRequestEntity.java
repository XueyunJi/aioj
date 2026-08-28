package com.aioj.next.ai.persistence.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;

import java.time.LocalDateTime;

@TableName("ai_clarification_requests")
public class AiClarificationRequestEntity {
    @TableId(type = IdType.ASSIGN_ID)
    public Long id;
    public Long userId;
    public String conversationId;
    public Long sourceMessageId;
    public String requestKey;
    public String priority;
    public String question;
    public String inputSchema;
    public String defaultAction;
    public String assumption;
    public String status;
    public String answerJson;
    public LocalDateTime createdAt;
    public LocalDateTime answeredAt;
}
