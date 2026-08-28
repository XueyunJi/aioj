package com.aioj.next.ai.domain;

import com.aioj.next.common.error.DomainException;
import com.aioj.next.common.error.ErrorCode;

/**
 * Thrown when a chat turn reproduces a running contest problem's content outside the
 * legitimate contest assistance context. Carries the affected contest coordinates so
 * the blocked turn can be attributed to that contest in admin AI usage records.
 */
public class ContestProblemLeakBlockedException extends DomainException {
    private final Long contestId;
    private final Long contestRunId;
    private final Long problemId;
    private final Long contestProblemId;

    public ContestProblemLeakBlockedException(Long contestId, Long contestRunId, Long problemId, Long contestProblemId) {
        super(ErrorCode.FORBIDDEN, "This question appears to reference a problem from a running contest");
        this.contestId = contestId;
        this.contestRunId = contestRunId;
        this.problemId = problemId;
        this.contestProblemId = contestProblemId;
    }

    public Long contestId() {
        return contestId;
    }

    public Long contestRunId() {
        return contestRunId;
    }

    public Long problemId() {
        return problemId;
    }

    public Long contestProblemId() {
        return contestProblemId;
    }
}
