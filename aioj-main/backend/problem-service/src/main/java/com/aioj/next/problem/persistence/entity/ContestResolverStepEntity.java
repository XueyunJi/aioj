package com.aioj.next.problem.persistence.entity;

import com.aioj.next.contract.contest.ContestResolverStepType;
import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;

import java.time.Instant;

@TableName("contest_resolver_steps")
public class ContestResolverStepEntity {
    @TableId(type = IdType.ASSIGN_ID)
    private Long id;
    private Long resolverSessionId;
    private Long contestId;
    private Long contestRunId;
    private Integer stepOrder;
    private ContestResolverStepType stepType;
    private Long participantId;
    private Long contestProblemId;
    private Long submissionId;
    private String payloadJson;
    private String scoreboardJson;
    private Instant createdAt;

    public Long getId() { return id; }
    public void setId(Long id) { this.id = id; }
    public Long getResolverSessionId() { return resolverSessionId; }
    public void setResolverSessionId(Long resolverSessionId) { this.resolverSessionId = resolverSessionId; }
    public Long getContestId() { return contestId; }
    public void setContestId(Long contestId) { this.contestId = contestId; }
    public Long getContestRunId() { return contestRunId; }
    public void setContestRunId(Long contestRunId) { this.contestRunId = contestRunId; }
    public Integer getStepOrder() { return stepOrder; }
    public void setStepOrder(Integer stepOrder) { this.stepOrder = stepOrder; }
    public ContestResolverStepType getStepType() { return stepType; }
    public void setStepType(ContestResolverStepType stepType) { this.stepType = stepType; }
    public Long getParticipantId() { return participantId; }
    public void setParticipantId(Long participantId) { this.participantId = participantId; }
    public Long getContestProblemId() { return contestProblemId; }
    public void setContestProblemId(Long contestProblemId) { this.contestProblemId = contestProblemId; }
    public Long getSubmissionId() { return submissionId; }
    public void setSubmissionId(Long submissionId) { this.submissionId = submissionId; }
    public String getPayloadJson() { return payloadJson; }
    public void setPayloadJson(String payloadJson) { this.payloadJson = payloadJson; }
    public String getScoreboardJson() { return scoreboardJson; }
    public void setScoreboardJson(String scoreboardJson) { this.scoreboardJson = scoreboardJson; }
    public Instant getCreatedAt() { return createdAt; }
    public void setCreatedAt(Instant createdAt) { this.createdAt = createdAt; }
}
