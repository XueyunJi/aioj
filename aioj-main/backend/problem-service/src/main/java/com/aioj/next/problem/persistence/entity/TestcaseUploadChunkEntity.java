package com.aioj.next.problem.persistence.entity;

import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;

import java.time.Instant;

@TableName("testcase_upload_chunks")
public class TestcaseUploadChunkEntity {
    @TableId
    private String uploadId;
    private Integer chunkIndex;
    private Long chunkSizeBytes;
    private String sha256;
    private String storagePath;
    private Instant createdAt;

    public String getUploadId() {
        return uploadId;
    }

    public void setUploadId(String uploadId) {
        this.uploadId = uploadId;
    }

    public Integer getChunkIndex() {
        return chunkIndex;
    }

    public void setChunkIndex(Integer chunkIndex) {
        this.chunkIndex = chunkIndex;
    }

    public Long getChunkSizeBytes() {
        return chunkSizeBytes;
    }

    public void setChunkSizeBytes(Long chunkSizeBytes) {
        this.chunkSizeBytes = chunkSizeBytes;
    }

    public String getSha256() {
        return sha256;
    }

    public void setSha256(String sha256) {
        this.sha256 = sha256;
    }

    public String getStoragePath() {
        return storagePath;
    }

    public void setStoragePath(String storagePath) {
        this.storagePath = storagePath;
    }

    public Instant getCreatedAt() {
        return createdAt;
    }

    public void setCreatedAt(Instant createdAt) {
        this.createdAt = createdAt;
    }
}
