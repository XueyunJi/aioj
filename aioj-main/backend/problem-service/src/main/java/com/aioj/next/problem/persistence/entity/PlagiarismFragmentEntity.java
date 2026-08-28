package com.aioj.next.problem.persistence.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;

import java.time.Instant;

@TableName("plagiarism_fragments")
public class PlagiarismFragmentEntity {
    @TableId(type = IdType.ASSIGN_ID)
    private Long id;
    private Long pairId;
    private Long jobId;
    private Integer sequenceNo;
    private Integer leftStartToken;
    private Integer rightStartToken;
    private Integer tokenLength;
    private String leftExcerpt;
    private String rightExcerpt;
    private Instant createdAt;

    public Long getId() { return id; }
    public void setId(Long id) { this.id = id; }
    public Long getPairId() { return pairId; }
    public void setPairId(Long pairId) { this.pairId = pairId; }
    public Long getJobId() { return jobId; }
    public void setJobId(Long jobId) { this.jobId = jobId; }
    public Integer getSequenceNo() { return sequenceNo; }
    public void setSequenceNo(Integer sequenceNo) { this.sequenceNo = sequenceNo; }
    public Integer getLeftStartToken() { return leftStartToken; }
    public void setLeftStartToken(Integer leftStartToken) { this.leftStartToken = leftStartToken; }
    public Integer getRightStartToken() { return rightStartToken; }
    public void setRightStartToken(Integer rightStartToken) { this.rightStartToken = rightStartToken; }
    public Integer getTokenLength() { return tokenLength; }
    public void setTokenLength(Integer tokenLength) { this.tokenLength = tokenLength; }
    public String getLeftExcerpt() { return leftExcerpt; }
    public void setLeftExcerpt(String leftExcerpt) { this.leftExcerpt = leftExcerpt; }
    public String getRightExcerpt() { return rightExcerpt; }
    public void setRightExcerpt(String rightExcerpt) { this.rightExcerpt = rightExcerpt; }
    public Instant getCreatedAt() { return createdAt; }
    public void setCreatedAt(Instant createdAt) { this.createdAt = createdAt; }
}
