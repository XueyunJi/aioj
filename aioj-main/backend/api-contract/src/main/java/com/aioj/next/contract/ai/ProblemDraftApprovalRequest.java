package com.aioj.next.contract.ai;

import com.aioj.next.contract.problem.ProblemVisibility;

public record ProblemDraftApprovalRequest(Boolean importProblem, ProblemVisibility visibility) {
}
