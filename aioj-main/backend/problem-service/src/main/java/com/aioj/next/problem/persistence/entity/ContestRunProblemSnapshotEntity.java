package com.aioj.next.problem.persistence.entity;

import com.aioj.next.contract.problem.Difficulty;
import com.aioj.next.contract.problem.ProblemVisibility;
import com.aioj.next.contract.contest.ContestProblemScoringMode;
import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;

import java.time.Instant;

@TableName("contest_run_problem_snapshots")
public class ContestRunProblemSnapshotEntity {
    @TableId(type = IdType.ASSIGN_ID)
    private Long id;
    private Long contestId;
    private Long contestRunId;
    private Long contestProblemId;
    private Long problemId;
    private String label;
    private String displayTitle;
    private String statement;
    private String notes;
    private String tags;
    private Difficulty difficulty;
    private Integer timeLimitMillis;
    private Integer memoryLimitKb;
    private Integer score;
    private ContestProblemScoringMode scoringMode;
    private ProblemVisibility visibility;
    private Integer sortOrder;
    private Instant createdAt;

    public Long getId() { return id; }
    public void setId(Long id) { this.id = id; }
    public Long getContestId() { return contestId; }
    public void setContestId(Long contestId) { this.contestId = contestId; }
    public Long getContestRunId() { return contestRunId; }
    public void setContestRunId(Long contestRunId) { this.contestRunId = contestRunId; }
    public Long getContestProblemId() { return contestProblemId; }
    public void setContestProblemId(Long contestProblemId) { this.contestProblemId = contestProblemId; }
    public Long getProblemId() { return problemId; }
    public void setProblemId(Long problemId) { this.problemId = problemId; }
    public String getLabel() { return label; }
    public void setLabel(String label) { this.label = label; }
    public String getDisplayTitle() { return displayTitle; }
    public void setDisplayTitle(String displayTitle) { this.displayTitle = displayTitle; }
    public String getStatement() { return statement; }
    public void setStatement(String statement) { this.statement = statement; }
    public String getNotes() { return notes; }
    public void setNotes(String notes) { this.notes = notes; }
    public String getTags() { return tags; }
    public void setTags(String tags) { this.tags = tags; }
    public Difficulty getDifficulty() { return difficulty; }
    public void setDifficulty(Difficulty difficulty) { this.difficulty = difficulty; }
    public Integer getTimeLimitMillis() { return timeLimitMillis; }
    public void setTimeLimitMillis(Integer timeLimitMillis) { this.timeLimitMillis = timeLimitMillis; }
    public Integer getMemoryLimitKb() { return memoryLimitKb; }
    public void setMemoryLimitKb(Integer memoryLimitKb) { this.memoryLimitKb = memoryLimitKb; }
    public Integer getScore() { return score; }
    public void setScore(Integer score) { this.score = score; }
    public ContestProblemScoringMode getScoringMode() { return scoringMode; }
    public void setScoringMode(ContestProblemScoringMode scoringMode) { this.scoringMode = scoringMode; }
    public ProblemVisibility getVisibility() { return visibility; }
    public void setVisibility(ProblemVisibility visibility) { this.visibility = visibility; }
    public Integer getSortOrder() { return sortOrder; }
    public void setSortOrder(Integer sortOrder) { this.sortOrder = sortOrder; }
    public Instant getCreatedAt() { return createdAt; }
    public void setCreatedAt(Instant createdAt) { this.createdAt = createdAt; }
}
