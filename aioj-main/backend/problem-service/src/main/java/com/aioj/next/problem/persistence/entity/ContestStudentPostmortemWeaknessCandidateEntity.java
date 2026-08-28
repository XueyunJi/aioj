package com.aioj.next.problem.persistence.entity;

import com.aioj.next.contract.contest.ContestStudentPostmortemWeaknessCandidateStatus;
import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;

import java.math.BigDecimal;
import java.time.Instant;

@TableName("contest_student_postmortem_weakness_candidates")
public class ContestStudentPostmortemWeaknessCandidateEntity {
    @TableId(type = IdType.ASSIGN_ID)
    private Long id;
    private Long reportId;
    private Long contestId;
    private Long contestRunId;
    private Long contestParticipantId;
    private Long userId;
    private ContestStudentPostmortemWeaknessCandidateStatus status;
    private String knowledgeNode;
    private String symptom;
    private String tagsJson;
    private String evidenceJson;
    private BigDecimal confidence;
    private Long memoryId;
    private Long weaknessId;
    private Instant createdAt;
    private Instant updatedAt;
    private Instant decidedAt;

    public Long getId() { return id; }
    public void setId(Long id) { this.id = id; }
    public Long getReportId() { return reportId; }
    public void setReportId(Long reportId) { this.reportId = reportId; }
    public Long getContestId() { return contestId; }
    public void setContestId(Long contestId) { this.contestId = contestId; }
    public Long getContestRunId() { return contestRunId; }
    public void setContestRunId(Long contestRunId) { this.contestRunId = contestRunId; }
    public Long getContestParticipantId() { return contestParticipantId; }
    public void setContestParticipantId(Long contestParticipantId) { this.contestParticipantId = contestParticipantId; }
    public Long getUserId() { return userId; }
    public void setUserId(Long userId) { this.userId = userId; }
    public ContestStudentPostmortemWeaknessCandidateStatus getStatus() { return status; }
    public void setStatus(ContestStudentPostmortemWeaknessCandidateStatus status) { this.status = status; }
    public String getKnowledgeNode() { return knowledgeNode; }
    public void setKnowledgeNode(String knowledgeNode) { this.knowledgeNode = knowledgeNode; }
    public String getSymptom() { return symptom; }
    public void setSymptom(String symptom) { this.symptom = symptom; }
    public String getTagsJson() { return tagsJson; }
    public void setTagsJson(String tagsJson) { this.tagsJson = tagsJson; }
    public String getEvidenceJson() { return evidenceJson; }
    public void setEvidenceJson(String evidenceJson) { this.evidenceJson = evidenceJson; }
    public BigDecimal getConfidence() { return confidence; }
    public void setConfidence(BigDecimal confidence) { this.confidence = confidence; }
    public Long getMemoryId() { return memoryId; }
    public void setMemoryId(Long memoryId) { this.memoryId = memoryId; }
    public Long getWeaknessId() { return weaknessId; }
    public void setWeaknessId(Long weaknessId) { this.weaknessId = weaknessId; }
    public Instant getCreatedAt() { return createdAt; }
    public void setCreatedAt(Instant createdAt) { this.createdAt = createdAt; }
    public Instant getUpdatedAt() { return updatedAt; }
    public void setUpdatedAt(Instant updatedAt) { this.updatedAt = updatedAt; }
    public Instant getDecidedAt() { return decidedAt; }
    public void setDecidedAt(Instant decidedAt) { this.decidedAt = decidedAt; }
}
