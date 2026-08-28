package com.aioj.next.contract.auth;

import com.aioj.next.common.security.Role;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Size;

import java.util.List;
import java.util.Set;

public record AdminUserBatchPreviewRequest(
        String mode,
        @Size(max = 64) String prefix,
        Integer start,
        Integer count,
        Integer width,
        @Size(max = 20000) String importText,
        Set<Role> defaultRoles,
        Boolean enabled,
        Boolean passwordResetRequired,
        List<@Valid AdminUserBatchCandidateRequest> candidates
) {
}
