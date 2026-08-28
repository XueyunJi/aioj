package com.aioj.next.ai.domain.problem;

import com.aioj.next.contract.ai.ProblemDraftResponse;

public interface ProblemDraftVerifier {
    VerificationReport verify(ProblemDraftResponse draft, VerificationOptions options);
}
