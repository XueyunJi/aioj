package com.aioj.next.contract.contest;

import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.Size;

import java.util.List;

/** Batch invitation targets selected from the authorized user directory. */
public record ContestInvitationBatchRequest(
        @NotEmpty @Size(max = 100) List<Long> userIds
) {
}
