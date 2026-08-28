package com.aioj.next.contract.auth;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

import java.util.List;

public record AdminUserBatchCreateRequest(
        @NotEmpty @Size(max = 200) List<@Valid AdminUserBatchCandidateRequest> users,
        @NotNull @Valid AdminUserBatchPasswordRequest password
) {
}
