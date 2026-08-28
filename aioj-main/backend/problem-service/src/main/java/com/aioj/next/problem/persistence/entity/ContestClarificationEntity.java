package com.aioj.next.problem.persistence.entity;

import com.aioj.next.contract.contest.ContestClarificationStatus;
import com.aioj.next.contract.contest.ContestClarificationVisibility;
import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;

import java.time.Instant;

@TableName("contest_clarifications")
public class ContestClarificationEntity {
    @TableId(type = IdType.ASSIGN_ID)
    private Long id;
    private Long contestId;
    private Long contestRunId;
    private Long contestProblemId;
    private Long participantId;
    private Long userId;
    private String question;
    private ContestClarificationStatus status;
    private String answer;
    private ContestClarificationVisibility answerVisibility;
    private Long answeredBy;
    private Instant answeredAt;
    private Instant closedAt;
    private Instant createdAt;
    private Instant updatedAt;

    public Long getId() { return id; }
    public void setId(Long id) { this.id = id; }
    public Long getContestId() { return contestId; }
    public void setContestId(Long contestId) { this.contestId = contestId; }
    public Long getContestRunId() { return contestRunId; }
    public void setContestRunId(Long contestRunId) { this.contestRunId = contestRunId; }
    public Long getContestProblemId() { return contestProblemId; }
    public void setContestProblemId(Long contestProblemId) { this.contestProblemId = contestProblemId; }
    public Long getParticipantId() { return participantId; }
    public void setParticipantId(Long participantId) { this.participantId = participantId; }
    public Long getUserId() { return userId; }
    public void setUserId(Long userId) { this.userId = userId; }
    public String getQuestion() { return question; }
    public void setQuestion(String question) { this.question = question; }
    public ContestClarificationStatus getStatus() { return status; }
    public void setStatus(ContestClarificationStatus status) { this.status = status; }
    public String getAnswer() { return answer; }
    public void setAnswer(String answer) { this.answer = answer; }
    public ContestClarificationVisibility getAnswerVisibility() { return answerVisibility; }
    public void setAnswerVisibility(ContestClarificationVisibility answerVisibility) { this.answerVisibility = answerVisibility; }
    public Long getAnsweredBy() { return answeredBy; }
    public void setAnsweredBy(Long answeredBy) { this.answeredBy = answeredBy; }
    public Instant getAnsweredAt() { return answeredAt; }
    public void setAnsweredAt(Instant answeredAt) { this.answeredAt = answeredAt; }
    public Instant getClosedAt() { return closedAt; }
    public void setClosedAt(Instant closedAt) { this.closedAt = closedAt; }
    public Instant getCreatedAt() { return createdAt; }
    public void setCreatedAt(Instant createdAt) { this.createdAt = createdAt; }
    public Instant getUpdatedAt() { return updatedAt; }
    public void setUpdatedAt(Instant updatedAt) { this.updatedAt = updatedAt; }
}
