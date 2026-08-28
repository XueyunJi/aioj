package com.aioj.next.judge.persistence.entity;

import com.aioj.next.contract.submission.SubmissionStatus;
import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;

import java.math.BigDecimal;
import java.time.Instant;

@TableName("submission_case_results")
public class SubmissionCaseResultEntity {
    @TableId(type = IdType.ASSIGN_ID)
    private Long id;
    private Long submissionId;
    private Long contestId;
    private Long contestProblemId;
    private Long contestParticipantId;
    private Long testcasePackageId;
    private Long caseId;
    private Integer caseIndex;
    private String caseName;
    private String subtaskKey;
    private SubmissionStatus status;
    private BigDecimal score;
    private BigDecimal maxScore;
    private Long timeMillis;
    private Long memoryKb;
    private String message;
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

    public Long getContestId() {
        return contestId;
    }

    public void setContestId(Long contestId) {
        this.contestId = contestId;
    }

    public Long getContestProblemId() {
        return contestProblemId;
    }

    public void setContestProblemId(Long contestProblemId) {
        this.contestProblemId = contestProblemId;
    }

    public Long getContestParticipantId() {
        return contestParticipantId;
    }

    public void setContestParticipantId(Long contestParticipantId) {
        this.contestParticipantId = contestParticipantId;
    }

    public Long getTestcasePackageId() {
        return testcasePackageId;
    }

    public void setTestcasePackageId(Long testcasePackageId) {
        this.testcasePackageId = testcasePackageId;
    }

    public Long getCaseId() {
        return caseId;
    }

    public void setCaseId(Long caseId) {
        this.caseId = caseId;
    }

    public Integer getCaseIndex() {
        return caseIndex;
    }

    public void setCaseIndex(Integer caseIndex) {
        this.caseIndex = caseIndex;
    }

    public String getCaseName() {
        return caseName;
    }

    public void setCaseName(String caseName) {
        this.caseName = caseName;
    }

    public String getSubtaskKey() {
        return subtaskKey;
    }

    public void setSubtaskKey(String subtaskKey) {
        this.subtaskKey = subtaskKey;
    }

    public SubmissionStatus getStatus() {
        return status;
    }

    public void setStatus(SubmissionStatus status) {
        this.status = status;
    }

    public BigDecimal getScore() {
        return score;
    }

    public void setScore(BigDecimal score) {
        this.score = score;
    }

    public BigDecimal getMaxScore() {
        return maxScore;
    }

    public void setMaxScore(BigDecimal maxScore) {
        this.maxScore = maxScore;
    }

    public Long getTimeMillis() {
        return timeMillis;
    }

    public void setTimeMillis(Long timeMillis) {
        this.timeMillis = timeMillis;
    }

    public Long getMemoryKb() {
        return memoryKb;
    }

    public void setMemoryKb(Long memoryKb) {
        this.memoryKb = memoryKb;
    }

    public String getMessage() {
        return message;
    }

    public void setMessage(String message) {
        this.message = message;
    }

    public Instant getCreatedAt() {
        return createdAt;
    }

    public void setCreatedAt(Instant createdAt) {
        this.createdAt = createdAt;
    }
}
