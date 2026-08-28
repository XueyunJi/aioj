package com.aioj.next.problem.persistence.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;

import java.math.BigDecimal;
import java.time.Instant;

@TableName("problem_subtasks")
public class ProblemSubtaskEntity {
    @TableId(type = IdType.ASSIGN_ID)
    private Long id;
    private Long problemId;
    private Long testcasePackageId;
    private String subtaskKey;
    private String title;
    private BigDecimal score;
    private Integer sortOrder;
    private Instant createdAt;

    public Long getId() { return id; }
    public void setId(Long id) { this.id = id; }
    public Long getProblemId() { return problemId; }
    public void setProblemId(Long problemId) { this.problemId = problemId; }
    public Long getTestcasePackageId() { return testcasePackageId; }
    public void setTestcasePackageId(Long testcasePackageId) { this.testcasePackageId = testcasePackageId; }
    public String getSubtaskKey() { return subtaskKey; }
    public void setSubtaskKey(String subtaskKey) { this.subtaskKey = subtaskKey; }
    public String getTitle() { return title; }
    public void setTitle(String title) { this.title = title; }
    public BigDecimal getScore() { return score; }
    public void setScore(BigDecimal score) { this.score = score; }
    public Integer getSortOrder() { return sortOrder; }
    public void setSortOrder(Integer sortOrder) { this.sortOrder = sortOrder; }
    public Instant getCreatedAt() { return createdAt; }
    public void setCreatedAt(Instant createdAt) { this.createdAt = createdAt; }
}
