package com.aioj.next.problem.persistence.entity;

import com.aioj.next.contract.contest.ContestProblemScoringMode;
import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;

import java.math.BigDecimal;
import java.time.Instant;

@TableName("contest_problem_scoring_rules")
public class ContestProblemScoringRuleEntity {
    @TableId(type = IdType.ASSIGN_ID)
    private Long id;
    private Long contestId;
    private Long contestProblemId;
    private ContestProblemScoringMode scoringMode;
    private BigDecimal maxScore;
    private String tieBreak;
    private Instant createdAt;
    private Instant updatedAt;

    public Long getId() { return id; }
    public void setId(Long id) { this.id = id; }
    public Long getContestId() { return contestId; }
    public void setContestId(Long contestId) { this.contestId = contestId; }
    public Long getContestProblemId() { return contestProblemId; }
    public void setContestProblemId(Long contestProblemId) { this.contestProblemId = contestProblemId; }
    public ContestProblemScoringMode getScoringMode() { return scoringMode; }
    public void setScoringMode(ContestProblemScoringMode scoringMode) { this.scoringMode = scoringMode; }
    public BigDecimal getMaxScore() { return maxScore; }
    public void setMaxScore(BigDecimal maxScore) { this.maxScore = maxScore; }
    public String getTieBreak() { return tieBreak; }
    public void setTieBreak(String tieBreak) { this.tieBreak = tieBreak; }
    public Instant getCreatedAt() { return createdAt; }
    public void setCreatedAt(Instant createdAt) { this.createdAt = createdAt; }
    public Instant getUpdatedAt() { return updatedAt; }
    public void setUpdatedAt(Instant updatedAt) { this.updatedAt = updatedAt; }
}
