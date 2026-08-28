package com.aioj.next.judge.persistence.entity;

import com.aioj.next.contract.submission.SubmissionStatus;
import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;

import java.time.Instant;

@TableName("judge_audit_logs")
public class JudgeAuditLogEntity {
    @TableId(type = IdType.ASSIGN_ID)
    private Long id;
    private Long submissionId;
    private SubmissionStatus fromStatus;
    private SubmissionStatus toStatus;
    private String workerId;
    private String message;
    private String sandboxRunId;
    private Integer signalValue;
    private Instant createdAt;

    public Long getId() {
        return id;
    }

    public void setId(Long id) {
        this.id = id;
    }

    public Long getSubmissionId() {
        return submissionId;
    }

    public void setSubmissionId(Long submissionId) {
        this.submissionId = submissionId;
    }

    public SubmissionStatus getFromStatus() {
        return fromStatus;
    }

    public void setFromStatus(SubmissionStatus fromStatus) {
        this.fromStatus = fromStatus;
    }

    public SubmissionStatus getToStatus() {
        return toStatus;
    }

    public void setToStatus(SubmissionStatus toStatus) {
        this.toStatus = toStatus;
    }

    public String getWorkerId() {
        return workerId;
    }

    public void setWorkerId(String workerId) {
        this.workerId = workerId;
    }

    public String getMessage() {
        return message;
    }

    public void setMessage(String message) {
        this.message = message;
    }

    public String getSandboxRunId() {
        return sandboxRunId;
    }

    public void setSandboxRunId(String sandboxRunId) {
        this.sandboxRunId = sandboxRunId;
    }

    public Integer getSignalValue() {
        return signalValue;
    }

    public void setSignalValue(Integer signalValue) {
        this.signalValue = signalValue;
    }

    public Instant getCreatedAt() {
        return createdAt;
    }

    public void setCreatedAt(Instant createdAt) {
        this.createdAt = createdAt;
    }
}
