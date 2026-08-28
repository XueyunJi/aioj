package com.aioj.next.problem.persistence.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;

import java.time.Instant;

@TableName("plagiarism_job_submissions")
public class PlagiarismJobSubmissionEntity {
    @TableId(type = IdType.ASSIGN_ID)
    private Long id;
    private Long jobId;
    private Long contestId;
    private Long contestProblemId;
    private Long problemId;
    private Long submissionId;
    private Long contestParticipantId;
    private Long userId;
    private String language;
    private String accountSnapshot;
    private String displayNameSnapshot;
    private String codeHash;
    private Integer codeChars;
    private Boolean included;
    private String excludeReason;
    private Instant createdAt;

    public Long getId() { return id; }
    public void setId(Long id) { this.id = id; }
    public Long getJobId() { return jobId; }
    public void setJobId(Long jobId) { this.jobId = jobId; }
    public Long getContestId() { return contestId; }
    public void setContestId(Long contestId) { this.contestId = contestId; }
    public Long getContestProblemId() { return contestProblemId; }
    public void setContestProblemId(Long contestProblemId) { this.contestProblemId = contestProblemId; }
    public Long getProblemId() { return problemId; }
    public void setProblemId(Long problemId) { this.problemId = problemId; }
    public Long getSubmissionId() { return submissionId; }
    public void setSubmissionId(Long submissionId) { this.submissionId = submissionId; }
    public Long getContestParticipantId() { return contestParticipantId; }
    public void setContestParticipantId(Long contestParticipantId) { this.contestParticipantId = contestParticipantId; }
    public Long getUserId() { return userId; }
    public void setUserId(Long userId) { this.userId = userId; }
    public String getLanguage() { return language; }
    public void setLanguage(String language) { this.language = language; }
    public String getAccountSnapshot() { return accountSnapshot; }
    public void setAccountSnapshot(String accountSnapshot) { this.accountSnapshot = accountSnapshot; }
    public String getDisplayNameSnapshot() { return displayNameSnapshot; }
    public void setDisplayNameSnapshot(String displayNameSnapshot) { this.displayNameSnapshot = displayNameSnapshot; }
    public String getCodeHash() { return codeHash; }
    public void setCodeHash(String codeHash) { this.codeHash = codeHash; }
    public Integer getCodeChars() { return codeChars; }
    public void setCodeChars(Integer codeChars) { this.codeChars = codeChars; }
    public Boolean getIncluded() { return included; }
    public void setIncluded(Boolean included) { this.included = included; }
    public String getExcludeReason() { return excludeReason; }
    public void setExcludeReason(String excludeReason) { this.excludeReason = excludeReason; }
    public Instant getCreatedAt() { return createdAt; }
    public void setCreatedAt(Instant createdAt) { this.createdAt = createdAt; }
}
