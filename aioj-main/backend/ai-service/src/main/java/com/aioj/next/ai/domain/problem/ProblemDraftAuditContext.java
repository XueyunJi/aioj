package com.aioj.next.ai.domain.problem;

public record ProblemDraftAuditContext(Long jobId) {
    public static final ProblemDraftAuditContext NONE = new ProblemDraftAuditContext(null);

    public boolean hasJob() {
        return jobId != null;
    }
}
