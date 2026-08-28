package com.aioj.next.problem.persistence.entity;

import com.aioj.next.contract.contest.ContestRegistrationStatus;
import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;

import java.time.Instant;

@TableName("contest_registrations")
public class ContestRegistrationEntity {
    @TableId(type = IdType.ASSIGN_ID)
    private Long id;
    private Long contestId;
    private Long contestRunId;
    private Long userId;
    private ContestRegistrationStatus status;
    private Instant requestedAt;
    private Long reviewedBy;
    private Instant approvedAt;
    private Instant rejectedAt;
    private Instant cancelledAt;
    private String rejectReason;
    private Long invitationNotificationVersion;
    private Long invitationNotificationDeliveredVersion;
    private Instant createdAt;
    private Instant updatedAt;

    public Long getId() { return id; }
    public void setId(Long id) { this.id = id; }
    public Long getContestId() { return contestId; }
    public void setContestId(Long contestId) { this.contestId = contestId; }
    public Long getContestRunId() { return contestRunId; }
    public void setContestRunId(Long contestRunId) { this.contestRunId = contestRunId; }
    public Long getUserId() { return userId; }
    public void setUserId(Long userId) { this.userId = userId; }
    public ContestRegistrationStatus getStatus() { return status; }
    public void setStatus(ContestRegistrationStatus status) { this.status = status; }
    public Instant getRequestedAt() { return requestedAt; }
    public void setRequestedAt(Instant requestedAt) { this.requestedAt = requestedAt; }
    public Long getReviewedBy() { return reviewedBy; }
    public void setReviewedBy(Long reviewedBy) { this.reviewedBy = reviewedBy; }
    public Instant getApprovedAt() { return approvedAt; }
    public void setApprovedAt(Instant approvedAt) { this.approvedAt = approvedAt; }
    public Instant getRejectedAt() { return rejectedAt; }
    public void setRejectedAt(Instant rejectedAt) { this.rejectedAt = rejectedAt; }
    public Instant getCancelledAt() { return cancelledAt; }
    public void setCancelledAt(Instant cancelledAt) { this.cancelledAt = cancelledAt; }
    public String getRejectReason() { return rejectReason; }
    public void setRejectReason(String rejectReason) { this.rejectReason = rejectReason; }
    public Long getInvitationNotificationVersion() { return invitationNotificationVersion; }
    public void setInvitationNotificationVersion(Long invitationNotificationVersion) { this.invitationNotificationVersion = invitationNotificationVersion; }
    public Long getInvitationNotificationDeliveredVersion() { return invitationNotificationDeliveredVersion; }
    public void setInvitationNotificationDeliveredVersion(Long invitationNotificationDeliveredVersion) {
        this.invitationNotificationDeliveredVersion = invitationNotificationDeliveredVersion;
    }
    public Instant getCreatedAt() { return createdAt; }
    public void setCreatedAt(Instant createdAt) { this.createdAt = createdAt; }
    public Instant getUpdatedAt() { return updatedAt; }
    public void setUpdatedAt(Instant updatedAt) { this.updatedAt = updatedAt; }
}
