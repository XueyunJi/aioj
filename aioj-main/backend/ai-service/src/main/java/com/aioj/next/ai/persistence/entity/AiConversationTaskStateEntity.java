package com.aioj.next.ai.persistence.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import com.baomidou.mybatisplus.annotation.Version;

import java.time.LocalDateTime;

@TableName("ai_conversation_task_states")
public class AiConversationTaskStateEntity {
    @TableId(type = IdType.ASSIGN_ID)
    public Long id;
    public Long userId;
    public String conversationId;
    public Long currentProblemId;
    public String currentGoal;
    public String language;
    public Long latestCodeSnapshotId;
    public String latestErrorJson;
    public String stateJson;
    @Version
    public Long stateVersion;
    public LocalDateTime createdAt;
    public LocalDateTime updatedAt;
}
