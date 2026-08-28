package com.aioj.next.ai.persistence.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;

import java.math.BigDecimal;
import java.time.LocalDateTime;

@TableName("ai_submission_analysis")
public class AiSubmissionAnalysisEntity {
    @TableId(type = IdType.ASSIGN_ID)
    public Long id;
    public Long userId;
    public Long submissionId;
    public Long problemId;
    public Long contestId;
    public Long contestRunId;
    public Long contestProblemId;
    public String status;
    public String language;
    public String codeHash;
    public String rootCauseTags;
    public String summary;
    public Long aiMessageId;
    public BigDecimal confidence;
    public LocalDateTime createdAt;
}
