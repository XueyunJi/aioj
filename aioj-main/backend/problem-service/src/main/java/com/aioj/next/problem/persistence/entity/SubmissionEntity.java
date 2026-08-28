package com.aioj.next.problem.persistence.entity;

import com.aioj.next.contract.submission.SubmissionStatus;
import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;

import java.math.BigDecimal;
import java.time.Instant;

@TableName("submissions")
public class SubmissionEntity {
    @TableId(type = IdType.ASSIGN_ID)
    private Long id;
    private Long problemId;
    private Long userId;
    private Long contestId;
    private Long contestRunId;
    private Long contestProblemId;
    private Long contestParticipantId;
    private Long submittedAtContestMillis;
    private Boolean visibleToParticipant;
    private String language;
    private String code;
    private SubmissionStatus status;
    private String judgeMessage;
    private Long timeMillis;
    private Long memoryKb;
    private String stdoutExcerpt;
    private String stderrExcerpt;
    private Integer exitStatus;
    private Long runTimeMillis;
    private BigDecimal score;
    private BigDecimal maxScore;
    private Integer retryCount;
    private String idempotencyKey;
    private Instant createdAt;
    private Instant updatedAt;
    private Instant judgedAt;

    public Long getId() {
        return id;
    }

    public void setId(Long id) {
        this.id = id;
    }

    public Long getProblemId() {
        return problemId;
    }

    public void setProblemId(Long problemId) {
        this.problemId = problemId;
    }

    public Long getUserId() {
        return userId;
    }

    public void setUserId(Long userId) {
        this.userId = userId;
    }

    public Long getContestId() {
        return contestId;
    }

    public void setContestId(Long contestId) {
        this.contestId = contestId;
    }

    public Long getContestRunId() {
        return contestRunId;
    }

    public void setContestRunId(Long contestRunId) {
        this.contestRunId = contestRunId;
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

    public Long getSubmittedAtContestMillis() {
        return submittedAtContestMillis;
    }

    public void setSubmittedAtContestMillis(Long submittedAtContestMillis) {
        this.submittedAtContestMillis = submittedAtContestMillis;
    }

    public Boolean getVisibleToParticipant() {
        return visibleToParticipant;
    }

    public void setVisibleToParticipant(Boolean visibleToParticipant) {
        this.visibleToParticipant = visibleToParticipant;
    }

    public String getLanguage() {
        return language;
    }

    public void setLanguage(String language) {
        this.language = language;
    }

    public String getCode() {
        return code;
    }

    public void setCode(String code) {
        this.code = code;
    }

    public SubmissionStatus getStatus() {
        return status;
    }

    public void setStatus(SubmissionStatus status) {
        this.status = status;
    }

    public String getJudgeMessage() {
        return judgeMessage;
    }

    public void setJudgeMessage(String judgeMessage) {
        this.judgeMessage = judgeMessage;
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

    public String getStdoutExcerpt() {
        return stdoutExcerpt;
    }

    public void setStdoutExcerpt(String stdoutExcerpt) {
        this.stdoutExcerpt = stdoutExcerpt;
    }

    public String getStderrExcerpt() {
        return stderrExcerpt;
    }

    public void setStderrExcerpt(String stderrExcerpt) {
        this.stderrExcerpt = stderrExcerpt;
    }

    public Integer getExitStatus() {
        return exitStatus;
    }

    public void setExitStatus(Integer exitStatus) {
        this.exitStatus = exitStatus;
    }

    public Long getRunTimeMillis() {
        return runTimeMillis;
    }

    public void setRunTimeMillis(Long runTimeMillis) {
        this.runTimeMillis = runTimeMillis;
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

    public Integer getRetryCount() {
        return retryCount;
    }

    public void setRetryCount(Integer retryCount) {
        this.retryCount = retryCount;
    }

    public String getIdempotencyKey() {
        return idempotencyKey;
    }

    public void setIdempotencyKey(String idempotencyKey) {
        this.idempotencyKey = idempotencyKey;
    }

    public Instant getCreatedAt() {
        return createdAt;
    }

    public void setCreatedAt(Instant createdAt) {
        this.createdAt = createdAt;
    }

    public Instant getUpdatedAt() {
        return updatedAt;
    }

    public void setUpdatedAt(Instant updatedAt) {
        this.updatedAt = updatedAt;
    }

    public Instant getJudgedAt() {
        return judgedAt;
    }

    public void setJudgedAt(Instant judgedAt) {
        this.judgedAt = judgedAt;
    }
}
