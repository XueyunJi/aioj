package com.aioj.next.problem.persistence.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;

import java.time.Instant;

@TableName("submission_request_fingerprints")
public class SubmissionRequestFingerprintEntity {
    @TableId(type = IdType.ASSIGN_ID)
    private Long id;
    private Long submissionId;
    private Long contestId;
    private Long contestRunId;
    private Long contestParticipantId;
    private Long userId;
    private String ipHash;
    private String ipPrefix;
    private String userAgentHash;
    private String userAgentSummary;
    private Instant createdAt;

    public Long getId() { return id; }
    public void setId(Long id) { this.id = id; }
    public Long getSubmissionId() { return submissionId; }
    public void setSubmissionId(Long submissionId) { this.submissionId = submissionId; }
    public Long getContestId() { return contestId; }
    public void setContestId(Long contestId) { this.contestId = contestId; }
    public Long getContestRunId() { return contestRunId; }
    public void setContestRunId(Long contestRunId) { this.contestRunId = contestRunId; }
    public Long getContestParticipantId() { return contestParticipantId; }
    public void setContestParticipantId(Long contestParticipantId) { this.contestParticipantId = contestParticipantId; }
    public Long getUserId() { return userId; }
    public void setUserId(Long userId) { this.userId = userId; }
    public String getIpHash() { return ipHash; }
    public void setIpHash(String ipHash) { this.ipHash = ipHash; }
    public String getIpPrefix() { return ipPrefix; }
    public void setIpPrefix(String ipPrefix) { this.ipPrefix = ipPrefix; }
    public String getUserAgentHash() { return userAgentHash; }
    public void setUserAgentHash(String userAgentHash) { this.userAgentHash = userAgentHash; }
    public String getUserAgentSummary() { return userAgentSummary; }
    public void setUserAgentSummary(String userAgentSummary) { this.userAgentSummary = userAgentSummary; }
    public Instant getCreatedAt() { return createdAt; }
    public void setCreatedAt(Instant createdAt) { this.createdAt = createdAt; }
}
