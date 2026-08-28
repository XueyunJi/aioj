package com.aioj.next.problem.persistence.entity;

import com.aioj.next.contract.contest.PlagiarismAiStatus;
import com.aioj.next.contract.contest.PlagiarismReviewStatus;
import com.aioj.next.contract.contest.PlagiarismRiskLevel;
import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;

import java.time.Instant;

@TableName("plagiarism_pairs")
public class PlagiarismPairEntity {
    @TableId(type = IdType.ASSIGN_ID)
    private Long id;
    private Long jobId;
    private Long contestId;
    private Long contestProblemId;
    private Long problemId;
    private String language;
    private Long leftJobSubmissionId;
    private Long rightJobSubmissionId;
    private Long leftSubmissionId;
    private Long rightSubmissionId;
    private Long leftUserId;
    private Long rightUserId;
    private Long leftParticipantId;
    private Long rightParticipantId;
    private Double similarity;
    private Double maximalSimilarity;
    private Double minimalSimilarity;
    private Integer matchedTokens;
    private PlagiarismRiskLevel riskLevel;
    private PlagiarismReviewStatus reviewStatus;
    private String teacherNote;
    private Long reviewedBy;
    private Instant reviewedAt;
    private PlagiarismAiStatus aiStatus;
    private String aiSummary;
    private Instant createdAt;
    private Instant updatedAt;

    public Long getId() { return id; }
    public void setId(Long id) { this.id = id; }
    public Long getJobId() { return jobId; }
    public void setJobId(Long jobId) { this.jobId = jobId; }
    public Long getContestId() { return contestId; }
    public void setContestId(Long contestId) { this.contestId = contestId; }
    public Long getContestProblemId() { return contestProblemId; }
    public void setContestProblemId(Long contestProblemId) { this.contestProblemId = contestProblemId; }
    public Long getProblemId() { return problemId; }
    public void setProblemId(Long problemId) { this.problemId = problemId; }
    public String getLanguage() { return language; }
    public void setLanguage(String language) { this.language = language; }
    public Long getLeftJobSubmissionId() { return leftJobSubmissionId; }
    public void setLeftJobSubmissionId(Long leftJobSubmissionId) { this.leftJobSubmissionId = leftJobSubmissionId; }
    public Long getRightJobSubmissionId() { return rightJobSubmissionId; }
    public void setRightJobSubmissionId(Long rightJobSubmissionId) { this.rightJobSubmissionId = rightJobSubmissionId; }
    public Long getLeftSubmissionId() { return leftSubmissionId; }
    public void setLeftSubmissionId(Long leftSubmissionId) { this.leftSubmissionId = leftSubmissionId; }
    public Long getRightSubmissionId() { return rightSubmissionId; }
    public void setRightSubmissionId(Long rightSubmissionId) { this.rightSubmissionId = rightSubmissionId; }
    public Long getLeftUserId() { return leftUserId; }
    public void setLeftUserId(Long leftUserId) { this.leftUserId = leftUserId; }
    public Long getRightUserId() { return rightUserId; }
    public void setRightUserId(Long rightUserId) { this.rightUserId = rightUserId; }
    public Long getLeftParticipantId() { return leftParticipantId; }
    public void setLeftParticipantId(Long leftParticipantId) { this.leftParticipantId = leftParticipantId; }
    public Long getRightParticipantId() { return rightParticipantId; }
    public void setRightParticipantId(Long rightParticipantId) { this.rightParticipantId = rightParticipantId; }
    public Double getSimilarity() { return similarity; }
    public void setSimilarity(Double similarity) { this.similarity = similarity; }
    public Double getMaximalSimilarity() { return maximalSimilarity; }
    public void setMaximalSimilarity(Double maximalSimilarity) { this.maximalSimilarity = maximalSimilarity; }
    public Double getMinimalSimilarity() { return minimalSimilarity; }
    public void setMinimalSimilarity(Double minimalSimilarity) { this.minimalSimilarity = minimalSimilarity; }
    public Integer getMatchedTokens() { return matchedTokens; }
    public void setMatchedTokens(Integer matchedTokens) { this.matchedTokens = matchedTokens; }
    public PlagiarismRiskLevel getRiskLevel() { return riskLevel; }
    public void setRiskLevel(PlagiarismRiskLevel riskLevel) { this.riskLevel = riskLevel; }
    public PlagiarismReviewStatus getReviewStatus() { return reviewStatus; }
    public void setReviewStatus(PlagiarismReviewStatus reviewStatus) { this.reviewStatus = reviewStatus; }
    public String getTeacherNote() { return teacherNote; }
    public void setTeacherNote(String teacherNote) { this.teacherNote = teacherNote; }
    public Long getReviewedBy() { return reviewedBy; }
    public void setReviewedBy(Long reviewedBy) { this.reviewedBy = reviewedBy; }
    public Instant getReviewedAt() { return reviewedAt; }
    public void setReviewedAt(Instant reviewedAt) { this.reviewedAt = reviewedAt; }
    public PlagiarismAiStatus getAiStatus() { return aiStatus; }
    public void setAiStatus(PlagiarismAiStatus aiStatus) { this.aiStatus = aiStatus; }
    public String getAiSummary() { return aiSummary; }
    public void setAiSummary(String aiSummary) { this.aiSummary = aiSummary; }
    public Instant getCreatedAt() { return createdAt; }
    public void setCreatedAt(Instant createdAt) { this.createdAt = createdAt; }
    public Instant getUpdatedAt() { return updatedAt; }
    public void setUpdatedAt(Instant updatedAt) { this.updatedAt = updatedAt; }
}
