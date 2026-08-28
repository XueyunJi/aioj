package com.aioj.next.judge.persistence.entity;

import com.aioj.next.contract.problem.TestcasePackageStatus;
import com.aioj.next.contract.problem.TestcaseCheckerProtocol;
import com.aioj.next.contract.problem.TestcaseCheckerType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;

import java.time.Instant;

@TableName("testcase_packages")
public class TestcasePackageEntity {
    @TableId
    private Long id;
    private Long problemId;
    private String version;
    private String fileName;
    private Long fileSizeBytes;
    private String sha256;
    private TestcasePackageStatus status;
    private Boolean active;
    private String storageProvider;
    private String storageKey;
    private Integer caseCount;
    private Integer sampleCount;
    private TestcaseCheckerType checkerType;
    private String checkerLanguage;
    private String checkerSourcePath;
    private TestcaseCheckerProtocol checkerProtocol;
    private Instant activatedAt;
    private String errorMessage;

    public Long getId() {
        return id;
    }

    public void setId(Long id) {
        this.id = id;
    }

    public Long getProblemId() {
        return problemId;
    }

    public void setProblemId(Long problemId) {
        this.problemId = problemId;
    }

    public String getVersion() {
        return version;
    }

    public void setVersion(String version) {
        this.version = version;
    }

    public String getFileName() {
        return fileName;
    }

    public void setFileName(String fileName) {
        this.fileName = fileName;
    }

    public Long getFileSizeBytes() {
        return fileSizeBytes;
    }

    public void setFileSizeBytes(Long fileSizeBytes) {
        this.fileSizeBytes = fileSizeBytes;
    }

    public String getSha256() {
        return sha256;
    }

    public void setSha256(String sha256) {
        this.sha256 = sha256;
    }

    public TestcasePackageStatus getStatus() {
        return status;
    }

    public void setStatus(TestcasePackageStatus status) {
        this.status = status;
    }

    public Boolean getActive() {
        return active;
    }

    public void setActive(Boolean active) {
        this.active = active;
    }

    public String getStorageProvider() {
        return storageProvider;
    }

    public void setStorageProvider(String storageProvider) {
        this.storageProvider = storageProvider;
    }

    public String getStorageKey() {
        return storageKey;
    }

    public void setStorageKey(String storageKey) {
        this.storageKey = storageKey;
    }

    public Integer getCaseCount() {
        return caseCount;
    }

    public void setCaseCount(Integer caseCount) {
        this.caseCount = caseCount;
    }

    public Integer getSampleCount() {
        return sampleCount;
    }

    public void setSampleCount(Integer sampleCount) {
        this.sampleCount = sampleCount;
    }

    public TestcaseCheckerType getCheckerType() {
        return checkerType;
    }

    public void setCheckerType(TestcaseCheckerType checkerType) {
        this.checkerType = checkerType;
    }

    public String getCheckerLanguage() {
        return checkerLanguage;
    }

    public void setCheckerLanguage(String checkerLanguage) {
        this.checkerLanguage = checkerLanguage;
    }

    public String getCheckerSourcePath() {
        return checkerSourcePath;
    }

    public void setCheckerSourcePath(String checkerSourcePath) {
        this.checkerSourcePath = checkerSourcePath;
    }

    public TestcaseCheckerProtocol getCheckerProtocol() {
        return checkerProtocol;
    }

    public void setCheckerProtocol(TestcaseCheckerProtocol checkerProtocol) {
        this.checkerProtocol = checkerProtocol;
    }

    public Instant getActivatedAt() {
        return activatedAt;
    }

    public void setActivatedAt(Instant activatedAt) {
        this.activatedAt = activatedAt;
    }

    public String getErrorMessage() {
        return errorMessage;
    }

    public void setErrorMessage(String errorMessage) {
        this.errorMessage = errorMessage;
    }
}
