package com.aioj.next.ai.persistence.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;

import java.time.LocalDateTime;

@TableName("ai_clarification_answers")
public class AiClarificationAnswerEntity {
    @TableId(type = IdType.ASSIGN_ID)
    public Long id;
    public Long userId;
    public String conversationId;
    public Long requestId;
    public String requestKey;
    public String question;
    public String answerText;
    public String selectedOptionIdsJson;
    public String interpretedDeltaJson;
    public Boolean mergedToState;
    public LocalDateTime createdAt;
}
