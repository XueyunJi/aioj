package com.aioj.next.contract.contest;

import com.fasterxml.jackson.annotation.JsonInclude;

import java.time.Instant;
import java.util.List;

public record ContestRunResponse(
        Long id,
        Long contestId,
        ContestRunKind runKind,
        String title,
        ContestRunStatus status,
        Instant startAt,
        Instant endAt,
        @JsonInclude(JsonInclude.Include.NON_NULL)
        Instant freezeAt,
        @JsonInclude(JsonInclude.Include.NON_NULL)
        Long sourceRunId,
        ContestRegistrationPolicy registrationPolicy,
        ContestRegistrationAccess registrationAccess,
        boolean approvalRequired,
        List<Long> allowedGroupIds,
        @JsonInclude(JsonInclude.Include.NON_NULL)
        Instant registrationStartAt,
        @JsonInclude(JsonInclude.Include.NON_NULL)
        Instant registrationEndAt,
        @JsonInclude(JsonInclude.Include.NON_NULL)
        Integer maxParticipants,
        @JsonInclude(JsonInclude.Include.NON_NULL)
        String contestTitleSnapshot,
        @JsonInclude(JsonInclude.Include.NON_NULL)
        String contestDescriptionSnapshot,
        @JsonInclude(JsonInclude.Include.NON_NULL)
        ContestMode modeSnapshot,
        @JsonInclude(JsonInclude.Include.NON_NULL)
        Integer penaltyMinutesSnapshot,
        @JsonInclude(JsonInclude.Include.NON_NULL)
        Boolean cePenaltySnapshot,
        @JsonInclude(JsonInclude.Include.NON_NULL)
        Instant archivedAt,
        @JsonInclude(JsonInclude.Include.NON_NULL)
        String archiveReason,
        @JsonInclude(JsonInclude.Include.NON_NULL)
        ContestRunStatus statusBeforeArchive,
        @JsonInclude(JsonInclude.Include.NON_NULL)
        Instant deletedAt,
        @JsonInclude(JsonInclude.Include.NON_NULL)
        Long deletedBy,
        @JsonInclude(JsonInclude.Include.NON_NULL)
        Instant publicScoreboardUnfrozenAt,
        @JsonInclude(JsonInclude.Include.NON_NULL)
        Long publicScoreboardUnfrozenBy,
        @JsonInclude(JsonInclude.Include.NON_NULL)
        ContestAiPolicyMode aiPolicyModeSnapshot,
        @JsonInclude(JsonInclude.Include.NON_NULL)
        String aiPolicyNotesSnapshot,
        Long createdBy,
        Instant createdAt,
        Instant updatedAt
) {
}
