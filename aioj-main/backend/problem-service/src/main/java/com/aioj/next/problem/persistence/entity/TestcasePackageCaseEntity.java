package com.aioj.next.problem.persistence.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;

import java.time.Instant;

@TableName("testcase_package_cases")
public class TestcasePackageCaseEntity {
    @TableId(type = IdType.ASSIGN_ID)
    private Long id;
    private Long packageId;
    private String name;
    private String inputPath;
    private String outputPath;
    private Boolean sample;
    private Integer score;
    private String subtaskKey;
    private Long inputSizeBytes;
    private Long outputSizeBytes;
    private Integer sortOrder;
    private Instant createdAt;

    public Long getId() {
        return id;
    }

    public void setId(Long id) {
        this.id = id;
    }

    public Long getPackageId() {
        return packageId;
    }

    public void setPackageId(Long packageId) {
        this.packageId = packageId;
    }

    public String getName() {
        return name;
    }

    public void setName(String name) {
        this.name = name;
    }

    public String getInputPath() {
        return inputPath;
    }

    public void setInputPath(String inputPath) {
        this.inputPath = inputPath;
    }

    public String getOutputPath() {
        return outputPath;
    }

    public void setOutputPath(String outputPath) {
        this.outputPath = outputPath;
    }

    public Boolean getSample() {
        return sample;
    }

    public void setSample(Boolean sample) {
        this.sample = sample;
    }

    public Integer getScore() {
        return score;
    }

    public void setScore(Integer score) {
        this.score = score;
    }

    public String getSubtaskKey() {
        return subtaskKey;
    }

    public void setSubtaskKey(String subtaskKey) {
        this.subtaskKey = subtaskKey;
    }

    public Long getInputSizeBytes() {
        return inputSizeBytes;
    }

    public void setInputSizeBytes(Long inputSizeBytes) {
        this.inputSizeBytes = inputSizeBytes;
    }

    public Long getOutputSizeBytes() {
        return outputSizeBytes;
    }

    public void setOutputSizeBytes(Long outputSizeBytes) {
        this.outputSizeBytes = outputSizeBytes;
    }

    public Integer getSortOrder() {
        return sortOrder;
    }

    public void setSortOrder(Integer sortOrder) {
        this.sortOrder = sortOrder;
    }

    public Instant getCreatedAt() {
        return createdAt;
    }

    public void setCreatedAt(Instant createdAt) {
        this.createdAt = createdAt;
    }
}
