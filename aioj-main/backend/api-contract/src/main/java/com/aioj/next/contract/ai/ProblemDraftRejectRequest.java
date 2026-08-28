package com.aioj.next.contract.ai;

import jakarta.validation.constraints.Size;

public record ProblemDraftRejectRequest(@Size(max = 500) String reasonNote) {
}
