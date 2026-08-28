package com.aioj.next.problem.persistence.entity;

import com.aioj.next.contract.problem.Difficulty;
import com.aioj.next.contract.problem.ProblemVisibility;
import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;

import java.time.Instant;
import java.math.BigDecimal;

@TableName("problems")
public class ProblemEntity {
    @TableId(type = IdType.ASSIGN_ID)
    private Long id;
    private String title;
    private Difficulty difficulty;
    private String statement;
    private String notes;
    private String tags;
    private Integer timeLimitMillis;
    private BigDecimal cppTimeLimitMultiplier;
    private BigDecimal pythonTimeLimitMultiplier;
    private BigDecimal javaTimeLimitMultiplier;
    private Integer memoryLimitKb;
    private Boolean aiGenerated;
    private ProblemVisibility visibility;
    private Long createdBy;
    private Instant createdAt;
    private Instant updatedAt;
    private Boolean deleted;
    private Instant archivedAt;
    private Instant deletedAt;
    private Long deletedBy;

    public Long getId() {
        return id;
    }

    public void setId(Long id) {
        this.id = id;
    }

    public String getTitle() {
        return title;
    }

    public void setTitle(String title) {
        this.title = title;
    }

    public Difficulty getDifficulty() {
        return difficulty;
    }

    public void setDifficulty(Difficulty difficulty) {
        this.difficulty = difficulty;
    }

    public String getStatement() {
        return statement;
    }

    public void setStatement(String statement) {
        this.statement = statement;
    }

    public String getNotes() {
        return notes;
    }

    public void setNotes(String notes) {
        this.notes = notes;
    }

    public String getTags() {
        return tags;
    }

    public void setTags(String tags) {
        this.tags = tags;
    }

    public Integer getTimeLimitMillis() {
        return timeLimitMillis;
    }

    public void setTimeLimitMillis(Integer timeLimitMillis) {
        this.timeLimitMillis = timeLimitMillis;
    }

    public BigDecimal getCppTimeLimitMultiplier() {
        return cppTimeLimitMultiplier;
    }

    public void setCppTimeLimitMultiplier(BigDecimal cppTimeLimitMultiplier) {
        this.cppTimeLimitMultiplier = cppTimeLimitMultiplier;
    }

    public BigDecimal getPythonTimeLimitMultiplier() {
        return pythonTimeLimitMultiplier;
    }

    public void setPythonTimeLimitMultiplier(BigDecimal pythonTimeLimitMultiplier) {
        this.pythonTimeLimitMultiplier = pythonTimeLimitMultiplier;
    }

    public BigDecimal getJavaTimeLimitMultiplier() {
        return javaTimeLimitMultiplier;
    }

    public void setJavaTimeLimitMultiplier(BigDecimal javaTimeLimitMultiplier) {
        this.javaTimeLimitMultiplier = javaTimeLimitMultiplier;
    }

    public Integer getMemoryLimitKb() {
        return memoryLimitKb;
    }

    public void setMemoryLimitKb(Integer memoryLimitKb) {
        this.memoryLimitKb = memoryLimitKb;
    }

    public Boolean getAiGenerated() {
        return aiGenerated;
    }

    public void setAiGenerated(Boolean aiGenerated) {
        this.aiGenerated = aiGenerated;
    }

    public ProblemVisibility getVisibility() {
        return visibility;
    }

    public void setVisibility(ProblemVisibility visibility) {
        this.visibility = visibility;
    }

    public Long getCreatedBy() {
        return createdBy;
    }

    public void setCreatedBy(Long createdBy) {
        this.createdBy = createdBy;
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

    public Boolean getDeleted() {
        return deleted;
    }

    public void setDeleted(Boolean deleted) {
        this.deleted = deleted;
    }

    public Instant getArchivedAt() {
        return archivedAt;
    }

    public void setArchivedAt(Instant archivedAt) {
        this.archivedAt = archivedAt;
    }

    public Instant getDeletedAt() {
        return deletedAt;
    }

    public void setDeletedAt(Instant deletedAt) {
        this.deletedAt = deletedAt;
    }

    public Long getDeletedBy() {
        return deletedBy;
    }

    public void setDeletedBy(Long deletedBy) {
        this.deletedBy = deletedBy;
    }
}
